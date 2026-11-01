# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin records the event and keeps that reaction visible in your local message row with a red highlight. Long-press a red reaction to open a small popup beside it showing the local removal time and the user whose reaction disappeared. Dismissing the popup does not clear the saved entry; long-press the red reaction again to show it.

The timestamp defaults to a 12-hour clock (for example, `removed at 11:28 AM`). You can switch to a 24-hour clock (for example, `removed at 23:28`) in the plugin's settings. Both formats use your device's current local timezone automatically.

Removal records are tracked per affected user. When that same user reacts again, their old removal entry is cleared and the local count is kept in sync. If the removed reaction belonged to your account, tapping the retained red reaction again re-adds it instead of sending another removal request.

The name shown is the user whose reaction was removed, based on Discord's reaction event. Discord does not include the identity of a moderator or other actor who may have triggered a removal, so the plugin cannot identify that actor.

Removal history is kept locally while the plugin is running. Reloading Discord clears the local markers and displays the real server state again. This only changes what your client displays; other users see the real server state.
