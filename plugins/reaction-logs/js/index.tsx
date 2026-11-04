import { onFluxEventDispatched } from '@valenced/discord/flux'
import { setRemoved, clearRemoved } from './native'
import {
    applyBulkMessageDelete,
    applyMessageDelete,
    applyReactionAdd,
    applyReactionRemove,
    applyRemoveAll,
    applyRemoveEmoji,
    buildNativeSnapshot,
    normalizeReactionStorage,
} from './state'
import { DefaultStorage } from './shared'
import type { ReactionPayload, ReactionStorage } from './shared'

export default plugin<{ jsonStorage: ReactionStorage }>({
    jsonStorage: {
        load: true,
        default: DefaultStorage,
    },

    start({ cleanup, plugin, jsonStorage }) {
        let state = normalizeReactionStorage(jsonStorage.cache, Date.now())
        let persistQueue = Promise.resolve()

        const persistState = () => {
            const snapshot = normalizeReactionStorage(state, Date.now())
            persistQueue = persistQueue
                .then(() => jsonStorage.set(snapshot, true))
                .catch(error => {
                    plugin.reportError('Failed to save reaction history: ' + String(error))
                })
        }

        const syncNative = () => {
            try {
                setRemoved(JSON.stringify(buildNativeSnapshot(state)))
            } catch (error) {
                plugin.reportError('Failed to refresh reaction popup records: ' + String(error))
            }
        }

        const update = (next: ReactionStorage) => {
            state = next
            persistState()
            syncNative()
        }

        cleanup(
            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_ADD',
                payload => {
                    update(applyReactionAdd(state, payload))
                    // Let Discord update its actual active reactor list and count.
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE',
                payload => {
                    update(applyReactionRemove(state, payload))
                    // The removed user is retained by our popup model patch, not by blocking Discord.
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_ALL',
                payload => {
                    update(applyRemoveAll(state, payload))
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_REACTION_REMOVE_EMOJI',
                payload => {
                    update(applyRemoveEmoji(state, payload))
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE',
                payload => {
                    update(applyMessageDelete(state, payload))
                    return payload
                },
            ),

            onFluxEventDispatched<ReactionPayload>(
                'MESSAGE_DELETE_BULK',
                payload => {
                    update(applyBulkMessageDelete(state, payload))
                    return payload
                },
            ),

            () => {
                try {
                    clearRemoved()
                } catch (error) {
                    plugin.reportError('Could not clear reaction highlights: ' + String(error))
                }
            },
        )

        // Restore retained removal history after a plugin reload and prune expired records.
        update(state)
    },
})
