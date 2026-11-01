import { Dispatcher } from '@valenced/discord/common/flux'
import { Stores } from '@valenced/discord/flux'
import { onFluxEventDispatched } from '@valenced/discord/flux'
import { getModules } from '@valenced/modules/finders'
import { withName } from '@valenced/modules/finders/filters'
import { instead } from '@valenced/patcher'
import { clearRemoved, setRemoved } from './native'
import { styleRow, type ReactionLog, type RowManager } from './rows'
import SettingsComponent from './settings'
import { DefaultSettings } from './shared'
import type { Settings } from './shared'

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
    avatar?: string | null
    getAvatarURL?(guildId?: string | null, size?: number): string | number
}

export default plugin<{ jsonStorage: Settings }>({
    jsonStorage: {
        load: true,
        default: DefaultSettings,
    },

    start({ cleanup, jsonStorage, plugin }) {
        const logs = new Map<string, ReactionLog>()
        const changed = new Set<string>()
        let stopped = false
        let logSequence = 0

        const settings = (): Settings => ({
            ...DefaultSettings,
            ...(jsonStorage.cache as Partial<Settings> | undefined),
        })

        const syncNative = () => {
            try {
                const removedPills = new Set([...logs.values()].map(log => log.pillKey))
                setRemoved([...removedPills])
            } catch (error) {
                plugin.reportError('Could not update reaction highlight: ' + String(error))
            }
        }

        const rerenderMessage = (log: Pick<ReactionLog, 'messageId' | 'channelId' | 'guildId'>) => {
            changed.add(log.messageId)

            // Flux cannot dispatch recursively while it is processing the reaction event.
            Promise.resolve().then(() => {
                if (stopped) return
                try {
                    Dispatcher.dispatch({
                        type: 'MESSAGE_UPDATE',
                        guildId: log.guildId,
                        message: {
                            id: log.messageId,
                            channel_id: log.channelId,
                        },
                    })
                } catch (error) {
                    plugin.reportError('Could not refresh reaction log row: ' + String(error))
                }
            })
        }

        const handleRemoval = (payload: ReactionPayload) => {
            const emoji = payload.emoji
            if (!emoji?.name || !payload.messageId || !payload.channelId) return payload

            const reactionType = Number.isFinite(payload.reactionType)
                ? Number(payload.reactionType)
                : 0
            const emojiKey = emoji.id ?? emoji.name
            const pillKey = reactionKey(payload.messageId, emojiKey, reactionType)
            const now = new Date()
            const log: ReactionLog = {
                id: pillKey + ':' + now.getTime() + ':' + (++logSequence),
                messageId: payload.messageId,
                channelId: payload.channelId,
                guildId: payload.guildId,
                emojiKey,
                reactionType,
                pillKey,
                deletedAt: formatTime(now),
                deletedAtMs: now.getTime(),
                removedBy: getRemover(payload.userId, payload.guildId),
            }

            // Each removal gets its own log entry, even when several people remove
            // the same emoji from one message during this app session.
            logs.set(log.id, log)
            syncNative()
            rerenderMessage(log)

            // Stop Discord from removing the reaction from this client.
            // The server event still happened; a full Discord reload syncs the real state.
            return undefined
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_REMOVE', payload => {
                if (!settings().enabled) return payload
                return handleRemoval(payload)
            }),

            // When the same reaction is added again, consume that add and clear its old tombstones.
            // This avoids incrementing a count that still includes locally retained reactions.
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_ADD', payload => {
                if (!settings().enabled || !payload.emoji?.name || !payload.messageId) return payload

                const reactionType = Number.isFinite(payload.reactionType)
                    ? Number(payload.reactionType)
                    : 0
                const key = reactionKey(payload.messageId, payload.emoji.id ?? payload.emoji.name, reactionType)
                const previous = [...logs.values()].filter(log => log.pillKey === key)
                if (!previous.length) return payload

                for (const log of previous) logs.delete(log.id)
                syncNative()
                rerenderMessage(previous[previous.length - 1]!)
                return undefined
            }),

            getModules(
                withName<RowManager>('RowManager'),
                ({ prototype }) => {
                    cleanup(
                        instead(prototype, 'generate', function (args, original) {
                            const row = original.apply(this, args)

                            try {
                                styleRow(row, logs, changed, settings)
                            } catch (error) {
                                plugin.reportError('Could not render reaction log: ' + String(error))
                            }

                            return row
                        }),
                    )
                },
            ),

            () => {
                stopped = true
                logs.clear()
                changed.clear()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear reaction highlights: ' + String(error))
                }
            },
        )
    },

    SettingsComponent,
})

function getRemover(userId?: string, guildId?: string): ReactionLog['removedBy'] {
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

        let avatarUrl: string | undefined
        try {
            const avatar = user?.getAvatarURL?.(guildId ?? null, 64)
            if (typeof avatar === 'string' && /^https?:\/\//i.test(avatar)) {
                avatarUrl = avatar
            }
        } catch {
            // Fall back to the user's global avatar hash below.
        }

        if (!avatarUrl && typeof user?.avatar === 'string' && user.avatar) {
            avatarUrl = 'https://cdn.discordapp.com/avatars/' +
                userId + '/' + user.avatar + '.png?size=64'
        }
        if (!avatarUrl) {
            avatarUrl = 'https://cdn.discordapp.com/embed/avatars/0.png'
        }

        return { userId, displayName: String(displayName), avatarUrl }
    } catch {
        return { userId, displayName: userId }
    }
}

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + ':' + emojiKey + ':' + reactionType
}

function formatTime(date: Date) {
    return String(date.getHours()).padStart(2, '0') + ':' +
        String(date.getMinutes()).padStart(2, '0')
}
