export interface RemovalRecord {
    eventId: string
    messageId: string
    channelId?: string
    guildId?: string
    userId: string
    emojiKey: string
    emojiName?: string
    emojiId?: string
    reactionType: number
    removedAt: number
    username?: string
    displayName?: string
    avatar?: string
    source?: 'gateway' | 'remove_all' | 'remove_emoji' | 'legacy'
}

export interface ReactionStateEntry {
    reactionKey: string
    messageId: string
    channelId?: string
    guildId?: string
    emojiKey: string
    reactionType: number
    userId: string
    state: 'active' | 'removed'
    updatedAt: number
    latestRemovalId?: string
}

export interface BulkRemovalRecord {
    eventId: string
    type: 'MESSAGE_REACTION_REMOVE' | 'MESSAGE_REACTION_REMOVE_ALL' | 'MESSAGE_REACTION_REMOVE_EMOJI'
    messageId?: string
    channelId?: string
    guildId?: string
    emojiKey?: string
    timestamp: number
}

export interface ActiveRemovalEntry {
    pillKey: string
    removedUsers: string[]
}

/** The removal event history is independent from the latest active/removed state. */
export interface ReactionStorage {
    removals: RemovalRecord[]
    reactionStates: ReactionStateEntry[]
    bulkEvents: BulkRemovalRecord[]
    activeRemovals?: ActiveRemovalEntry[]
}

export const DefaultStorage: ReactionStorage = {
    removals: [],
    reactionStates: [],
    bulkEvents: [],
}
