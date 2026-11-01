# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin keeps that reaction visible in your local session and highlights its reaction pill in red. Hold the reaction to open its details: the plugin shows the removal time in small text on the same line, directly beside that emoji in the popup's emoji list.

The timestamp defaults to a 12-hour clock (for example, `11:28 AM`). You can switch to a 24-hour clock (for example, `23:28`) in the plugin's settings. Both formats use your device's current local timezone automatically.

Each reaction is matched by message ID, emoji ID/name, and reaction type, so removed reactions and their timestamps stay attached to the correct message and emoji. When the same reaction is added again, its matching removal marker and timestamp are cleared.

Removed reactions are retained while the plugin is running. Reloading Discord clears the local markers and displays the real server state again.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Source

The plugin source code is available on the `main` branch.

## Important

This only changes what your client displays. It does not restore a reaction on Discord's servers, and other users see the real state.
