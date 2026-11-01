import { onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'

interface Emoji {
    id?: string | null
    name: string
}

interface ReactionPayload {
    messageId?: string
    id?: string
    ids?: string[]
    userId?: string
    emoji?: Emoji
    reactionType?: number
    burst?: boolean
}

export default plugin({
    start({ cleanup, plugin }) {
        const pendingRemovals = new Map<string, Set<string>>()

        const syncNative = () => {
            try {
                const entries = [...pendingRemovals].map(([pillKey, users]) => ({
                    pillKey,
                    removedUsers: [...users]
                        .filter(userId => userId !== '*')
                        .map(id => ({ id })),
                }))
                setRemoved(JSON.stringify(entries))
            } catch (error) {
                plugin.reportError('Failed to refresh reaction logs: ' + String(error))
            }
        }

        const makeKey = (payload: ReactionPayload): string | null => {
            const emoji = payload.emoji
            if (!emoji?.name || !payload.messageId) return null

            const reactionType = payload.reactionType === 1 || payload.burst === true ? 1 : 0
            const emojiKey = emoji.id ? emoji.name + ':' + emoji.id : emoji.name
            return reactionKey(payload.messageId, emojiKey, reactionType)
        }

        const clearMessage = (messageId?: string) => {
            if (!messageId) return false
            const prefix = messageId + '|'
            let changed = false

            for (const key of pendingRemovals.keys()) {
                if (key.startsWith(prefix)) {
                    pendingRemovals.delete(key)
                    changed = true
                }
            }
            return changed
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const users = pendingRemovals.get(key) ?? new Set<string>()
                    users.add(payload.userId || '*')
                    pendingRemovals.set(key, users)
                    syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    const key = makeKey(payload)
                    if (!key) return payload

                    const users = pendingRemovals.get(key)
                    if (users) {
                        users.delete(payload.userId || '*')
                        if (users.size === 0) pendingRemovals.delete(key)
                        syncNative()
                    }
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    if (clearMessage(payload.messageId)) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_EMOJI',
                payload => {
                    if (!payload.messageId || !payload.emoji?.name) return payload
                    const emojiKey = payload.emoji.id
                        ? payload.emoji.name + ':' + payload.emoji.id
                        : payload.emoji.name
                    const prefix = payload.messageId + '|' + emojiKey + '|'
                    let changed = false

                    for (const key of pendingRemovals.keys()) {
                        if (key.startsWith(prefix)) {
                            pendingRemovals.delete(key)
                            changed = true
                        }
                    }

                    if (changed) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE',
                payload => {
                    if (clearMessage(payload.id ?? payload.messageId)) syncNative()
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE_BULK',
                payload => {
                    let changed = false
                    for (const messageId of payload.ids ?? []) {
                        changed = clearMessage(messageId) || changed
                    }
                    if (changed) syncNative()
                    return payload
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
})

function reactionKey(messageId: string, emojiKey: string, reactionType: number) {
    return messageId + '|' + emojiKey + '|' + reactionType
}
