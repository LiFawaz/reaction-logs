export interface RemovedUserEntry {
    id: string
}

export interface RemovalLogEntry {
    pillKey: string
    removedUsers: RemovedUserEntry[]
}

export interface ReactionStorage {
    removalLogs?: RemovalLogEntry[]
    retainedPillKeys?: string[]
}

export const DefaultStorage: ReactionStorage = {
    removalLogs: [],
    retainedPillKeys: [],
}
