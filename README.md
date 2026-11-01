# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin marks the remaining reaction pill red. Long-press a red reaction to open Android's tooltip with the local removal time and the user whose reaction disappeared. Dismissing the tooltip does not clear it; long-press the same red reaction again to show it.

The timestamp defaults to a 12-hour clock (for example, `removed at 11:28 AM`). You can switch to a 24-hour clock (for example, `removed at 23:28`) in the plugin's settings. Both formats use your device's current local timezone automatically.

Reaction add and remove events are allowed to update Discord's real local state. This keeps reaction counts and your own reaction toggle in sync, so you can add a reaction again after removing it. The removal marker clears when that same user adds the reaction again.

The name shown is the user whose reaction was removed, based on Discord's reaction event. Discord does not include the identity of a moderator or other actor who may have triggered a removal, so the plugin cannot identify that actor.

Removal history is kept locally while the plugin is running. Reloading Discord clears the local markers and displays the real server state again. A reaction with a count of zero is removed from Discord's normal reaction row; the saved marker can appear again if that emoji is later added back.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Source

The plugin source code is available on the `main` branch.

## Important

This only changes what your client displays. It does not restore a reaction on Discord's servers, and other users see the real state.
