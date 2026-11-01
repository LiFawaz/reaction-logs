import { Stores, onFluxEventDispatched } from '@valenced/discord/flux'
import { getModules } from '@valenced/modules/finders'
import { withProps } from '@valenced/modules/finders/filters'
import { instead } from '@valenced/patcher'
import { setRemoved, setTimeFormat24Hour, clearRemoved } from './native'
import SettingsComponent from './settings'
import { DefaultSettings } from './shared'
import type { Settings } from './shared'

interface Emoji {
    id?: string | null
    name: string
}

interface ReactionPayload {
    channelId?: string
    messageId?: string
    id?: string
    ids?: string[]
    userId?: string
    emoji?: Emoji
    reactionType?: number
    burst?: boolean
    optimistic?: boolean
}

interface CachedUser {
    id: string
    globalName?: string | null
    displayName?: string | null
    username?: string | null
}

interface UserStoreShape {
    getUser(id: string): CachedUser | undefined
    getCurrentUser(): CachedUser | undefined
}

interface RemoveReactionOptions {
    channelId: string
    messageId: string
    emoji: Emoji
    location?: string
    userId?: string
}

interface ReactionActionCreators {
    addReaction(
        channelId: string,
        messageId: string,
        emoji: Emoji,
        location?: string,
    ): unknown
    removeReaction(options: RemoveReactionOptions): unknown
    ReactionLocations?: {
        MESSAGE?: string
    }
}

interface PendingUserRemoval {
    removedAt: number
    name: string
}

interface PendingRemoval {
    users: Map<string, PendingUserRemoval>
    anonymous: number[]
}

const ECHO_WINDOW_MS = 10_000

