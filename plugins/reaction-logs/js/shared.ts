export interface RemovedUserEntry {
    id: string
    removedAt: number
}

export interface RemovalLogEntry {
    pillKey: string
    removedAt: number
    removedUsers: RemovedUserEntry[]
}

export interface ReactionStorage {
    removalLogs?: RemovalLogEntry[]
    retainedPillKeys?: string[]
    // Read-only migration field for 1.3.0 and 1.3.1 caches.
    activeRemovals?: Array<{
        pillKey: string
        removedUsers: string[]
        removedAt?: number
    }>
}

export const DefaultStorage: ReactionStorage = {
    removalLogs: [],
    retainedPillKeys: [],
}
