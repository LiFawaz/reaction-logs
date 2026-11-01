# Reaction Logs

A Valenced-only plugin for Discord Android.

When a reaction is removed, the plugin keeps that reaction visible in your local session and highlights its reaction pill in red. It does not add a timestamp, arrow, remover name, or text underneath the reaction.

Each reaction is matched by message ID, emoji ID/name, and reaction type, so removed reactions are marked on their own reaction pills.

Removed reactions are retained while the plugin is running. Reloading Discord clears the local markers and displays the real server state again.

## Install

Add this repository in Valenced:

https://raw.githubusercontent.com/LiFawaz/reaction-logs/gh-pages/index.json

## Source

All plugin source code is on the `main` branch. GitHub Actions builds and publishes the installable package automatically.

## Important

This only changes what your client displays. It does not restore a reaction on Discord's servers, and other users see the real state.
