# Reaction Logs 1.2.8

This release carries the timestamp-free local reaction removal behavior from the 1.0.0 rebuild, published as an upgrade-compatible version.

- Removed timestamps and timer-driven behavior from the removal history.
- Keep removed reaction pills red locally until the same emoji is added again.
- Persist active red markers locally across plugin reloads.
- Keep Configure history with the removed emoji, affected account, profile link, and message link.
- Re-adding an emoji clears its active red marker without deleting its prior history entry.
