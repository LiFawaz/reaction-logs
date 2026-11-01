export interface RemovalLogEntry {
    id: string
    messageId: string
    channelId: string
    emojiName: string
    emojiId: string | null
    reactionType: number
    userId: string
    userName: string
    guildId?: string | null
}

export interface ActiveRemovalEntry {
    pillKey: string
    removedUsers: string[]
}

export interface HistoryStorage {
    entries: RemovalLogEntry[]
    nextId: number
    activeRemovals: ActiveRemovalEntry[]
}

export const DefaultStorage: HistoryStorage = {
    entries: [],
    nextId: 1,
    activeRemovals: [],
}
