# Fancy Crests (Custom Names)

A standalone server-side Fabric mod that gives players customizable crests:
equippable prefixes/suffixes, nicknames and an admin-configured second line,
rendered to **vanilla clients** (no client-side mod required) via virtual
`text_display` entities and per-player client-side virtual teams.

- Mod ID: `fancycrests`
- Command roots: `/fancycrests`, `/names`, `/customnames`
- Build artifact base name: `FancyCrests`

Status: **scaffold (P0)** — toolchain and project skeleton only. Feature work
follows the phased plan (P0 → P5) in the development plan document.

## Toolchain (locked versions)

| Component | Version |
| --- | --- |
| Minecraft | 26.2 (unobfuscated official names) |
| Java | 25 (build and server runtime) |
| Gradle | 9.8.2 (wrapper) |
| Fabric Loom | 1.19.3 (`net.fabricmc.fabric-loom`, non-remapping) |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.161.0+26.2 |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 |
| Kotlin | 2.4.21 |

The project uses the non-remapping Loom plugin: no mappings dependency and no
remap tasks; code is written directly against the official 26.2 names.

## Building

Requires Java 25 on the build machine (the Gradle wrapper handles the rest).

```powershell
.\gradlew.bat build        # produces build/libs/FancyCrests-<version>.jar
.\gradlew.bat runServer    # dev server, with EULA acceptance in run/
```

The mod is written in Kotlin (`src/main/kotlin`) and declares
`fabric-language-kotlin` as a dependency (not bundled); a deployment needs
Fabric Loader, Fabric API and Fabric Language Kotlin installed on the server.

## Project layout

```
settings.gradle.kts     build settings (plugin version pinning)
gradle.properties       single source of truth for locked versions
build.gradle.kts        Loom + Kotlin build script
src/main/kotlin/        Kotlin sources (package: dev.fancycrests)
src/main/resources/     fabric.mod.json, mixin config, assets
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
