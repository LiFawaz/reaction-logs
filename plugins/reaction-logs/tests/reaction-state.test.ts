import { describe, expect, test } from 'bun:test'
import {
    applyBulkMessageDelete,
    applyMessageDelete,
    applyReactionAdd,
    applyReactionRemove,
    applyRemoveAll,
    applyRemoveEmoji,
    buildNativeSnapshot,
    normalizeReactionStorage,
    MAX_ACTIVE_REACTIONS,
} from '../js/state'
import { DefaultStorage } from '../js/shared'
import type { ReactionPayload, ReactionStorage } from '../js/shared'

const base = (overrides: Partial<ReactionPayload> = {}): ReactionPayload => ({
    channel_id: 'channel-1',
    message_id: 'message-1',
    guild_id: 'guild-1',
    user_id: 'user-1',
    emoji: { name: 'heart', id: null },
    reaction_type: 0,
    ...overrides,
})

const state = (): ReactionStorage => structuredClone(DefaultStorage)

describe('Reaction Logs event state', () => {
    test('one add then one remove records the affected user', () => {
        let current = applyReactionAdd(state(), base(), 100)
        expect(current.activeReactions).toHaveLength(1)
        current = applyReactionRemove(current, base(), 200)
        expect(current.activeReactions).toHaveLength(0)
        expect(current.removalHistory).toHaveLength(1)
        expect(current.removalHistory[0]).toMatchObject({
            channelId: 'channel-1',
            messageId: 'message-1',
            userId: 'user-1',
            emojiKey: 'unicode:heart',
            reactionType: 0,
            timestamp: 200,
        })
    })

    test('removing one user does not mark other users on the same emoji', () => {
        let current = applyReactionAdd(state(), base({ user_id: 'user-a' }), 1)
        current = applyReactionAdd(current, base({ user_id: 'user-b' }), 2)
        current = applyReactionRemove(current, base({ user_id: 'user-a' }), 3)
        expect(current.removalHistory.map(row => row.userId)).toEqual(['user-a'])
        expect(current.activeReactions.map(row => row.userId)).toEqual(['user-b'])
    })

    test('one user can react to multiple emojis; removing one leaves the others active', () => {
        let current = applyReactionAdd(state(), base({ emoji: { name: 'heart' } }), 1)
        current = applyReactionAdd(current, base({ emoji: { name: 'laugh' } }), 2)
        current = applyReactionAdd(current, base({ emoji: { name: 'star' } }), 3)
        current = applyReactionRemove(current, base({ emoji: { name: 'heart' } }), 4)
        expect(current.removalHistory.map(row => row.emojiKey)).toEqual(['unicode:heart'])
        expect(current.activeReactions.map(row => row.emojiKey).sort()).toEqual([
            'unicode:laugh',
            'unicode:star',
        ])
        expect(buildNativeSnapshot(current, 4).activeRemovals).toEqual([
            { pillKey: 'message-1|unicode:heart|0', removedUsers: [{ id: 'user-1' }] },
        ])
    })

    test('different channels and messages remain isolated', () => {
        let current = applyReactionAdd(state(), base({ user_id: 'user-a' }), 1)
        current = applyReactionAdd(current, base({ channel_id: 'channel-2', user_id: 'user-a' }), 2)
        current = applyReactionAdd(current, base({ message_id: 'message-2', user_id: 'user-a' }), 3)
        current = applyReactionRemove(current, base({ user_id: 'user-a' }), 4)
        expect(current.activeReactions).toHaveLength(2)
        expect(current.removalHistory).toHaveLength(1)
        expect(current.activeReactions.every(row => row.messageId !== 'message-1' || row.channelId !== 'channel-1')).toBe(true)
    })

    test('an add after removal restores active state but preserves historical entry', () => {
        let current = applyReactionAdd(state(), base(), 10)
        current = applyReactionRemove(current, base(), 20)
        current = applyReactionAdd(current, base(), 30)
        expect(current.removalHistory).toHaveLength(1)
        expect(current.activeReactions).toHaveLength(1)
        expect(buildNativeSnapshot(current).activeRemovals).toHaveLength(0)
    })

    test('a second remove after re-add creates a new historical record', () => {
        let current = applyReactionAdd(state(), base(), 10)
        current = applyReactionRemove(current, base(), 20)
        current = applyReactionAdd(current, base(), 30)
        current = applyReactionRemove(current, base(), 40)
        expect(current.removalHistory).toHaveLength(2)
        expect(current.removalHistory.map(row => row.timestamp)).toEqual([20, 40])
    })

    test('duplicate remove events for the same reaction epoch are deduplicated', () => {
        let current = applyReactionAdd(state(), base(), 10)
        current = applyReactionRemove(current, base(), 20)
        current = applyReactionRemove(current, base(), 21)
        expect(current.removalHistory).toHaveLength(1)
    })

    test('a new user add does not clear another user removal history', () => {
        let current = applyReactionAdd(state(), base({ user_id: 'user-a' }), 1)
        current = applyReactionRemove(current, base({ user_id: 'user-a' }), 2)
        current = applyReactionAdd(current, base({ user_id: 'user-b' }), 3)
        expect(current.removalHistory.map(row => row.userId)).toEqual(['user-a'])
        expect(current.activeReactions.map(row => row.userId)).toEqual(['user-b'])
    })

    test('burst reaction type is kept separate from normal reactions', () => {
        let current = applyReactionAdd(state(), base({ reaction_type: 0 }), 1)
        current = applyReactionAdd(current, base({ reaction_type: 1 }), 2)
        current = applyReactionRemove(current, base({ reaction_type: 1 }), 3)
        expect(current.activeReactions.map(row => row.reactionType)).toEqual([0])
        expect(current.removalHistory[0].reactionType).toBe(1)
    })

    test('custom emoji identity uses stable ID even if name is absent', () => {
        const current = applyReactionRemove(state(), base({ emoji: { id: '987654321', name: null } }), 10)
        expect(current.removalHistory[0].emojiKey).toBe('custom:987654321')
    })

    test('unicode emoji names are normalized consistently', () => {
        const decomposed = 'e\u0301'
        const composed = 'é'
        let current = applyReactionAdd(state(), base({ emoji: { name: decomposed } }), 1)
        current = applyReactionRemove(current, base({ emoji: { name: composed } }), 2)
        expect(current.removalHistory).toHaveLength(1)
        expect(current.removalHistory[0].emojiKey).toBe('unicode:é')
    })

    test('missing user, message, channel, or emoji never creates a fake record', () => {
        const incomplete: ReactionPayload[] = [
            base({ user_id: undefined }),
            base({ message_id: undefined }),
            base({ channel_id: undefined }),
            base({ emoji: undefined }),
        ]
        for (const payload of incomplete) {
            expect(applyReactionRemove(state(), payload, 1).removalHistory).toHaveLength(0)
        }
    })

    test('bulk remove events clear active state without fabricating user records', () => {
        let current = applyReactionAdd(state(), base(), 1)
        current = applyRemoveAll(current, base(), 2)
        expect(current.activeReactions).toHaveLength(0)
        expect(current.removalHistory).toHaveLength(0)

        current = applyReactionAdd(state(), base(), 3)
        current = applyRemoveEmoji(current, base(), 4)
        expect(current.activeReactions).toHaveLength(0)
        expect(current.removalHistory).toHaveLength(0)
    })

    test('deleting a message clears its active state and removal history only', () => {
        let current = applyReactionAdd(state(), base(), 1)
        current = applyReactionRemove(current, base(), 2)
        current = applyReactionAdd(current, base({ message_id: 'message-2' }), 3)
        current = applyReactionRemove(current, base({ message_id: 'message-2' }), 4)
        current = applyMessageDelete(current, { id: 'message-1', channel_id: 'channel-1' }, 5)
        expect(current.removalHistory.map(row => row.messageId)).toEqual(['message-2'])
        expect(current.activeReactions).toHaveLength(0)
    })

    test('bulk message deletion clears every listed message', () => {
        let current = applyReactionRemove(state(), base(), 1)
        current = applyReactionRemove(current, base({ message_id: 'message-2' }), 2)
        current = applyBulkMessageDelete(current, { ids: ['message-1', 'message-2'], channel_id: 'channel-1' }, 3)
        expect(current.removalHistory).toHaveLength(0)
    })

    test('storage migration preserves legacy exact-message emoji users', () => {
        const migrated = normalizeReactionStorage({
            activeRemovals: [
                { pillKey: 'message-1|heart:123456789|0', removedUsers: ['user-1', '*'] },
            ],
        }, 100)
        expect(migrated.removalHistory).toHaveLength(1)
        expect(migrated.removalHistory[0]).toMatchObject({
            messageId: 'message-1',
            userId: 'user-1',
            emojiKey: 'custom:123456789',
            channelId: '',
        })
    })

    test('legacy removal records survive later state updates without a channel ID', () => {
        let migrated = normalizeReactionStorage({
            activeRemovals: [
                { pillKey: 'message-1|heart:123456789|0', removedUsers: ['user-1'] },
            ],
        }, 100)
        migrated = normalizeReactionStorage(migrated, 200)
        expect(migrated.removalHistory).toHaveLength(1)

        const snapshot = buildNativeSnapshot(migrated, 200)
        expect(snapshot.activeRemovals).toEqual([
            { pillKey: 'message-1|custom:123456789|0', removedUsers: [{ id: 'user-1' }] },
        ])
    })

    test('history expires after retention and stays bounded', () => {
        const old = applyReactionRemove(state(), base(), 1)
        expect(normalizeReactionStorage(old, 1 + 30 * 24 * 60 * 60 * 1000 + 1).removalHistory).toHaveLength(0)

        let current = state()
        for (let index = 0; index < 1005; index++) {
            current = applyReactionRemove(current, base({
                message_id: `message-${index}`,
                user_id: `user-${index}`,
            }), 1000 + index)
        }
        expect(current.removalHistory).toHaveLength(1000)
    })
    test('active reaction cache and epoch map stay bounded', () => {
        const activeReactions = Array.from(
            { length: MAX_ACTIVE_REACTIONS + 10 },
            (_, index) => ({
                guildId: 'guild-1',
                channelId: 'channel-1',
                messageId: `active-message-${index}`,
                userId: `active-user-${index}`,
                emojiId: null,
                emojiName: 'heart',
                emojiKey: 'unicode:heart',
                reactionType: 0,
            }),
        )
        const epochs = Object.fromEntries(activeReactions.map((item, index) => [
            JSON.stringify([
                item.guildId,
                item.channelId,
                item.messageId,
                item.emojiKey,
                item.reactionType,
                item.userId,
            ]),
            index + 1,
        ]))
        const current = normalizeReactionStorage({
            ...state(),
            activeReactions,
            epochs,
        }, 10_000)

        expect(current.activeReactions).toHaveLength(MAX_ACTIVE_REACTIONS)
        expect(Object.keys(current.epochs)).toHaveLength(MAX_ACTIVE_REACTIONS)
    })

})
