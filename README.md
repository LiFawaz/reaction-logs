# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, its reaction pill turns red and stays visible locally, even when Discord removes the original pill after its count reaches zero. The red marker is saved on this device and remains until that same emoji is added again.

The Configure page keeps a local removal history with the emoji, the affected account, a profile link, and a message link. There are no timestamps, clocks, popups, or timers. Adding the emoji again clears only its current red marker; it does not erase the old history entry.

The user shown is the account whose reaction was removed. Discord's event does not identify a moderator or another person who initiated the removal.

Up to 500 recent entries are retained locally. You can clear the history from the plugin settings.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Important

All reaction markers and history are stored on this device. The plugin does not change Discord's server-side reaction state.
