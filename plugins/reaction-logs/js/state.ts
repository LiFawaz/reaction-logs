import type {
    ActiveReaction,
    RemovalRecord,
    ReactionPayload,
    ReactionStorage,
} from './shared'

export const REMOVAL_RETENTION_MS = 30 * 24 * 60 * 60 * 1000
export const MAX_REMOVAL_RECORDS = 1000
export const MAX_ACTIVE_REACTIONS = 5000

type ReactionIdentity = Omit<ActiveReaction, 'reactionType'> & {
    reactionType: number
    pillKey: string
    baseKey: string
    identityKey: string
}

const nonEmpty = (value: unknown): string | null => {
    if (typeof value !== 'string') return null
    const result = value.trim()
    return result.length > 0 && result !== 'null' ? result : null
}

const asNumber = (value: unknown, fallback = 0): number =>
    typeof value === 'number' && Number.isFinite(value) ? value : fallback

export function emojiIdentity(emoji: ReactionPayload['emoji']): string | null {
    if (!emoji) return null
    const id = nonEmpty(emoji.id)
    if (id) return `custom:${id}`

    const name = nonEmpty(emoji.name)
    if (!name) return null
    return `unicode:${name.normalize('NFC')}`
}

function getString(
    payload: ReactionPayload,
    camel: keyof ReactionPayload,
    snake: keyof ReactionPayload,
): string | null {
    return nonEmpty(payload[camel]) ?? nonEmpty(payload[snake])
}

function reactionType(payload: ReactionPayload): number {
    const value = payload.reactionType ?? payload.reaction_type
    if (typeof value === 'number' && Number.isFinite(value)) return value
    if (typeof payload.type === 'number' && Number.isFinite(payload.type)) return payload.type
    return payload.burst === true ? 1 : 0
}

function identityFromPayload(payload: ReactionPayload): ReactionIdentity | null {
    const channelId = getString(payload, 'channelId', 'channel_id')
    const messageId = getString(payload, 'messageId', 'message_id')
    const userId = getString(payload, 'userId', 'user_id')
    const emojiKey = emojiIdentity(payload.emoji)
    if (!channelId || !messageId || !userId || !emojiKey) return null

    const guildId =
        nonEmpty(payload.guildId) ??
        nonEmpty(payload.guild_id)

    const emojiId = nonEmpty(payload.emoji?.id)
    const emojiName = nonEmpty(payload.emoji?.name)
    const type = reactionType(payload)
    // Channel/message IDs are globally unique; guildId is optional in some
    // Flux payloads, so it is retained as metadata but excluded from the key.
    // This lets ADD and REMOVE events match even if only one carries guildId.
    const baseKey = JSON.stringify([
        channelId,
        messageId,
        emojiKey,
        type,
        userId,
    ])

    return {
        guildId,
        channelId,
        messageId,
        userId,
        emojiId,
        emojiName,
        emojiKey,
        reactionType: type,
        pillKey: `${messageId}|${emojiKey}|${type}`,
        baseKey,
        identityKey: baseKey,
    }
}

const asActiveReaction = (identity: ReactionIdentity): ActiveReaction => ({
    guildId: identity.guildId,
    channelId: identity.channelId,
    messageId: identity.messageId,
    userId: identity.userId,
    emojiId: identity.emojiId,
    emojiName: identity.emojiName,
    emojiKey: identity.emojiKey,
    reactionType: identity.reactionType,
})

export function activeReactionKey(value: ActiveReaction): string {
    return JSON.stringify([
        value.channelId,
        value.messageId,
        value.emojiKey,
        value.reactionType,
        value.userId,
    ])
}

function removalId(identityKey: string, epoch: number): string {
    return JSON.stringify([identityKey, epoch])
}

function legacyEmojiIdentity(value: string): string | null {
    if (value.startsWith('custom:') || value.startsWith('unicode:')) return value

    // Previous releases stored custom emoji as "name:id" and Unicode by name.
    const idMatch = /:(\d+)$/.exec(value)
    if (idMatch) return `custom:${idMatch[1]}`
    if (/^\d+$/.test(value)) return `custom:${value}`
    return value ? `unicode:${value.normalize('NFC')}` : null
}

function migrateLegacyEntries(
    entries: unknown,
    into: Map<string, RemovalRecord>,
    migratedAt: number,
): void {
    if (!Array.isArray(entries)) return

    for (const value of entries) {
        if (!value || typeof value !== 'object') continue
        const entry = value as { pillKey?: unknown; removedUsers?: unknown }
        const pillKey = nonEmpty(entry.pillKey)
        if (!pillKey || !Array.isArray(entry.removedUsers)) continue

        const [messageId, ...rest] = pillKey.split('|')
        if (!messageId || rest.length < 2) continue
        const type = Number(rest.pop())
        const emojiKey = legacyEmojiIdentity(rest.join('|'))
        if (!emojiKey || !Number.isFinite(type)) continue

        for (const rawUserId of entry.removedUsers) {
            const userId = nonEmpty(rawUserId)
            if (!userId || userId === '*') continue
            const identityKey = JSON.stringify(['', '', messageId, emojiKey, type, userId])
            const id = `legacy:${identityKey}`
            if (into.has(id)) continue
            into.set(id, {
                id,
                guildId: null,
                channelId: '',
                messageId,
                userId,
                emojiId: emojiKey.startsWith('custom:') ? emojiKey.slice(7) : null,
                emojiName: emojiKey.startsWith('unicode:') ? emojiKey.slice(8) : null,
                emojiKey,
                reactionType: type,
                timestamp: migratedAt,
                epoch: 0,
                identityKey,
            })
        }
    }
}

