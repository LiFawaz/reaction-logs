export interface EmojiPayload {
    id?: string | null
    name?: string | null
}

export interface ReactionPayload {
    channelId?: string
    channel_id?: string
    messageId?: string
    message_id?: string
    id?: string
    ids?: string[]
    userId?: string
    user_id?: string
    emoji?: EmojiPayload
    reactionType?: number
    reaction_type?: number
    type?: number | string
    burst?: boolean
    guildId?: string
    guild_id?: string
    optimistic?: boolean
}

export interface ActiveReaction {
    guildId: string | null
    channelId: string
    messageId: string
    userId: string
    emojiId: string | null
    emojiName: string | null
    emojiKey: string
    reactionType: number
}

export interface RemovalRecord {
    id: string
    identityKey: string
    guildId: string | null
    channelId: string
    messageId: string
    userId: string
    emojiId: string | null
    emojiName: string | null
    emojiKey: string
    reactionType: number
    timestamp: number | null
    epoch: number
}

export interface ReactionStorage {
    schemaVersion: 2
    removalHistory: RemovalRecord[]
    activeReactions: ActiveReaction[]
    epochs: Record<string, number>
    // Read only for migration from versions <= 1.2.9.
    activeRemovals?: { pillKey: string; removedUsers: string[] }[]
}

export const DefaultStorage: ReactionStorage = {
    schemaVersion: 2,
    removalHistory: [],
    activeReactions: [],
    epochs: {},
}
