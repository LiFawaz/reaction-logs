# Reaction Logs 1.2.2

- Rebind the last real reaction list after a removal event reaches the native bridge.
- Close the timing race where Discord can remove a zero-count reaction row before the removal record is applied.
- Keep the locally reconstructed red reaction row visible and tappable after Discord refreshes the message.
- Build validation does not replace an on-device Valenced test.
