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
        for (const item of jsonStorage.cache?.activeRemovals ?? []) {
            if (!item?.pillKey) continue
            activeRemovals.set(item.pillKey, new Set(item.removedUsers ?? []))
        }

        let persistQueue = Promise.resolve()
        const pendingRemovalEchoes = new Set<string>()

        const persistState = () => {
            const snapshot: ReactionStorage = {
                activeRemovals: [...activeRemovals].map(([pillKey, users]) => ({
                    pillKey,
                    removedUsers: [...users],
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

            // Ignore Discord's duplicate optimistic/gateway event pair without a timer.
            if (userId !== '*' && pendingRemovalEchoes.delete(token)) return false
            if (userId !== '*') pendingRemovalEchoes.add(token)

            const users = activeRemovals.get(key) ?? new Set<string>()
            users.add(userId)
            activeRemovals.set(key, users)

            persistState()
            syncNative()
            return true
        }

        // Any new use of this exact emoji resets its red marker completely.
        const clearActiveForKey = (key: string) => activeRemovals.delete(key)

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
                    // Keep Discord from removing the local pill; only recolor it red.
                    return undefined
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const userId = payload.userId || '*'
                    pendingRemovalEchoes.delete(key + '\u0000' + userId)

                    if (clearActiveForKey(key)) {
                        persistState()
                        syncNative()
                        // The retained local pill already represents this reaction.
                        return undefined
                    }
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
                pendingRemovalEchoes.clear()
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
