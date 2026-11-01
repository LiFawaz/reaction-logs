import { onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import SettingsComponent from './settings'

interface Emoji {
    id?: string | null
    name: string
}

interface ReactionPayload {
    channelId?: string
    messageId?: string
    id?: string
    ids?: string[]
    userId?: string
    emoji?: Emoji
    reactionType?: number
    burst?: boolean
    optimistic?: boolean
}

interface PendingRemoval {
    users: Set<string>
    anonymous: number
}

const ECHO_WINDOW_MS = 10_000

export default plugin({
    start({ cleanup, plugin }) {
        const pendingRemovals = new Map<string, PendingRemoval>()
        const pendingAddEchoes = new Map<string, number>()

        const syncNative = () => {
            try {
                const entries = [...pendingRemovals]
                    .filter(([, state]) => state.users.size > 0 || state.anonymous > 0)
                    .map(([pillKey]) => ({ pillKey }))
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

        const echoKey = (key: string, userId: string) => key + '\u0000' + userId

        const pruneEchoes = () => {
            const now = Date.now()
            for (const [token, expiresAt] of pendingAddEchoes) {
                if (expiresAt <= now) pendingAddEchoes.delete(token)
            }
        }

        const consumePendingRemoval = (key: string, userId?: string): boolean => {
            const state = pendingRemovals.get(key)
            if (!state) return false

            if (userId && state.users.delete(userId)) {
                // Prefer balancing the same user's removal when they react again.
            } else if (state.users.size > 0) {
                // A new user adding this emoji also balances one old locally-retained removal.
                const firstUser = state.users.values().next().value
                if (firstUser) state.users.delete(firstUser)
            } else if (state.anonymous > 0) {
                state.anonymous--
            } else {
                return false
            }

            if (state.users.size === 0 && state.anonymous === 0) {
                pendingRemovals.delete(key)
            }
            return true
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
            for (const token of pendingAddEchoes.keys()) {
                if (token.startsWith(prefix)) pendingAddEchoes.delete(token)
            }

            return changed
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    pruneEchoes()
                    const key = makeKey(payload)
                    if (!key) return payload

                    const state = pendingRemovals.get(key) ?? {
                        users: new Set<string>(),
                        anonymous: 0,
                    }

                    if (payload.userId) {
                        // Discord may send an optimistic removal followed by its gateway echo.
                        // Count that user once, but swallow both events so the pill stays visible.
                        if (state.users.has(payload.userId)) return undefined
                        state.users.add(payload.userId)
                    } else {
                        state.anonymous++
                    }

                    pendingRemovals.set(key, state)
                    syncNative()
                    return undefined
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    pruneEchoes()
                    const key = makeKey(payload)
                    if (!key) return payload

                    const userId = payload.userId
                    const token = userId ? echoKey(key, userId) : null

                    // The gateway echo of our own optimistic add is the same reaction,
                    // not a second add. Swallow it without consuming another removal.
                    if (token && !payload.optimistic && pendingAddEchoes.has(token)) {
                        pendingAddEchoes.delete(token)
                        return undefined
                    }

                    const balanced = consumePendingRemoval(key, userId)
                    if (balanced) {
                        if (token && payload.optimistic) {
                            pendingAddEchoes.set(token, Date.now() + ECHO_WINDOW_MS)
                        }
                        syncNative()
                        // The locally retained pill already includes the removed reaction.
                        // Swallow this add to keep the count in sync with the server.
                        return undefined
                    }

                    // Keep the optimistic add, but suppress its later gateway echo.
                    if (token && payload.optimistic) {
                        pendingAddEchoes.set(token, Date.now() + ECHO_WINDOW_MS)
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
                    for (const token of pendingAddEchoes.keys()) {
                        if (token.startsWith(prefix)) pendingAddEchoes.delete(token)
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
                pendingAddEchoes.clear()
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
    return messageId + '|' + emojiKey + '|' + reactionType
}
