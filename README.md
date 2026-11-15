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
| `menu` | Open the prefix, suffix and nickname menus |
| `equip <prefix\|suffix> <id>` | Equip a title you are eligible for |
| `unequip <prefix\|suffix> <id>` | Unequip one title |
| `unequip <prefix\|suffix> all` | Unequip every title in that slot |
| `nickname <text>` | Set a nickname (needs `fancycrests.nickname.set`) |
| `nickname reset` | Drop the nickname and show the profile name again |

Use the buttons at the bottom of the menu to switch between prefixes, suffixes
and nicknames. Select an equipped title to remove it, or an available title to
equip it. The nickname menu lets permitted players enter or reset a nickname;
its vanilla anvil input is limited to 50 characters, including formatting codes.
Use the nickname command when a longer input is allowed by the configuration.

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
| `nickname reset <player>` | Reset a player's nickname |

Targets accept a UUID or an exact online player name. Nicknames are never used for lookups.

## Permissions

| Node | Default |
| --- | --- |
| `fancycrests.menu` | everyone |
| `fancycrests.equip` | everyone |
| `fancycrests.nickname.set` | operators; others require an explicit grant |
| `fancycrests.nickname.bypass_reserved` | operators; others require an explicit grant |
| `fancycrests.admin.grant` | operators |
| `fancycrests.admin.revoke` | operators |
| `fancycrests.admin.inspect` | operators |
| `fancycrests.admin.preview` | operators |
| `fancycrests.admin.reload` | operators |
| `fancycrests.admin.validate` | operators |
| `fancycrests.admin.diagnose` | operators |
| `fancycrests.admin.nickname_reset` | operators |

Operators also have the custom permission nodes used by title definitions and
second-line rule conditions within Fancy Crests. This does not write permissions
to LuckPerms or grant them to other mods. The vanilla operator list determines
player status, including lower-level operators and eligible singleplayer owners.

Evaluation order per node: explicit deny → explicit allow → operator access to
mod nodes → documented default. A deny from any backend overrides any allow or
operator status. Use the built-in `permissions.nodes` deny list or LuckPerms
`false` to disable an operator's node. If an operator's external permission data
is still loading, access waits until its denies can be checked. Online OP changes
revalidate equipped titles and display state.

The nickname bypass node skips reserved-name and other online players' real-name
checks. Disabling a nickname permission does not erase an existing nickname. A
title's local ownership grant remains a separate eligibility source, so denying
its permission does not revoke an existing ownership grant. Console/RCON can run
admin commands but not player-only commands.

## Configuration

`config/fancycrests.json` is validated on load and every problem is reported with
its exact JSON path. Unknown or misspelled fields are **errors**, not silently
ignored members (`permisson`, `titels` and a segment without `type` used to change
behaviour quietly). A failed **reload** keeps the previous configuration active.
A failed **startup** load falls back to the last-known-good copy
(`fancycrests.json.last-good`, rewritten after every successful full validation);
if neither copy is usable the server enters **protection mode**: title state
changes are refused and saved equipment is never unequipped against an unknown
catalog, so a bad edit cannot wipe existing choices. Fix the file and restart or
`/fancycrests reload` to leave protection.

| Section | Purpose |
| --- | --- |
| `channels` | Independent switches: `headRender`, `chat`, `tabList`, `placeholdersExport`; optional `headRenderTemplate` / `chatTemplate` / `tabListTemplate` override the global first line per channel (absent = inherit the global template, `[]` = that channel has no first line) |
| `equipment` | `maxPrefixes` / `maxSuffixes` per player (0–10; `0` disables the slot) |
| `nickname` | `maxLength` / `minLength` (1–64), `pattern`, `reservedNames`, `allowSet` |
| `permissions.nodes` | Built-in backend: per-UUID `allow` / `deny` lists (trailing `.*` wildcards supported) |
| `firstLineTemplate` | Ordered segments of type `prefixes` / `name` / `suffixes`, each with `separator` and optional `separatorStyle` |
| `secondLineRules` | `id`, `priority` (0–1000), `condition` (`dimension` / `permission`), `template`, `refreshTicks` (1–72000, default 20) |
| `render` | `distanceBlocks` (8–128) and the first/second line offsets |
| `titles` | `id`, `slot` (`prefix`/`suffix`), `displayName`, `text` (`&` codes), `permission`, `category`, `iconItem`, `sortOrder` (0–10000) |

Titles use a name tag icon by default. Set `iconItem` to a registered item ID,
such as `minecraft:diamond`, to use a different icon. Equipped titles show an
enchantment glint; unequipped titles do not.

Numeric fields are parsed exactly from the JSON text: `3.0` is accepted as the
same integer, while `3.9`, `1e5` and values outside the field's range are errors
with the exact path. A condition `dimension` without a namespace means the
vanilla namespace (`overworld` → `minecraft:overworld`); nicknames may not start
or end with whitespace.

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

The jar is written to `build/libs/FancyCrests-<version>.jar`.

### Shared local test server

Use `./gradlew runServer --console=plain` (Windows:
`.\gradlew.bat runServer --console=plain`) for the shared development server. Loom's generated server IDE
configuration uses the same `run/` working directory and the current project
classes. Do not also copy a Fancy Crests jar into its `mods/` directory.

Keep the existing shared world and configuration between test sessions. Stop the
server with the console command `stop` and wait for shutdown before changing
mods or starting it from another tool. Never run two instances against this
directory. Reset test data only when explicitly requested.

The locally configured test server has usage notes in `run/README.md`. Its
configuration, player data, optional mods and control credentials are local only
and are not distributed with the repository. On a new checkout, create your own
server configuration and review the Minecraft EULA before accepting it.

## License

[Apache-2.0](LICENSE)
