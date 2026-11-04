import { onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import { DefaultStorage } from './shared'
import type {
    BulkRemovalRecord,
    ReactionStateEntry,
    ReactionStorage,
    RemovalRecord,
} from './shared'

interface Emoji {
    id?: string | number | null
    name?: string | null
    animated?: boolean
}

interface ReactionPayload {
    channelId?: string
    channel_id?: string
    messageId?: string
    message_id?: string
    id?: string
    ids?: string[]
    userId?: string
    user_id?: string
    emoji?: Emoji
    reactionType?: number
    reaction_type?: number
    type?: number
    burst?: boolean
    guildId?: string
    guild_id?: string
    optimistic?: boolean
    timestamp?: number | string
    user?: {
        username?: string
        globalName?: string
        global_name?: string
        avatar?: string | null
        displayName?: string
        display_name?: string
    }
    member?: {
        user?: ReactionPayload['user']
        nick?: string
    }
}

const RETENTION_MS = 30 * 24 * 60 * 60 * 1000
const MAX_REMOVALS = 5000
const MAX_BULK_EVENTS = 1000

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    start({ cleanup, plugin, jsonStorage }) {
        const removals = [...(jsonStorage.cache?.removals ?? [])]
        const states = new Map<string, ReactionStateEntry>(
            (jsonStorage.cache?.reactionStates ?? []).map(entry => [
                stateKey(entry.reactionKey, entry.userId),
                entry,
            ]),
        )
        const bulkEvents = [...(jsonStorage.cache?.bulkEvents ?? [])]
        let persistQueue: Promise<void> = Promise.resolve()
        let eventSequence = 0

        // Migrate old red-pill entries while preserving their known message/emoji/user identity.
        for (const legacy of jsonStorage.cache?.activeRemovals ?? []) {
            const parts = legacy.pillKey.split('|')
            const messageId = parts[0]
            const oldEmojiKey = parts[1]
            const reactionType = Number(parts[2] ?? 0)
            if (!messageId || !oldEmojiKey) continue
            const emojiKey = oldEmojiKey.includes(':')
                ? 'id:' + oldEmojiKey.slice(oldEmojiKey.lastIndexOf(':') + 1)
                : 'unicode:' + oldEmojiKey

            for (const userId of legacy.removedUsers ?? []) {
                if (!userId || userId === '*') continue
                const reactionKey = makeReactionKey(messageId, emojiKey, reactionType)
                const key = stateKey(reactionKey, userId)
                if (states.has(key)) continue
                const timestamp = Date.now()
                const record: RemovalRecord = {
                    eventId: 'legacy:' + key,
                    messageId,
                    userId,
                    emojiKey,
                    reactionType,
                    removedAt: timestamp,
                    source: 'legacy',
                }
                removals.push(record)
                states.set(key, {
                    reactionKey,
                    messageId,
                    emojiKey,
                    reactionType,
                    userId,
                    state: 'removed',
                    updatedAt: timestamp,
                    latestRemovalId: record.eventId,
                })
            }
        }

        const persistState = () => {
            const cutoff = Date.now() - RETENTION_MS
            const retainedRemovals = removals
                .filter(record => record.removedAt >= cutoff)
                .slice(-MAX_REMOVALS)
            const retainedBulkEvents = bulkEvents
                .filter(record => record.timestamp >= cutoff)
                .slice(-MAX_BULK_EVENTS)
            removals.splice(0, removals.length, ...retainedRemovals)
            bulkEvents.splice(0, bulkEvents.length, ...retainedBulkEvents)
            for (const [key, state] of states) {
                if (state.updatedAt < cutoff) states.delete(key)
            }
            if (states.size > 20000) {
                const newest = [...states.entries()]
                    .sort((a, b) => a[1].updatedAt - b[1].updatedAt)
                    .slice(-20000)
                states.clear()
                for (const [key, state] of newest) states.set(key, state)
            }

            const snapshot: ReactionStorage = {
                removals: [...removals],
                reactionStates: [...states.values()],
                bulkEvents: [...bulkEvents],
            }
            persistQueue = persistQueue
                .then(() => jsonStorage.set(snapshot, true))
                .catch(error => {
                    plugin.reportError('Failed to save reaction removal history: ' + String(error))
                })
        }

        const syncNative = () => {
            try {
                const latestRemoved = removals.filter(record => {
                    const key = stateKey(
                        makeReactionKey(record.messageId, record.emojiKey, record.reactionType),
                        record.userId,
                    )
                    const state = states.get(key)
                    return state?.state === 'removed' && state.latestRemovalId === record.eventId
                })
                setRemoved(JSON.stringify(latestRemoved.map(record => ({
                    messageId: record.messageId,
                    channelId: record.channelId,
                    guildId: record.guildId,
                    emojiKey: record.emojiKey,
                    reactionType: record.reactionType,
                    userId: record.userId,
                    username: record.username,
                    displayName: record.displayName,
                    avatar: record.avatar,
                    timestamp: record.removedAt,
                }))))
            } catch (error) {
                plugin.reportError('Failed to refresh removed reaction history in native UI: ' + String(error))
            }
        }

        const makeIdentity = (payload: ReactionPayload) => {
            const messageId = asString(payload.messageId, payload.message_id)
            const emoji = payload.emoji
            if (!messageId || !emoji) return null

            const emojiId = asString(emoji.id)
            const emojiName = asString(emoji.name)
            if (!emojiId && !emojiName) return null
            const emojiKey = emojiId ? 'id:' + emojiId : 'unicode:' + emojiName
            const rawType = payload.reactionType ?? payload.reaction_type ?? payload.type
            const reactionType = Number(rawType) === 1 || payload.burst === true ? 1 : 0

            return {
                messageId,
                channelId: asString(payload.channelId, payload.channel_id) ?? undefined,
                guildId: asString(payload.guildId, payload.guild_id) ?? undefined,
                userId: asString(payload.userId, payload.user_id) ?? undefined,
                emojiKey,
                emojiName: emojiName ?? undefined,
                emojiId: emojiId ?? undefined,
                reactionType,
                reactionKey: makeReactionKey(messageId, emojiKey, reactionType),
            }
        }

        const addBulkEvent = (
            type: BulkRemovalRecord['type'],
            identity: ReturnType<typeof makeIdentity>,
            timestamp = Date.now(),
        ) => {
            bulkEvents.push({
                eventId: type + ':' + timestamp + ':' + (++eventSequence),
                type,
                messageId: identity?.messageId,
                channelId: identity?.channelId,
                guildId: identity?.guildId,
                emojiKey: identity?.emojiKey,
                timestamp,
            })
        }

        const recordIndividualRemoval = (
            identity: NonNullable<ReturnType<typeof makeIdentity>>,
            userId: string,
            source: RemovalRecord['source'] = 'gateway',
            metadata?: Pick<RemovalRecord, 'username' | 'displayName' | 'avatar'>,
            timestamp = Date.now(),
        ) => {
            const key = stateKey(identity.reactionKey, userId)
            const existing = states.get(key)
            // Suppress the optimistic/gateway echo pair inside a short five-second window,
            // but allow a later REMOVE to form a new history entry if an ADD was missed while
            // the plugin was unloaded. A received matching ADD resets the state immediately.
            if (existing?.state === 'removed' && timestamp - existing.updatedAt < 5000) {
                return false
            }

            const eventId = 'remove:' + key + ':' + timestamp + ':' + (++eventSequence)
            const record: RemovalRecord = {
                eventId,
                messageId: identity.messageId,
                channelId: identity.channelId,
                guildId: identity.guildId,
                userId,
                emojiKey: identity.emojiKey,
                emojiName: identity.emojiName,
                emojiId: identity.emojiId,
                reactionType: identity.reactionType,
                removedAt: timestamp,
                source,
                ...metadata,
            }
            removals.push(record)
            states.set(key, {
                reactionKey: identity.reactionKey,
                messageId: identity.messageId,
                channelId: identity.channelId,
                guildId: identity.guildId,
                emojiKey: identity.emojiKey,
                reactionType: identity.reactionType,
                userId,
                state: 'removed',
                updatedAt: timestamp,
                latestRemovalId: eventId,
            })
            return true
        }

        const handleAdd = (payload: ReactionPayload) => {
            const identity = makeIdentity(payload)
            const userId = identity?.userId
            if (!identity || !userId) return
            const key = stateKey(identity.reactionKey, userId)
            const existing = states.get(key)
            states.set(key, {
                reactionKey: identity.reactionKey,
                messageId: identity.messageId,
                channelId: identity.channelId,
                guildId: identity.guildId,
                emojiKey: identity.emojiKey,
                reactionType: identity.reactionType,
                userId,
                state: 'active',
                updatedAt: Date.now(),
                latestRemovalId: existing?.latestRemovalId,
            })
            persistState()
            syncNative()
        }

        const handleRemove = (payload: ReactionPayload) => {
            const identity = makeIdentity(payload)
            const userId = identity?.userId
            if (!identity || !userId) {
                addBulkEvent('MESSAGE_REACTION_REMOVE', identity)
                persistState()
                plugin.reportError('Reaction removal received without a reliable message, emoji, or affected user ID')
                return
            }

            const sourceUser = payload.user ?? payload.member?.user
            const displayName = asString(
                sourceUser?.displayName,
                sourceUser?.display_name,
                sourceUser?.globalName,
                sourceUser?.global_name,
                payload.member?.nick,
            )
            const metadata = {
                username: asString(sourceUser?.username) ?? undefined,
                displayName: displayName ?? undefined,
                avatar: asString(sourceUser?.avatar) ?? undefined,
            }
            if (recordIndividualRemoval(identity, userId, 'gateway', metadata)) {
                persistState()
                syncNative()
            }
        }

        const handleBulkRemoval = (
            type: 'MESSAGE_REACTION_REMOVE_ALL' | 'MESSAGE_REACTION_REMOVE_EMOJI',
            payload: ReactionPayload,
        ) => {
            const identity = makeIdentity(payload)
            const messageId = identity?.messageId ?? asString(payload.messageId, payload.message_id)
            if (!messageId) {
                addBulkEvent(type, identity)
                persistState()
                return
            }

            const timestamp = Date.now()
            // A bulk event does not identify individual reactors. Mark the plugin's
            // observed active state as cleared, but do not invent per-user removal records.
            for (const [key, state] of states) {
                if (state.messageId !== messageId || state.state !== 'active') continue
                if (type === 'MESSAGE_REACTION_REMOVE_EMOJI' &&
                    (!identity?.emojiKey || state.emojiKey !== identity.emojiKey)) continue

                states.set(key, {
                    ...state,
                    state: 'removed',
                    updatedAt: timestamp,
                    latestRemovalId: undefined,
                })
            }

            addBulkEvent(type, identity, timestamp)
            persistState()
            syncNative()
        }

        const clearDeletedMessages = (messageIds: string[]) => {
            const deleted = new Set(messageIds.filter(Boolean))
            if (!deleted.size) return
            const oldRemovalCount = removals.length
            const oldBulkCount = bulkEvents.length
            removals.splice(0, removals.length, ...removals.filter(item => !deleted.has(item.messageId)))
            bulkEvents.splice(0, bulkEvents.length, ...bulkEvents.filter(
                item => !item.messageId || !deleted.has(item.messageId),
            ))
            let stateChanged = false
            for (const [key, state] of states) {
                if (deleted.has(state.messageId)) {
                    states.delete(key)
                    stateChanged = true
                }
            }
            if (oldRemovalCount !== removals.length || oldBulkCount !== bulkEvents.length || stateChanged) {
                persistState()
                syncNative()
            }
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE', payload => {
                try {
                    handleRemove(payload)
                } catch (error) {
                    plugin.reportError('Failed to record MESSAGE_REACTION_REMOVE: ' + String(error))
                }
                // Pass the event through so Discord's stores update active reactors and counts.
                return payload
            }),

            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_ADD', payload => {
                try {
                    handleAdd(payload)
                } catch (error) {
                    plugin.reportError('Failed to process MESSAGE_REACTION_ADD: ' + String(error))
                }
                return payload
            }),

            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE_ALL', payload => {
                try {
                    handleBulkRemoval('MESSAGE_REACTION_REMOVE_ALL', payload)
                } catch (error) {
                    plugin.reportError('Failed to process MESSAGE_REACTION_REMOVE_ALL: ' + String(error))
                }
                return payload
            }),

            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE_EMOJI', payload => {
                try {
                    handleBulkRemoval('MESSAGE_REACTION_REMOVE_EMOJI', payload)
                } catch (error) {
                    plugin.reportError('Failed to process MESSAGE_REACTION_REMOVE_EMOJI: ' + String(error))
                }
                return payload
            }),

            onFluxEventDispatched<ReactionPayload>('MESSAGE_DELETE', payload => {
                const messageId = asString(payload.id, payload.messageId, payload.message_id)
                if (messageId) clearDeletedMessages([messageId])
                return payload
            }),

            onFluxEventDispatched<ReactionPayload>('MESSAGE_DELETE_BULK', payload => {
                clearDeletedMessages(payload.ids ?? [])
                return payload
            }),

            () => {
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear in-memory reaction highlights: ' + String(error))
                }
            },
        )

        syncNative()
        persistState()
    },
})

function asString(...values: unknown[]): string | null {
    for (const value of values) {
        if (typeof value === 'string' && value.length > 0) return value
        if (typeof value === 'number' && Number.isFinite(value)) return String(value)
    }
    return null
}

function makeReactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + '|' + emojiKey + '|' + reactionType
}

function stateKey(reactionKey: string, userId: string) {
    return reactionKey + '\u0000' + userId
}
