# Reaction Logs 1.0.0

- Remove all timestamp, clock, timer, and popup functionality.
- Keep a removed reaction pill red and visible locally until that same emoji is added again.
- Persist the active red-pill state on this device so it survives plugin reloads.
- Clear the whole red marker when the same emoji is added, without deleting its older history entry.
- Keep Configure history with the removed emoji, affected account, Discord profile link, and message link.
- Keep history and live red-pill state separate; clearing history does not clear active red markers.
