export interface ActiveRemovalEntry {
    pillKey: string
    removedUsers: string[]
    removedAt?: number
}

export interface ReactionStorage {
    activeRemovals: ActiveRemovalEntry[]
}

export const DefaultStorage: ReactionStorage = {
    activeRemovals: [],
}
