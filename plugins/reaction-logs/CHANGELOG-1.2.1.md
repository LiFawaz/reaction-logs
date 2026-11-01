# Reaction Logs 1.2.1

- Keep the local reaction removal event from immediately deleting the red pill from the message row.
- Preserve the removed reaction count locally until the same affected user adds the reaction again, or the message/reaction is cleared.
- When the current user's red retained reaction is tapped again, route the stale remove action to an add action so the reaction can be restored.
- Balance the matching optimistic add and gateway echo without double-counting the retained local pill.
- Preserve normal and burst reaction types.
- Build and package validation do not confirm on-device interaction behavior.