export function normalizeReactionStorage(
    input: Partial<ReactionStorage> | null | undefined,
    now = Date.now(),
): ReactionStorage {
    const history = new Map<string, RemovalRecord>()
    if (Array.isArray(input?.removalHistory)) {
        for (const raw of input.removalHistory) {
            if (!raw || typeof raw !== 'object') continue
            const item = raw as RemovalRecord
            const required = [
                item.id,
                item.messageId,
                item.userId,
                item.emojiKey,
                item.identityKey,
            ]
            if (required.some(value => typeof value !== 'string' || value.length === 0)) continue
            if (typeof item.channelId !== 'string') continue
            if (item.channelId.length === 0 && !item.id.startsWith('legacy:')) continue
            if (item.timestamp != null && (!Number.isFinite(item.timestamp) || item.timestamp < 0)) continue
            history.set(item.id, {
                ...item,
                reactionType: asNumber(item.reactionType),
                epoch: Math.max(0, asNumber(item.epoch)),
                timestamp: item.timestamp == null ? null : asNumber(item.timestamp),
            })
        }
    }

    migrateLegacyEntries(input?.activeRemovals, history, now)

    const cutoff = now - REMOVAL_RETENTION_MS
    const retained = [...history.values()]
        .filter(item => item.timestamp == null || item.timestamp >= cutoff)
        .sort((a, b) => (a.timestamp ?? 0) - (b.timestamp ?? 0))
        .slice(-MAX_REMOVAL_RECORDS)

    const active = new Map<string, ActiveReaction>()
    if (Array.isArray(input?.activeReactions)) {
        for (const item of input.activeReactions) {
            if (!item || typeof item !== 'object') continue
            const value = item as ActiveReaction
            if (
                !nonEmpty(value.channelId) ||
                !nonEmpty(value.messageId) ||
                !nonEmpty(value.userId) ||
                !nonEmpty(value.emojiKey)
            ) continue
            const normalized: ActiveReaction = {
                guildId: nonEmpty(value.guildId),
                channelId: value.channelId,
                messageId: value.messageId,
                userId: value.userId,
                emojiId: nonEmpty(value.emojiId),
                emojiName: nonEmpty(value.emojiName),
                emojiKey: value.emojiKey,
                reactionType: asNumber(value.reactionType),
            }
            active.set(activeReactionKey(normalized), normalized)
        }
    }

    const epochs: Record<string, number> = {}
    if (input?.epochs && typeof input.epochs === 'object') {
        for (const [key, value] of Object.entries(input.epochs)) {
            if (typeof value === 'number' && Number.isFinite(value) && value >= 0)
                epochs[key] = Math.floor(value)
        }
    }
    for (const item of retained) {
        if (item.id.startsWith('legacy:')) continue
        epochs[item.identityKey] = Math.max(epochs[item.identityKey] ?? 0, item.epoch)
    }

    // Bound both the active cache and its epoch map to the same retained identities.
    const boundedActive = [...active.values()].slice(-MAX_ACTIVE_REACTIONS)
    const liveIdentityKeys = new Set([
        ...boundedActive.map(activeReactionKey),
        ...retained.map(item => item.identityKey),
    ])
    for (const key of Object.keys(epochs)) {
        if (!liveIdentityKeys.has(key)) delete epochs[key]
    }

    return {
        schemaVersion: 2,
        removalHistory: retained,
        activeReactions: boundedActive,
        epochs,
    }
}

