# Reaction Logs 1.2.0

- Show the saved removal time through an Android long-press tooltip on red reaction pills.
- Keep tooltip text attached to the pill after it is dismissed so it can be opened again.
- Include the affected reaction user's cached display name when available.
- Stop swallowing normal reaction remove/add state updates; this fixes stale own-reaction state and re-tap failures.
- Preserve per-user removal history until that same user adds the reaction again or the message/reaction is cleared.
- Discord's event exposes the user whose reaction was removed, not the moderator/actor who initiated the removal.
- Build and package validation do not confirm on-device tooltip behavior.
