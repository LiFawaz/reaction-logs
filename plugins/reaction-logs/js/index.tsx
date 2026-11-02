import { Stores, onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import { DefaultStorage } from './shared'
import type { ReactionStorage } from './shared'

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
    guildId?: string
    guild_id?: string
    optimistic?: boolean
}

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    start({ cleanup, plugin, jsonStorage }) {
        // Only the active local red-marker state is retained.
        const activeRemovals = new Map<string, Set<string>>()
        const removedAtByKey = new Map<string, number>()
        for (const item of jsonStorage.cache?.activeRemovals ?? []) {
            if (!item?.pillKey) continue
            activeRemovals.set(item.pillKey, new Set(item.removedUsers ?? []))
            const removedAt = Number(item.removedAt)
            removedAtByKey.set(item.pillKey, Number.isFinite(removedAt) && removedAt > 0 ? removedAt : Date.now())
        }

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

        const persistState = () => {
            const snapshot: ReactionStorage = {
                activeRemovals: [...activeRemovals].map(([pillKey, users]) => ({
                    pillKey,
                    removedUsers: [...users],
                    removedAt: removedAtByKey.get(pillKey) ?? Date.now(),
                })),
            }
            persistQueue = persistQueue
                .then(() => jsonStorage.set(snapshot, true))
                .catch(error => {
                    plugin.reportError('Failed to save removed reaction state: ' + String(error))
                })
        }

        const syncNative = () => {
            try {
                const entries = [...activeRemovals].map(([pillKey, users]) => ({
                    pillKey,
                    removedAt: removedAtByKey.get(pillKey) ?? Date.now(),
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

        const recordRemoval = (payload: ReactionPayload, key: string) => {
            const userId = payload.userId || '*'
            const token = key + '\u0000' + userId

            // Ignore a duplicate gateway echo of the same removal.
            if (userId !== '*' && pendingRemovalEchoes.delete(token)) return false
            if (userId !== '*') pendingRemovalEchoes.add(token)

            // A new removal supersedes a pending add echo for the same account.
            if (userId !== '*') pendingAddEchoes.delete(token)

            const users = activeRemovals.get(key) ?? new Set<string>()
            users.add(userId)
            activeRemovals.set(key, users)
            removedAtByKey.set(key, Date.now())

            persistState()
            syncNative()
            return true
        }

        // Clear only the account that added this emoji again. Other removed
        // reactors on the same emoji must remain highlighted.
        const clearActiveForUser = (key: string, userId: string) => {
            const users = activeRemovals.get(key)
            if (!users || !users.delete(userId)) return false

            if (users.size === 0) {
                activeRemovals.delete(key)
                removedAtByKey.delete(key)
            }
            return true
        }

        const clearActiveMessage = (messageId?: string) => {
            if (!messageId) return false
            const prefix = messageId + '|'
            let changed = false

            for (const key of activeRemovals.keys()) {
                if (key.startsWith(prefix)) {
                    activeRemovals.delete(key)
                    removedAtByKey.delete(key)
                    changed = true
                }
            }
            for (const token of pendingRemovalEchoes) {
                if (token.startsWith(prefix)) pendingRemovalEchoes.delete(token)
            }
            for (const token of pendingAddEchoes) {
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

                    const recorded = recordRemoval(payload, key)
                    if (!recorded) return undefined

                    // Let Discord update our own "me" state. Otherwise tapping a
                    // red pill keeps acting like a removal and cannot add it back.
                    const currentUserId = getCurrentUserId()
                    if (payload.userId && currentUserId && payload.userId === currentUserId) {
                        return payload
                    }

                    // For other users, keep the local reactor row so it can be
                    // visibly marked red in Manage Reactions.
                    return undefined
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const userId = payload.userId || getCurrentUserId() || '*'
                    const token = key + '\u0000' + userId

                    if (userId !== '*') {
                        pendingRemovalEchoes.delete(token)

                        // Apply an optimistic add once and ignore only its later
                        // gateway echo, not the real local add action.
                        if (!payload.optimistic && pendingAddEchoes.delete(token)) {
                            return undefined
                        }
                        if (payload.optimistic) pendingAddEchoes.add(token)
                    }

                    if (clearActiveForUser(key, userId)) {
                        persistState()
                        syncNative()
                    }

                    // Always pass the add through so Discord updates the count and
                    // the current account's "me" state. The red marker is per-user.
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    if (clearActiveMessage(payload.messageId)) {
                        persistState()
                        syncNative()
                    }
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
                            removedAtByKey.delete(key)
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
                    if (clearActiveMessage(payload.id ?? payload.messageId)) {
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
                        changed = clearActiveMessage(messageId) || changed
                    }
                    if (changed) {
                        persistState()
                        syncNative()
                    }
                    return payload
                },
            ),

            () => {
                activeRemovals.clear()
                removedAtByKey.clear()
                pendingRemovalEchoes.clear()
                pendingAddEchoes.clear()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear reaction highlights: ' + String(error))
                }
            },
        )

        // Restore locally saved red markers immediately after the plugin starts.
        syncNative()
        persistState()
    },
})

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + '|' + emojiKey + '|' + reactionType
}
