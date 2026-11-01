# Reaction Logs 1.2.3

- Removed the timestamp, long-press popup, user-detail text, and clock-format setting entirely.
- Simplified removal tracking to a red marker per reaction.
- Reuse the last known message ID when Discord rebinds the reaction flexbox outside the adapter's immediate bind call.
- Keep rebuilding zero-count removed rows from the last concrete reaction model after Discord refreshes the message.
- Keep normal Discord reaction events enabled so reaction counts and the current user's reaction state stay synchronized.
- Build validation does not replace an on-device Valenced test.
