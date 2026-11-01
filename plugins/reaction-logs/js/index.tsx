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
    emoji?: Emoji
    reactionType?: number
}

export default plugin({
    start({ cleanup, plugin }) {
        // Track each blocked removal separately. A Set loses information when
        // multiple users remove the same emoji before someone adds it again.
        const pendingRemovals = new Map<string, number>()

        const syncNative = () => {
            try {
                const entries = [...pendingRemovals.keys()].map(pillKey => ({ pillKey }))
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

                    pendingRemovals.set(key, (pendingRemovals.get(key) ?? 0) + 1)
                    syncNative()

                    // Keep the removed reaction visible in this client only.
                    // Its matching add event is balanced below.
                    return undefined
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const pending = pendingRemovals.get(key) ?? 0
                    if (pending <= 0) return payload

                    // Balance exactly one previously blocked removal. If more
                    // removals are still pending, keep the pill marked red.
                    if (pending === 1) {
                        pendingRemovals.delete(key)
                    } else {
                        pendingRemovals.set(key, pending - 1)
                    }
                    syncNative()

                    // The local count already contains the blocked removal, so
                    // block this matching add to keep it aligned with the server.
                    return undefined
                },
            ),

            () => {
                pendingRemovals.clear()
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
