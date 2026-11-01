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
    removedAt?: number
}

export interface HistoryStorage {
    entries: RemovalLogEntry[]
    nextId: number
}

export const DefaultStorage: HistoryStorage = {
    entries: [],
    nextId: 1,
}
