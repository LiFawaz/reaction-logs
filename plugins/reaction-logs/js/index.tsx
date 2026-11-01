import { Stores, onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import SettingsComponent from './settings'
import { DefaultStorage } from './shared'
import type { HistoryStorage, RemovalLogEntry } from './shared'

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
}

const REMOVAL_ECHO_WINDOW_MS = 10_000
const MAX_HISTORY_ENTRIES = 500

export default plugin<{ jsonStorage: HistoryStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    start({ cleanup, plugin, jsonStorage }) {
        // Active removals only drive the temporary red reaction-pill style.
        // The append-only history below is a separate, persistent log.
        const activeRemovals = new Map<string, Set<string>>()
        const recentRemovalEchoes = new Map<string, number>()
        const userStore = Stores.UserStore as unknown as UserStoreShape

        let history = [...(jsonStorage.cache?.entries ?? [])]
        let nextId = jsonStorage.cache?.nextId ?? 1
        let persistQueue = Promise.resolve()

        const persistHistory = () => {
            const snapshot: HistoryStorage = {
                entries: [...history],
                nextId,
            }
            persistQueue = persistQueue
                .then(() => jsonStorage.set(snapshot, true))
                .catch(error => {
                    plugin.reportError('Failed to save reaction history: ' + String(error))
                })
        }

        const syncNative = () => {
            try {
                const entries = [...activeRemovals].map(([pillKey, users]) => ({
                    pillKey,
                    removedUsers: [...users]
                        .filter(userId => userId !== '*')
                        .map(id => ({ id })),
                }))
                setRemoved(JSON.stringify(entries))
            } catch (error) {
                plugin.reportError('Failed to refresh removed reaction highlights: ' + String(error))
            }
        }

        const makeKey = (payload: ReactionPayload): string | null => {
            const emoji = payload.emoji
            if (!emoji?.name || !payload.messageId) return null

            const reactionType = payload.reactionType === 1 || payload.burst === true ? 1 : 0
            const emojiKey = emoji.id ? emoji.name + ':' + emoji.id : emoji.name
            return reactionKey(payload.messageId, emojiKey, reactionType)
        }

        const getUserName = (userId: string) => {
            const user = userStore.getUser(userId)
            return (
                user?.globalName?.trim() ||
                user?.displayName?.trim() ||
                user?.username?.trim() ||
                userId
            )
        }

        const recordRemoval = (payload: ReactionPayload, key: string) => {
            const userId = payload.userId || '*'
            const token = key + '\u0000' + userId
            const now = Date.now()

            // Discord can dispatch an optimistic removal and then its gateway echo.
            // Ignore only that duplicate pair. A later add clears the token so a
            // genuine remove-after-readd is logged as a new entry.
            for (const [recentToken, expiresAt] of recentRemovalEchoes) {
                if (expiresAt <= now) recentRemovalEchoes.delete(recentToken)
            }
            const expiresAt = recentRemovalEchoes.get(token)
            if (userId !== '*' && expiresAt !== undefined && expiresAt > now) {
                recentRemovalEchoes.delete(token)
                return false
            }
            if (userId !== '*') {
                recentRemovalEchoes.set(token, now + REMOVAL_ECHO_WINDOW_MS)
            }

            const users = activeRemovals.get(key) ?? new Set<string>()
            users.add(userId)
            activeRemovals.set(key, users)

            const emoji = payload.emoji
            const entry: RemovalLogEntry = {
                id: String(nextId++),
                messageId: payload.messageId ?? '',
                channelId: payload.channelId ?? '',
                emojiName: emoji?.name ?? 'Unknown emoji',
                emojiId: emoji?.id ?? null,
                reactionType: payload.reactionType === 1 || payload.burst === true ? 1 : 0,
                userId,
                userName: userId === '*' ? 'Unknown user' : getUserName(userId),
            }

            history = [...history, entry].slice(-MAX_HISTORY_ENTRIES)
            persistHistory()
            syncNative()
            return true
        }

        const clearActiveForKey = (key: string, userId?: string) => {
            const users = activeRemovals.get(key)
            if (!users) return false

            if (userId) {
                users.delete(userId)
                users.delete('*')
            } else {
                users.clear()
            }

            if (users.size === 0) activeRemovals.delete(key)
            return true
        }

        const clearActiveMessage = (messageId?: string) => {
            if (!messageId) return false
            const prefix = messageId + '|'
            let changed = false

            for (const key of activeRemovals.keys()) {
                if (key.startsWith(prefix)) {
                    activeRemovals.delete(key)
                    changed = true
                }
            }
            return changed
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    const key = makeKey(payload)
                    if (key) recordRemoval(payload, key)
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const userId = payload.userId || '*'
                    recentRemovalEchoes.delete(key + '\u0000' + userId)

                    // Adding the reaction clears only the current red marker.
                    // It must never erase the historical removal entry.
                    if (clearActiveForKey(key, payload.userId)) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    if (clearActiveMessage(payload.messageId)) syncNative()
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

                    for (const key of activeRemovals.keys()) {
                        if (key.startsWith(prefix)) {
                            activeRemovals.delete(key)
                            changed = true
                        }
                    }

                    if (changed) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE',
                payload => {
                    if (clearActiveMessage(payload.id ?? payload.messageId)) syncNative()
                    // Historical entries deliberately survive message deletion.
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE_BULK',
                payload => {
                    let changed = false
                    for (const messageId of payload.ids ?? []) {
                        changed = clearActiveMessage(messageId) || changed
                    }
                    if (changed) syncNative()
                    return payload
                },
            ),

            () => {
                activeRemovals.clear()
                recentRemovalEchoes.clear()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear reaction highlights: ' + String(error))
                }
            },
        )
    },

    SettingsComponent,
})

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + '|' + emojiKey + '|' + reactionType
}
