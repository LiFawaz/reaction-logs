# Reaction Logs 1.2.5

- Separate the removal history from the live red reaction-pill highlight.
- Persist each observed reaction-removal event in plugin JSON storage.
- Keep historical entries when the reaction is added again, when an emoji is removed in bulk, or when the message is deleted.
- Add a Removal History page in plugin settings, with a clear-history action.
- Keep the live red marker independent; it may disappear when Discord removes the final reaction pill.
- Remove timestamp, clock, and popup functionality.
- Retain up to 500 recent removal entries.
