# Reaction Logs 1.2.1

- Recreate a local red reaction row when Discord removes the final server reaction from the message.
- Clone Discord's concrete reaction model so the retained row remains compatible with RecyclerView diffing and reaction interactions.
- Keep the retained row non-selected, allowing the current user to add the reaction again.
- Show the saved removal time and affected reaction user in a popup beside the red pill on long-press.
- Dismissing the popup leaves the log available for the next long-press.
- Build and package validation do not confirm on-device rendering or tap behavior.
