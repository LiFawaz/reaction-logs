# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin marks the reaction pill red. Long-press a red reaction to open a small popup with the local removal time and the user whose reaction disappeared. Dismissing the popup does not clear the saved information; long-press the red reaction again to show it.

The timestamp defaults to a 12-hour clock. You can switch to a 24-hour clock in the plugin settings. Both formats use your phone's local timezone.

Removed reaction rows are kept locally even when Discord's server count reaches zero, so the red row can remain visible and be tapped again. Normal Discord reaction add/remove updates are allowed to run so reaction counts and your own reaction state stay synchronized.

The name shown is the user whose reaction was removed. Discord's reaction event does not identify a moderator or other actor who may have initiated the removal.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Important

This changes only what your client displays. It does not restore reactions on Discord's servers, and other users see the real server state.