export default plugin<{ jsonStorage: Settings }>({
    jsonStorage: {
        load: true,
        default: DefaultSettings,
    },

    start({ cleanup, plugin, jsonStorage }) {
        setTimeFormat24Hour(jsonStorage.cache?.clockFormat === '24h')

        const userStore = Stores.UserStore as unknown as UserStoreShape
        const pendingRemovals = new Map<string, PendingRemoval>()
        const pendingAddEchoes = new Map<string, number>()

        const getCurrentUserId = () => userStore.getCurrentUser()?.id

        const getUserName = (userId: string) => {
            const user = userStore.getUser(userId)
            return (
                user?.globalName?.trim() ||
                user?.displayName?.trim() ||
                user?.username?.trim() ||
                userId
            )
        }

        const latestRemovalAt = (state: PendingRemoval): number | null => {
            let latest = 0

            for (const removal of state.users.values()) {
                if (removal.removedAt > latest) latest = removal.removedAt
            }
            for (const timestamp of state.anonymous) {
                if (timestamp > latest) latest = timestamp
            }

            return latest > 0 ? latest : null
        }

        const syncNative = () => {
            try {
                const entries = [...pendingRemovals].flatMap(([pillKey, state]) => {
                    const removedAt = latestRemovalAt(state)
                    if (removedAt === null) return []

                    return [{
                        pillKey,
                        removedAt,
                        removedUsers: [...state.users.entries()].map(([id, removal]) => ({
                            id,
                            name: removal.name,
                            removedAt: removal.removedAt,
                        })),
                    }]
                })
                setRemoved(JSON.stringify(entries))
            } catch (error) {
                plugin.reportError('Failed to refresh reaction logs: ' + String(error))
            }
        }

        const makeKey = (payload: ReactionPayload): string | null => {
            const emoji = payload.emoji
            if (!emoji?.name || !payload.messageId) return null

            const reactionType = payload.reactionType === 1 || payload.burst === true ? 1 : 0
            const emojiKey = emoji.id ? emoji.name + ':' + emoji.id : emoji.name

            return reactionKey(payload.messageId, emojiKey, reactionType)
        }

        const echoKey = (key: string, userId: string) => key + '\u0000' + userId

        const pruneEchoes = () => {
            const now = Date.now()
            for (const [token, expiresAt] of pendingAddEchoes) {
                if (expiresAt <= now) pendingAddEchoes.delete(token)
            }
        }

        const clearMatchingRemoval = (key: string, userId?: string): boolean => {
            const state = pendingRemovals.get(key)
            if (!state) return false

            let changed = false
            if (userId) {
                changed = state.users.delete(userId)
            } else if (state.anonymous.length > 0) {
                state.anonymous.shift()
                changed = true
            }

            if (state.users.size === 0 && state.anonymous.length === 0) {
                pendingRemovals.delete(key)
            }

            return changed
        }

        const clearMessage = (messageId?: string) => {
            if (!messageId) return false
            const prefix = messageId + '|'
            let changed = false

            for (const key of pendingRemovals.keys()) {
                if (key.startsWith(prefix)) {
                    pendingRemovals.delete(key)
                    changed = true
                }
            }
            for (const token of pendingAddEchoes.keys()) {
                if (token.startsWith(prefix)) pendingAddEchoes.delete(token)
            }

            return changed
        }

        // A removed pill remains visible locally, but a second tap on your own
        // retained pill must add your reaction instead of sending another DELETE.
        cleanup(
            getModules(
                withProps<ReactionActionCreators>(
                    'addReaction',
                    'removeReaction',
                ),
                creators => {
                    cleanup(
                        instead(
                            creators,
                            'removeReaction',
                            function (args, original) {
                                const options = args[0] as
                                    | RemoveReactionOptions
                                    | undefined
                                if (
                                    !options?.channelId ||
                                    !options.messageId ||
                                    !options.emoji?.name
                                ) {
                                    return Reflect.apply(original, this, args)
                                }

                                const userId = options.userId ?? getCurrentUserId()
                                const key = reactionKey(
                                    options.messageId,
                                    options.emoji.id
                                        ? options.emoji.name + ':' + options.emoji.id
                                        : options.emoji.name,
                                    0,
                                )

                                if (
                                    !userId ||
                                    !pendingRemovals.get(key)?.users.has(userId)
                                ) {
                                    return Reflect.apply(original, this, args)
                                }

                                const location =
                                    options.location ??
                                    creators.ReactionLocations?.MESSAGE ??
                                    'Message'

                                // The original removal event was retained locally.
                                // Re-adding here updates the server and its optimistic
                                // ADD is balanced by the event hook below.
                                return creators.addReaction(
                                    options.channelId,
                                    options.messageId,
                                    options.emoji,
                                    location,
                                )
                            },
                        ),
                    )
                },
                { max: 1 },
            ),
        )

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    pruneEchoes()
                    const key = makeKey(payload)
                    if (!key) return payload

                    const state = pendingRemovals.get(key) ?? {
                        users: new Map<string, PendingUserRemoval>(),
                        anonymous: [],
                    }

                    const removedAt = Date.now()
                    const userId = payload.userId

                    if (userId) {
                        // An optimistic removal may be followed by its gateway echo.
                        // Save one entry per affected user and retain the local pill.
                        if (state.users.has(userId)) return undefined
                        state.users.set(userId, {
                            removedAt,
                            name: getUserName(userId),
                        })
                        pendingAddEchoes.delete(echoKey(key, userId))
                    } else {
                        state.anonymous.push(removedAt)
                    }

                    pendingRemovals.set(key, state)
                    syncNative()

                    // Keep Discord's local reaction row untouched. The server still
                    // receives the remove request from ReactionActionCreators.
                    return undefined
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    pruneEchoes()
                    const key = makeKey(payload)
                    if (!key) return payload

                    const userId = payload.userId
                    const token = userId ? echoKey(key, userId) : null

                    // If we balanced an optimistic add, discard its later gateway echo.
                    if (token && !payload.optimistic && pendingAddEchoes.has(token)) {
                        pendingAddEchoes.delete(token)
                        return undefined
                    }

                    const balanced = clearMatchingRemoval(key, userId)
                    if (balanced) {
                        if (token && payload.optimistic) {
                            pendingAddEchoes.set(token, Date.now() + ECHO_WINDOW_MS)
                        }
                        syncNative()
                        // The locally retained pill already includes this reaction.
                        return undefined
                    }

                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    if (clearMessage(payload.messageId)) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_EMOJI',
                payload => {
                    if (!payload.messageId || !payload.emoji?.name) return payload
                    const emojiKey = payload.emoji.id
                        ? payload.emoji.name + ':' + payload.emoji.id
                        : payload.emoji.name
                    const prefix = payload.messageId + '|' + emojiKey + '|'
                    let changed = false

                    for (const key of pendingRemovals.keys()) {
                        if (key.startsWith(prefix)) {
                            pendingRemovals.delete(key)
                            changed = true
                        }
                    }
                    for (const token of pendingAddEchoes.keys()) {
                        if (token.startsWith(prefix)) pendingAddEchoes.delete(token)
                    }

                    if (changed) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE',
                payload => {
                    if (clearMessage(payload.id ?? payload.messageId)) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE_BULK',
                payload => {
                    let changed = false
                    for (const messageId of payload.ids ?? []) {
                        changed = clearMessage(messageId) || changed
                    }
                    if (changed) syncNative()
                    return payload
                },
            ),

            () => {
                pendingRemovals.clear()
                pendingAddEchoes.clear()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear reaction logs: ' + String(error))
                }
            },
        )
    },

    SettingsComponent,
})

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + '|' + emojiKey + '|' + reactionType
}
