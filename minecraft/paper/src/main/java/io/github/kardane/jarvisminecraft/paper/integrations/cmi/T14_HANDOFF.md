# T14 CMI integration status

## Authorization and source boundary

- The user confirmed that Zrips granted explicit permission to use CMI-API from JARVIS. The repository does not contain a copy of that permission; this records the user's confirmation without representing it as independently inspected evidence.
- The official CMI API guide documents `com.github.Zrips:CMI-API:9.8.6.4` as a JitPack `provided` dependency. JARVIS uses it as Gradle `compileOnly`; the API and CMI binaries are not bundled into the plugin JAR.
- The inspected CMI-API source is release commit `de669b2`. The integration calls only `CMIUser.getUser(Player)`, `getNickName()`, and `isAfk()`.
- Existing target-server notes list CMI 9.8.9.6 + CMILib 1.5.9.9. This runtime has not yet been smoke-tested against the 9.8.6.4 compile API.

## Implemented scope

- Optional Paper Provider activates only when CMI and CMILib are enabled and the required CMI API methods are available.
- `get_cmi_player_info` accepts one player UUID and reads one currently online player on the server thread.
- The result contains the Minecraft player reference, sanitized CMI nickname, and CMI AFK state.
- Nickname formatting and control characters are stripped, the value is capped at 64 UTF-16 units, and a missing nickname falls back to the Minecraft name.
- Offline targets return `NOT_FOUND`. Missing CMI user data or API/runtime failure returns `PROVIDER_UNAVAILABLE`.
- Offline user loading, play-time lookup, CMI warning, and mutations are out of scope.
- The Paper connection advertises active Registry Tools and capabilities with active Provider versions. Disabled or absent optional integrations remain hidden.

## Verification and remaining runtime smoke

Run local contract checks with:

```powershell
.\gradlew.bat :minecraft:paper:t13Verification :minecraft:paper:t14Verification :minecraft:paper:check :minecraft:paper:jar
```

T14 verification covers registration, online profile mapping, nickname sanitization/length, offline/unavailable errors, and runtime linkage failure. Protocol fixtures cover strict request/result schema parsing. These checks do not prove compatibility with a live CMI server.

For the remaining Paper 1.21.8 smoke, install CMI 9.8.9.6 and CMILib 1.5.9.9 alongside the built JAR, then verify startup ordering, capability source/version, online nickname/AFK results, offline UUID behavior, and fail-closed behavior with CMI disabled or unavailable. Confirm the plugin JAR contains no `com/Zrips/CMI` classes.

## Official evidence

- [CMI API guide](https://www.zrips.net/cmi/api/)
- [CMI-API releases](https://github.com/Zrips/CMI-API/releases)
- [CMI-API CMIUser source](https://github.com/Zrips/CMI-API/blob/9.8.6.4/src/com/Zrips/CMI/Containers/CMIUser.java)
- [CMI placeholders](https://www.zrips.net/cmi/placeholders/)
