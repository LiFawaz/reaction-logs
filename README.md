# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin keeps that reaction visible in your local session, highlights its reaction pill in red, and shows \`removed at HH:mm\` directly beside the emoji inside that pill. The time uses your device's local timezone and a 24-hour clock.

Each reaction is matched by message ID, emoji ID/name, and reaction type, so removed reactions and their timestamps stay attached to their own reaction pills. When the same reaction is added again, its matching removal marker and timestamp are cleared.

Removed reactions are retained while the plugin is running. Reloading Discord clears the local markers and displays the real server state again.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Source

The plugin source code is available on the \`main\` branch.

## Important

This only changes what your client displays. It does not restore a reaction on Discord's servers, and other users see the real state.
