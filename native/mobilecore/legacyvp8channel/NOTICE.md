# Legacy VP8 transport notice

The files in this directory are derived from the `vp8channel` transport in
`Oleglog/Olcrtc_manager` release `server-v1.9.64`
(`ec536083969fbb0398172192dfb780eb3f031890`), licensed under WTFPL v2.

Client-specific changes:

- renamed the package so it can coexist with the official current transport;
- adapted removed upstream configuration fields and current option types;
- retained the legacy 32-byte `token + source epoch + CRC` wire header;
- removed comments unrelated to the implementation.
