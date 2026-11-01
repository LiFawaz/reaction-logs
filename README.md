# Reaction Logs

A Valenced-only plugin for Discord Android.

Every observed `MESSAGE_REACTION_REMOVE` event is saved in a persistent Removal History page in the plugin settings. Each entry records the reaction emoji, the affected user's account, and the message ID.

The history is separate from the red reaction-pill highlight. Discord can remove the emoji pill when its count reaches zero; that does not delete the saved history entry. Adding the reaction again clears the current red marker but does not erase the old log. Message deletion also leaves the history intact.

The timestamp and popup feature is not included. No fake zero-count reaction pills are created.

The user shown is the account whose reaction was removed. Discord's event does not identify a moderator or other actor who initiated the removal.

Up to 500 recent entries are retained locally. You can clear the history from the plugin settings.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Important

The history is stored on this device only. It does not change Discord's server-side reaction state.
