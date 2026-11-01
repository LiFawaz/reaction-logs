export interface ActiveRemovalEntry {
    pillKey: string
    removedUsers: string[]
}

export interface ReactionStorage {
    activeRemovals: ActiveRemovalEntry[]
}

export const DefaultStorage: ReactionStorage = {
    activeRemovals: [],
}
