<img src="src/main/resources/assets/fancycrests/icon.png" align="right" width="128px"/>

# Fancy Crests (Custom Names)

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62B47A)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-DBB69D)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-ED8B00)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)

A server-side [Fabric](https://fabricmc.net/) mod that gives players customizable crests:
equippable prefixes and suffixes, nicknames, and an admin-configured second line —
rendered to **vanilla clients**. No client-side mod is needed.

- Mod ID: `fancycrests`
- Command roots: `/fancycrests`, `/names`, `/customnames`
- Build artifact: `FancyCrests-<version>.jar`

> **Status: pre-release.** P0–P4 are implemented (toolchain, render channel, core
> services, GUI and dynamic templates, optional integrations). P5 acceptance is still
> open: client-side rendering verification with vanilla 26.2 clients, performance
> evidence, and the release package.

> [!CAUTION]
> Developed with assistance from **GPT-6-Astra**, **DeepSeek-V4.1-Flash-1017** and **GLM-5.3**.
> Designed for my own server — use at your own risk.

## Features

- **Prefixes and suffixes** — equip several per player, kept in equip order; slot limits are configurable.
- **Nicknames** — validated length, characters, reserved names and an optional bypass node.
- **Admin second line** — static text mixed with built-in placeholders, matched by priority and target conditions, refreshed on a configurable tick interval.
- **Configurable first line** — reorder the `prefixes` / `name` / `suffixes` segments and their separators.
- **Rendered to vanilla clients** — one virtual `text_display` pair per target plus an optional second-line entity, carried by a per-player client-side virtual team. No real server scoreboard teams are created or modified.
- **Native GUI** — browsing, previewing and equipping happens in a vanilla container menu.
- **Persistence** — SQLite storage with optimistic revisions; choices survive restarts.
- **Hot reload** — `/fancycrests reload` swaps the configuration atomically and revalidates online players.
- **Own permission backend** — works with no permissions plugin installed; optional LuckPerms backend when present.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.20.1+ |
| Fabric API | 0.161.0+26.2 |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 |
| Java | 25 |

Optional integrations (not required to run): **LuckPerms** (Fabric) 5.5.85,
**Text Placeholder API** 3.1.0-beta.1+26.2.

Clients need nothing — vanilla 26.2 works.

## Installation

1. Install Fabric Loader, Fabric API and Fabric Language Kotlin on the server.
2. Put `FancyCrests-<version>.jar` into `mods/`.
3. Start the server once to generate `config/fancycrests.json`.
4. Edit titles, templates and rules, then run `/fancycrests reload`.

## Commands

Aliases `/names` and `/customnames` behave exactly like `/fancycrests`.

### Players

| Command | Description |
| --- | --- |
| `menu` | Open the title menu |
| `equip <prefix\|suffix> <id>` | Equip a title you are eligible for |
| `unequip <prefix\|suffix> <id>` | Unequip one title |
| `unequip <prefix\|suffix> all` | Unequip every title in that slot |
| `nickname <text>` | Set a nickname (needs `fancycrests.nickname.set`) |
| `nickname reset` | Drop the nickname and show the profile name again |

### Administrators

| Command | Description |
| --- | --- |
| `grant <player> <titleId>` | Grant a local ownership record |
| `revoke <player> <titleId>` | Revoke a local ownership record |
| `inspect <player>` | Show state, ownership and equipped titles |
| `preview <player>` | Resolve first and second line without touching state |
| `reload` | Reload the configuration atomically |
| `validate` | Validate the config file on disk without applying it |
| `diagnose` | Channels, permission backends, catalog and storage status |

Targets accept a UUID or an exact online player name. Nicknames are never used for lookups.

## Permissions

| Node | Default |
| --- | --- |
| `fancycrests.menu` | everyone |
| `fancycrests.equip` | everyone |
| `fancycrests.nickname.set` | nobody (grant explicitly) |
| `fancycrests.nickname.bypass_reserved` | nobody (grant explicitly) |
| `fancycrests.admin.grant` | op level 2 |
| `fancycrests.admin.revoke` | op level 2 |
| `fancycrests.admin.inspect` | op level 2 |
| `fancycrests.admin.preview` | op level 2 |
| `fancycrests.admin.reload` | op level 2 |
| `fancycrests.admin.validate` | op level 2 |
| `fancycrests.admin.diagnose` | op level 2 |

Evaluation order per node: explicit deny → explicit allow → documented default.
An explicit deny cannot be overridden by operator status. The console may run admin
commands but not player-only ones.

## Configuration

`config/fancycrests.json` is validated on load; an invalid file leaves the previous
configuration active and reports the offending JSON paths.

| Section | Purpose |
| --- | --- |
| `channels` | Independent switches: `headRender`, `chat`, `tabList`, `placeholdersExport` |
| `equipment` | `maxPrefixes` / `maxSuffixes` per player |
| `nickname` | `maxLength`, `minLength`, `pattern`, `reservedNames`, `allowSet` |
| `permissions.nodes` | Built-in backend: per-UUID `allow` / `deny` lists (trailing `.*` wildcards supported) |
| `firstLineTemplate` | Ordered segments of type `prefixes` / `name` / `suffixes`, each with `separator` and optional `separatorStyle` |
| `secondLineRules` | `id`, `priority`, `condition` (`dimension` / `permission`), `template`, `refreshTicks` (1–72000, default 20) |
| `render` | `distanceBlocks` and the first/second line offsets |
| `titles` | `id`, `slot` (`prefix`/`suffix`), `displayName`, `text` (`&` codes), `permission`, `category`, `iconItem`, `sortOrder` |

Titles with a `permission` are only equippable by players who own them locally or
whose active permission backend allows the node.

## Text formatting

Title text, nickname input, `separatorStyle` and static template text accept classic
colour codes with the `&` prefix (`&6Gold`, `&lbold`, `&r` resets) — easy to type in
chat and config. The vanilla `§` prefix still works for existing configs. A doubled
prefix (`&&`) escapes into a literal `&`, so plain text like `R&&D` stays intact; any
unknown pair such as `Tom & Jerry` is also kept verbatim.

## Placeholders

Second-line templates and the Text Placeholder API export share the same registry:

| Placeholder | Value |
| --- | --- |
| `%fancycrests:prefix%` | Equipped prefixes joined with the configured separator, empty when none |
| `%fancycrests:suffix%` | Equipped suffixes joined with the configured separator, empty when none |
| `%fancycrests:nickname%` | Nickname, otherwise the current profile name |
| `%fancycrests:belowname%` | Resolved admin second line, empty when no rule matched |

## Integrations

- **LuckPerms** — when the Fabric mod is present, a `luckperms` backend is registered
  after the built-in one. Explicit allow/deny wins; undefined nodes fall back to the
  documented default. Permission changes are re-checked on the main thread, so a revoked
  title is removed promptly.
- **Text Placeholder API** — when present, the four placeholders above are exported for
  chat, TAB or any other consumer. Without it the mod runs unchanged.

## Building

Requires **Java 25** and Git.

```sh
git clone https://github.com/XiaoQing235/fancy-crests-mod.git
cd fancy-crests-mod
./gradlew build
```

The jar is written to `build/libs/FancyCrests-<version>.jar`. `./gradlew runServer`
starts a development server under `run/` (EULA acceptance included).

## License

[Apache-2.0](LICENSE)
