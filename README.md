# Reaction Logs

A Valenced-only plugin that keeps removed reactions visible in your own Discord session.

When someone removes a reaction, Reaction Logs blocks the local removal event. The reaction stays on your screen, its pill is marked red, and the message shows when it was removed.

The log is temporary. Reloading Discord clears the local markers and syncs the real reaction state again.

## Install

Build the plugin ZIP from GitHub Actions, then install it in Valenced.

## Notes

This only changes what your client displays. It does not restore the reaction on Discord's servers, and other users will see the real state.
