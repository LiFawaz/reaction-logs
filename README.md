# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin records the reactor locally, keeps the removed reaction pill red, and highlights that user's row and avatar with red styling in the reaction-details popup.

The log is session-scoped. Closing the popup, leaving the screen, or stopping and restarting the plugin does not erase the current session's records. Fully closing and reopening Discord/Valenced starts a fresh session and clears the previous records. Adding the same user's reaction again clears that user's old removal record. Discord's normal reaction event is never blocked.

There is no timestamp UI and no separate history screen.

## Install

Test builds are published separately from the stable repository:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages-test/index.json

## Important

All markers and session records are local to this device. The plugin does not change Discord's server-side reaction state.
