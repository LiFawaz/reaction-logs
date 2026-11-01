# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin keeps that reaction visible in your local session and marks its pill red. Directly underneath that exact reaction, it shows a down-arrow log:

`↓ This was deleted at 12:34 PM by @username`

The timestamp uses the device's current locale and timezone, so it follows the same local clock setting as Discord. Tapping the username opens that user's Discord profile.

Each reaction is matched by message ID, emoji ID/name, and reaction type. If several people remove the same emoji, their entries are stacked beneath that emoji's pill. Other emojis in the same message get their own separate entries.

Removed reactions are always retained while the plugin is running. There is no setting to turn that core behavior off. Reloading Discord clears the local markers and displays the real server state again.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Source

All plugin source code and workflows are on the `main` branch. GitHub Actions builds and publishes the installable package automatically.

## Important

This only changes what your client displays. It does not restore a reaction on Discord's servers, and other users see the real state.