export function applyReactionAdd(
    input: ReactionStorage,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionStorage {
    const state = normalizeReactionStorage(input, now)
    const identity = identityFromPayload(payload)
    if (!identity) return state

    const active = new Map(state.activeReactions.map(item => [activeReactionKey(item), item]))
    if (!active.has(identity.identityKey)) {
        active.set(identity.identityKey, asActiveReaction(identity))
        state.epochs[identity.identityKey] = (state.epochs[identity.identityKey] ?? 0) + 1
    }

    state.activeReactions = [...active.values()]
    return normalizeReactionStorage(state, now)
}

export function applyReactionRemove(
    input: ReactionStorage,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionStorage {
    const state = normalizeReactionStorage(input, now)
    const identity = identityFromPayload(payload)
    if (!identity) return state

    const epoch = state.epochs[identity.identityKey] ?? 0
    const id = removalId(identity.identityKey, epoch)
    const existing = state.removalHistory.some(item => item.id === id)
    const active = new Map(state.activeReactions.map(item => [activeReactionKey(item), item]))
    active.delete(identity.identityKey)
    state.activeReactions = [...active.values()]

    if (!existing) {
        const record: RemovalRecord = {
            id,
            identityKey: identity.identityKey,
            guildId: identity.guildId,
            channelId: identity.channelId,
            messageId: identity.messageId,
            userId: identity.userId,
            emojiId: identity.emojiId,
            emojiName: identity.emojiName,
            emojiKey: identity.emojiKey,
            reactionType: identity.reactionType,
            timestamp: now,
            epoch,
        }
        state.removalHistory.push(record)
    }

    return normalizeReactionStorage(state, now)
}

export function applyRemoveAll(
    input: ReactionStorage,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionStorage {
    const state = normalizeReactionStorage(input, now)
    const channelId = getString(payload, 'channelId', 'channel_id')
    const messageId = getString(payload, 'messageId', 'message_id')
    if (!channelId || !messageId) return state

    state.activeReactions = state.activeReactions.filter(
        item => item.channelId !== channelId || item.messageId !== messageId,
    )
    // This event has no user ID. Preserve history without inventing individual actors.
    return normalizeReactionStorage(state, now)
}

export function applyRemoveEmoji(
    input: ReactionStorage,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionStorage {
    const state = normalizeReactionStorage(input, now)
    const channelId = getString(payload, 'channelId', 'channel_id')
    const messageId = getString(payload, 'messageId', 'message_id')
    const emojiKey = emojiIdentity(payload.emoji)
    if (!channelId || !messageId || !emojiKey) return state

    state.activeReactions = state.activeReactions.filter(
        item =>
            item.channelId !== channelId ||
            item.messageId !== messageId ||
            item.emojiKey !== emojiKey,
    )
    // Bulk emoji removal does not identify individual users.
    return normalizeReactionStorage(state, now)
}

export function applyMessageDelete(
    input: ReactionStorage,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionStorage {
    const state = normalizeReactionStorage(input, now)
    const messageId =
        nonEmpty(payload.id) ??
        getString(payload, 'messageId', 'message_id')
    const channelId = getString(payload, 'channelId', 'channel_id')
    if (!messageId) return state

    state.removalHistory = state.removalHistory.filter(
        item => item.messageId !== messageId || (channelId != null && item.channelId.length > 0 && item.channelId !== channelId),
    )
    state.activeReactions = state.activeReactions.filter(
        item => item.messageId !== messageId || (channelId != null && item.channelId !== channelId),
    )
    const retainedIdentityKeys = new Set([
        ...state.removalHistory.map(item => item.identityKey),
        ...state.activeReactions.map(activeReactionKey),
    ])
    for (const key of Object.keys(state.epochs)) {
        if (!retainedIdentityKeys.has(key)) delete state.epochs[key]
    }
    return normalizeReactionStorage(state, now)
}

export function applyBulkMessageDelete(
    input: ReactionStorage,
    payload: ReactionPayload,
    now = Date.now(),
): ReactionStorage {
    let state = normalizeReactionStorage(input, now)
    const ids = Array.isArray(payload.ids)
        ? payload.ids.map(nonEmpty).filter((id): id is string => id != null)
        : []
    for (const id of ids) {
        state = applyMessageDelete(state, { id, channelId: payload.channelId, channel_id: payload.channel_id }, now)
    }
    return normalizeReactionStorage(state, now)
}

export function buildNativeSnapshot(state: ReactionStorage, now = Date.now()) {
    const normalized = normalizeReactionStorage(state, now)
    const activeKeys = new Set(normalized.activeReactions.map(activeReactionKey))
    const grouped = new Map<string, Set<string>>()

    for (const item of normalized.removalHistory) {
        const identityKey = item.identityKey
        // For old entries without channel IDs, only the pill marker can be scoped.
        const active = normalized.activeReactions.some(active =>
            (item.channelId.length === 0 || active.channelId === item.channelId) &&
            active.messageId === item.messageId &&
            active.userId === item.userId &&
            active.emojiKey === item.emojiKey &&
            active.reactionType === item.reactionType &&
            (!item.guildId || !active.guildId || active.guildId === item.guildId),
        )
        if (active || activeKeys.has(identityKey)) continue

        const pillKey = `${item.messageId}|${item.emojiKey}|${item.reactionType}`
        const users = grouped.get(pillKey) ?? new Set<string>()
        users.add(item.userId)
        grouped.set(pillKey, users)
    }

    return {
        schemaVersion: 2,
        activeRemovals: [...grouped].map(([pillKey, users]) => ({
            pillKey,
            removedUsers: [...users].map(id => ({ id })),
        })),
        removalHistory: normalized.removalHistory,
        activeReactions: normalized.activeReactions,
    }
}
