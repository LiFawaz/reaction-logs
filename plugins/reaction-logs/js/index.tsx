import { Dispatcher } from '@valenced/discord/common/flux'
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

export default plugin<{ jsonStorage: Settings }>({
    jsonStorage: {
        load: true,
        default: DefaultSettings,
    },

    start({ cleanup, jsonStorage, plugin }) {
        const logs = new Map<string, ReactionLog>()
        const changed = new Set<string>()
        let stopped = false

        const settings = (): Settings => ({
            ...DefaultSettings,
            ...(jsonStorage.cache as Partial<Settings> | undefined),
        })

        const syncNative = () => {
            try {
                setRemoved([...logs.keys()])
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
            const key = reactionKey(payload.messageId, emojiKey, reactionType)
            const log: ReactionLog = {
                messageId: payload.messageId,
                channelId: payload.channelId,
                guildId: payload.guildId,
                emojiKey,
                reactionType,
                deletedAt: formatTime(new Date()),
            }

            logs.set(key, log)
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

            // When the same reaction is added again, consume that add and clear its old tombstone.
            // This avoids adding to a count that still includes the locally retained reaction.
            onFluxEventDispatched<ReactionPayload>('MESSAGE_REACTION_ADD', payload => {
                if (!settings().enabled || !payload.emoji?.name || !payload.messageId) return payload

                const reactionType = Number.isFinite(payload.reactionType)
                    ? Number(payload.reactionType)
                    : 0
                const key = reactionKey(payload.messageId, payload.emoji.id ?? payload.emoji.name, reactionType)
                const previous = logs.get(key)
                if (!previous) return payload

                logs.delete(key)
                syncNative()
                rerenderMessage(previous)
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
                        }),                    )
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

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + ':' + emojiKey + ':' + reactionType
}

function formatTime(date: Date) {
    return String(date.getHours()).padStart(2, '0') + ':' +
        String(date.getMinutes()).padStart(2, '0')
}