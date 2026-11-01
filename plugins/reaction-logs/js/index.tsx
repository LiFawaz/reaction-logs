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
    guildId?: string
    guild_id?: string
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

interface ChannelStoreShape {
    getChannel(id: string): { guild_id?: string | null; guildId?: string | null } | undefined
}

const MAX_HISTORY_ENTRIES = 500

export default plugin<{ jsonStorage: HistoryStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    start({ cleanup, plugin, jsonStorage }) {
        // The live red state is stored locally and remains separate from history.
        const activeRemovals = new Map<string, Set<string>>()
        for (const item of jsonStorage.cache?.activeRemovals ?? []) {
            if (!item?.pillKey) continue
            activeRemovals.set(item.pillKey, new Set(item.removedUsers ?? []))
        }

        // Remove the old timestamp field when migrating existing local history.
        let history = (jsonStorage.cache?.entries ?? []).map(entry => {
            const cleanEntry = { ...entry } as RemovalLogEntry & { removedAt?: number }
            delete cleanEntry.removedAt
            return cleanEntry
        })
        let nextId = jsonStorage.cache?.nextId ?? 1
        let persistQueue = Promise.resolve()
        const pendingRemovalEchoes = new Set<string>()

        const userStore = Stores.UserStore as unknown as UserStoreShape
        const channelStore = Stores.ChannelStore as unknown as ChannelStoreShape

        const persistHistory = () => {
            const snapshot: HistoryStorage = {
                entries: [...history],
                nextId,
                activeRemovals: [...activeRemovals].map(([pillKey, users]) => ({
                    pillKey,
                    removedUsers: [...users],
                })),
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

        const getGuildId = (channelId: string): string | null => {
            try {
                const channel = channelStore.getChannel(channelId)
                return channel?.guild_id ?? channel?.guildId ?? null
            } catch {
                return null
            }
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

            // Ignore Discord's duplicate optimistic/gateway event pair without a timer.
            if (userId !== '*' && pendingRemovalEchoes.delete(token)) return false
            if (userId !== '*') pendingRemovalEchoes.add(token)

            const users = activeRemovals.get(key) ?? new Set<string>()
            users.add(userId)
            activeRemovals.set(key, users)

            const emoji = payload.emoji
            const entry: RemovalLogEntry = {
                id: String(nextId++),
                messageId: payload.messageId ?? '',
                channelId: payload.channelId ?? '',
                guildId: payload.guildId ?? payload.guild_id ?? (payload.channelId ? getGuildId(payload.channelId) : null),
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
                    // Match the original working implementation: prevent Discord
                    // from removing the local reaction pill. We only recolor it.
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
                        persistHistory()
                        syncNative()
                        // The retained local pill already represents this reaction.
                        // Swallow the add event so Discord does not change its count.
                        return undefined
                    }
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    if (clearActiveMessage(payload.messageId)) {
                        persistHistory()
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
                        persistHistory()
                        syncNative()
                    }
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE',
                payload => {
                    if (clearActiveMessage(payload.id ?? payload.messageId)) {
                        persistHistory()
                        syncNative()
                    }
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
                    if (changed) {
                        persistHistory()
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
        persistHistory()
    },

    SettingsComponent,
})

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + '|' + emojiKey + '|' + reactionType
}
