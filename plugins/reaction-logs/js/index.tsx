import { lookupModule } from '@valenced/modules/finders'
import { withProps } from '@valenced/modules/finders/filters'
import { registerJSMethod } from '@valenced/modules/native'
import { Stores } from '@valenced/discord/flux'
import { onFluxEventDispatched } from '@valenced/discord/flux'
import { clearRemoved, setRemoved } from './native'
import SettingsComponent from './settings'

interface Emoji {
    id?: string | null
    name: string
}

interface ReactionPayload {
    type: string
    channelId: string
    messageId: string
    guildId?: string
    userId?: string
    emoji?: Emoji
    reactionType?: number
    optimistic?: boolean
}

interface DiscordUser {
    id: string
    username?: string
    globalName?: string | null
    global_name?: string | null
}

interface ReactionRemover {
    userId: string
    displayName: string
}

interface ReactionLog {
    id: string
    messageId: string
    channelId: string
    guildId?: string
    pillKey: string
    deletedAtMs: number
    removedBy?: ReactionRemover
}

interface UserProfileActions {
    openUserProfileModal(options: { userId: string }): void
}

// Native reaction-log labels call this method when the username is tapped.
registerJSMethod('lifawaz.reaction-logs.openProfile', (userId: string) => {
    if (!/^\d{15,25}$/.test(userId)) return

    const [profileActions] = lookupModule(
        withProps<UserProfileActions>('openUserProfileModal'),
    )

    if (typeof profileActions?.openUserProfileModal !== 'function') {
        throw new Error('Discord user profile action is unavailable')
    }

    profileActions.openUserProfileModal({ userId })
})

export default plugin({
    start({ cleanup, plugin }) {
        const logs = new Map<string, ReactionLog>()
        let logSequence = 0

        const syncNative = () => {
            try {
                const entries = [...logs.values()].map(log => ({
                    pillKey: log.pillKey,
                    deletedAtMs: log.deletedAtMs,
                    userId: log.removedBy?.userId ?? null,
                    displayName: log.removedBy?.displayName ?? 'Unknown user',
                }))
                setRemoved(JSON.stringify(entries))
            } catch (error) {
                plugin.reportError('Could not update reaction log labels: ' + String(error))
            }
        }

        const handleRemoval = (payload: ReactionPayload) => {
            const emoji = payload.emoji
            if (!emoji?.name || !payload.messageId || !payload.channelId) return payload

            const reactionType = Number.isFinite(payload.reactionType)
                ? Number(payload.reactionType)
                : 0
            const emojiKey = emoji.id ?? emoji.name
            const pillKey = reactionKey(payload.messageId, emojiKey, reactionType)
            const now = Date.now()

            const log: ReactionLog = {
                id: pillKey + ':' + now + ':' + (++logSequence),
                messageId: payload.messageId,
                channelId: payload.channelId,
                guildId: payload.guildId,
                pillKey,
                deletedAtMs: now,
                removedBy: getRemover(payload.userId, payload.guildId),
            }

            // Keep separate entries for repeated removals of the same reaction.
            logs.set(log.id, log)
            syncNative()

            // Block only this client's removal event. Discord's server state is unchanged.
            return undefined
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE', payload =>
                handleRemoval(payload),
            ),

            // If the same reaction is added again, remove its local deletion labels.
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_ADD', payload => {
                if (!payload.emoji?.name || !payload.messageId) return payload

                const reactionType = Number.isFinite(payload.reactionType)
                    ? Number(payload.reactionType)
                    : 0
                const key = reactionKey(
                    payload.messageId,
                    payload.emoji.id ?? payload.emoji.name,
                    reactionType,
                )
                const previous = [...logs.values()].filter(log => log.pillKey === key)
                if (!previous.length) return payload

                for (const log of previous) logs.delete(log.id)
                syncNative()

                // The retained local count already includes the reaction being added.
                return undefined
            }),

            () => {
                logs.clear()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear reaction log labels: ' + String(error))
                }
            },
        )
    },

    SettingsComponent,
})

function getRemover(userId?: string, guildId?: string): ReactionRemover | undefined {
    if (!userId || !/^\d{15,25}$/.test(userId)) return undefined

    try {
        const stores = Stores as any
        const user = stores.UserStore?.getUser?.(userId) as DiscordUser | undefined
        const guildNick = guildId
            ? stores.GuildMemberStore?.getNick?.(guildId, userId)
            : undefined
        const relationshipNick = stores.RelationshipStore?.getNickname?.(userId)
        const displayName =
            (typeof guildNick === 'string' && guildNick.trim()) ||
            (typeof relationshipNick === 'string' && relationshipNick.trim()) ||
            user?.globalName ||
            user?.global_name ||
            user?.username ||
            userId

        return { userId, displayName: String(displayName) }
    } catch {
        return { userId, displayName: userId }
    }
}

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + ':' + emojiKey + ':' + reactionType
}
