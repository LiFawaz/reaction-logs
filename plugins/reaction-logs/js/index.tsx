import { Stores, onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemovalLogs, setRetainedPills, clearNativeState } from './native'
import { DefaultStorage } from './shared'
import type { ReactionStorage, RemovalLogEntry } from './shared'

interface Emoji {
    id?: string | null
    name: string
}

interface EventUser {
    id?: string | number | null
}

interface ReactionPayload {
    channelId?: string
    messageId?: string
    id?: string
    ids?: string[]
    userId?: string
    user_id?: string
    user?: EventUser | null
    member?: { id?: string | number | null; user?: EventUser | null } | null
    emoji?: Emoji
    reactionType?: number
    burst?: boolean
    optimistic?: boolean
}

type UserTimes = Map<string, number>

const keyFor = (messageId: string, emojiKey: string, reactionType: number) =>
    messageId + '|' + emojiKey + '|' + reactionType

const getEmojiPart = (emoji: Emoji | undefined): string | null => {
    if (!emoji?.name) return null
    return emoji.id ? emoji.name + ':' + emoji.id : emoji.name
}

const getEventUserId = (payload: ReactionPayload): string | undefined => {
    const value =
        payload.userId ??
        payload.user_id ??
        payload.user?.id ??
        payload.member?.user?.id ??
        payload.member?.id
    if (value === undefined || value === null || String(value).trim() === '') return undefined
    return String(value)
}

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    start({ cleanup, plugin, jsonStorage }) {
        // Removal logging and red-pill retention are deliberately independent.
        const removalLogs = new Map<string, UserTimes>()
        const retainedPillKeys = new Set<string>()

        const cache = jsonStorage.cache ?? DefaultStorage

        // Session-only state: do not restore previous runs after a full app restart.
        // Events are persisted while this process is alive, but the next app start
        // begins with an empty log.
        let persistQueue = Promise.resolve()
        const pendingRemovalEchoes = new Set<string>()
        const pendingAddEchoes = new Set<string>()

        const getCurrentUserId = (): string | undefined => {
            try {
                const userStore = Stores.UserStore as unknown as {
                    getCurrentUser?: () => { id?: string } | undefined
                }
                return userStore.getCurrentUser?.()?.id
            } catch {
                return undefined
            }
        }

        const makeKey = (payload: ReactionPayload): string | null => {
            if (!payload.messageId) return null
            const emojiPart = getEmojiPart(payload.emoji)
            if (!emojiPart) return null
            const reactionType = payload.reactionType === 1 || payload.burst === true ? 1 : 0
            return keyFor(payload.messageId, emojiPart, reactionType)
        }

        const syncRemovalLogs = () => {
            try {
                const entries: RemovalLogEntry[] = [...removalLogs].map(([pillKey, users]) => {
                    const removedUsers = [...users].map(([id, removedAt]) => ({ id, removedAt }))
                    const removedAt = removedUsers.reduce(
                        (latest, user) => Math.max(latest, user.removedAt),
                        0,
                    )
                    return { pillKey, removedAt, removedUsers }
                })
                setRemovalLogs(JSON.stringify(entries))
            } catch (error) {
                plugin.reportError('Failed to sync removed-reaction logs: ' + String(error))
            }
        }

        const syncRetainedPills = () => {
            try {
                setRetainedPills(JSON.stringify([...retainedPillKeys]))
            } catch (error) {
                plugin.reportError('Failed to sync retained reaction pills: ' + String(error))
            }
        }

        const syncNative = () => {
            syncRemovalLogs()
            syncRetainedPills()
        }

        const persistState = () => {
            const snapshot: ReactionStorage = {
                removalLogs: [...removalLogs].map(([pillKey, users]) => {
                    const removedUsers = [...users].map(([id, removedAt]) => ({ id, removedAt }))
                    const removedAt = removedUsers.reduce(
                        (latest, user) => Math.max(latest, user.removedAt),
                        0,
                    )
                    return { pillKey, removedAt, removedUsers }
                }),
                retainedPillKeys: [...retainedPillKeys],
            }

            persistQueue = persistQueue
                .then(() => jsonStorage.set(snapshot, true))
                .catch(error => {
                    plugin.reportError('Failed to save reaction logs: ' + String(error))
                })
        }

        const recordRemoval = (payload: ReactionPayload, key: string, knownUserId?: string) => {
            const userId = knownUserId ?? getEventUserId(payload) ?? '*'
            const users = removalLogs.get(key) ?? new Map<string, number>()
            users.set(userId, Date.now())
            removalLogs.set(key, users)
            retainedPillKeys.add(key)
        }

        // Adding an emoji clears only that user's old removal log. The pill remains
        // red while another removed reactor is still recorded for the same emoji.
        const clearRemovalForUser = (key: string, userId: string): boolean => {
            const users = removalLogs.get(key)
            if (!users || !users.delete(userId)) return false

            if (users.size === 0) {
                removalLogs.delete(key)
                retainedPillKeys.delete(key)
            }
            return true
        }

        const clearMessage = (messageId?: string): boolean => {
            if (!messageId) return false
            const prefix = messageId + '|'
            let changed = false

            for (const key of [...removalLogs.keys()]) {
                if (key.startsWith(prefix)) {
                    removalLogs.delete(key)
                    changed = true
                }
            }
            for (const key of [...retainedPillKeys]) {
                if (key.startsWith(prefix)) {
                    retainedPillKeys.delete(key)
                    changed = true
                }
            }
            for (const token of [...pendingRemovalEchoes]) {
                if (token.startsWith(prefix)) pendingRemovalEchoes.delete(token)
            }
            for (const token of [...pendingAddEchoes]) {
                if (token.startsWith(prefix)) pendingAddEchoes.delete(token)
            }
            return changed
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const userId = getEventUserId(payload) ?? getCurrentUserId() ?? '*'
                    const token = key + '\u0000' + userId

                    // Record an optimistic remove once. Its gateway echo is still
                    // allowed to update Discord, but must not create a second log.
                    if (payload.optimistic) {
                        pendingRemovalEchoes.add(token)
                        recordRemoval(payload, key, userId)
                        persistState()
                        syncNative()
                        return payload
                    }

                    if (pendingRemovalEchoes.delete(token)) {
                        return payload
                    }

                    recordRemoval(payload, key, userId)
                    persistState()
                    syncNative()

                    // Never cancel the Flux removal. Discord must update the actual
                    // reaction count and the current user's "me" state. The native
                    // pill-retention layer independently keeps the red local pill.
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const userId = getEventUserId(payload) ?? getCurrentUserId()
                    if (userId) {
                        const token = key + '\u0000' + userId

                        // The optimistic local add has already updated Discord.
                        // Suppress only its duplicate gateway echo.
                        if (!payload.optimistic && pendingAddEchoes.delete(token)) {
                            return undefined
                        }
                        if (payload.optimistic) pendingAddEchoes.add(token)

                        if (clearRemovalForUser(key, userId)) {
                            persistState()
                            syncNative()
                        }
                    }

                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    if (clearMessage(payload.messageId)) {
                        persistState()
                        syncNative()
                    }
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_EMOJI',
                payload => {
                    if (!payload.messageId || !payload.emoji) return payload
                    const emojiPart = getEmojiPart(payload.emoji)
                    if (!emojiPart) return payload
                    const prefix = payload.messageId + '|' + emojiPart + '|'
                    let changed = false

                    for (const key of [...removalLogs.keys()]) {
                        if (key.startsWith(prefix)) {
                            removalLogs.delete(key)
                            changed = true
                        }
                    }
                    for (const key of [...retainedPillKeys]) {
                        if (key.startsWith(prefix)) {
                            retainedPillKeys.delete(key)
                            changed = true
                        }
                    }

                    if (changed) {
                        persistState()
                        syncNative()
                    }
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE',
                payload => {
                    if (clearMessage(payload.id ?? payload.messageId)) {
                        persistState()
                        syncNative()
                    }
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
                    if (changed) {
                        persistState()
                        syncNative()
                    }
                    return payload
                },
            ),

            () => {
                removalLogs.clear()
                retainedPillKeys.clear()
                pendingRemovalEchoes.clear()
                pendingAddEchoes.clear()
                persistState()
                try {
                    clearNativeState()
                } catch (error) {
                    plugin.reportError('Could not clear native reaction-log state: ' + String(error))
                }
            },
        )

        syncNative()
        persistState()
    },
})
