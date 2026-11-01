import { onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import SettingsComponent from './settings'

interface Emoji {
    id?: string | null
    name: string
}

interface ReactionPayload {
    channelId: string
    messageId: string
    guildId?: string
    userId?: string
    emoji?: Emoji
    reactionType?: number
}

export default plugin({
    start({ cleanup, plugin }) {
        const removedKeys = new Set<string>()

        const syncNative = () => {
            try {
                const entries = [...removedKeys].map(pillKey => ({ pillKey }))
                setRemoved(JSON.stringify(entries))
            } catch (error) {
                plugin.reportError('Failed to refresh reaction logs: ' + String(error))
            }
        }

        const makeKey = (payload: ReactionPayload): string | null => {
            if (!payload.emoji?.name || !payload.messageId) return null

            const reactionType = Number.isFinite(payload.reactionType)
                ? Number(payload.reactionType)
                : 0
            const emojiKey = payload.emoji.id ?? payload.emoji.name

            return reactionKey(payload.messageId, emojiKey, reactionType)
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    const key = makeKey(payload)
                    if (!key || !payload.channelId) return payload

                    removedKeys.add(key)
                    syncNative()

                    // Keep the removed reaction visible in this client only.
                    return undefined
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    const key = makeKey(payload)
                    if (!key || !removedKeys.has(key)) return payload

                    removedKeys.delete(key)
                    syncNative()

                    // The locally retained count already includes this reaction.
                    return undefined
                },
            ),

            () => {
                removedKeys.clear()
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear reaction logs: ' + String(error))
                }
            },
        )
    },

    SettingsComponent,
})

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + ':' + emojiKey + ':' + reactionType
}
