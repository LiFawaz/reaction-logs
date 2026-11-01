# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin marks its reaction pill red and keeps a local row visible even when Discord's server count reaches zero. The plugin does not show timestamps, popups, or removal details.

Discord's normal reaction add and remove updates are allowed to run, so the count and your own reaction state stay synchronized. When the affected user adds that reaction again, its red removal marker is cleared.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Important

This changes only what your client displays. It does not restore reactions on Discord's servers, and other users see the real server state.
