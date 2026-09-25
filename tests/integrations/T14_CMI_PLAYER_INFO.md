# T14 CMI player profile checks

## Local contract checks

`T14VerificationMain` verifies:

- `get_cmi_player_info` registers with `PlayerUuidArguments`.
- An online CMI profile maps player UUID/name, nickname, and AFK state.
- Nickname formatting and control characters are removed; missing values use the Minecraft name; long values are capped and marked truncated.
- Offline players return `NOT_FOUND`.
- A missing CMI user or API linkage/runtime failure returns `PROVIDER_UNAVAILABLE`.

`T13VerificationMain` verifies that CMI requires both CMI and CMILib, does not expose its Tool while a dependency is disabled, and advertises `player.cmi_profile` with the installed CMI version only when active.

The protocol fixture suite verifies a valid CMI Tool request/result, strict UUID arguments, capability advertisement, and the result data shape.

## Paper runtime smoke still required

Use Paper 1.21.8 with CMI 9.8.9.6 and CMILib 1.5.9.9. Confirm:

1. JARVIS starts after CMI/CMILib and advertises the CMI version and profile Tool.
2. A connected player returns the current Minecraft name, CMI nickname, and AFK state.
3. An offline UUID returns `NOT_FOUND` without loading offline CMI data.
4. Disabling CMI, disabling CMILib, or making the API unavailable hides the Tool/capability or returns `PROVIDER_UNAVAILABLE`.
5. The packaged JAR contains no CMI-API/CMI classes.

Local contract tests do not replace this runtime smoke. T14 scope excludes offline profile loading, play time, warning, and mutation Tools.
