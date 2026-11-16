<img src="src/main/resources/assets/fancycrests/icon.png" align="right" width="128px"/>

# Fancy Crests (Custom Names)

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62B47A)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-DBB69D)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-ED8B00)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)

[简体中文](README.zh-CN.md) | English

This is a server-side [Fabric](https://fabricmc.net/) mod that gives players a rich customizable title experience without requiring anything on the client. It supports prefixes and suffixes with configurable limits and ordering, custom nicknames or preset selections, and a second line of text that administrators can configure flexibly.

> [!CAUTION]
> Developed with assistance from **GPT-6-Astra**, **DeepSeek-V4.1-Flash-1017** and **GLM-5.3**.
> The mod was originally designed for the author's own server. Please evaluate it for yourself and use it at your own risk.

## Features

- **Prefixes and suffixes**: Each player can equip multiple titles at once. They appear in the order equipped, and the limit for each slot can be configured.
- **Nickname system**: Validates nickname length, allowed characters and reserved names. Permission nodes can be configured to bypass validation for specific players.
- **Administrator-controlled second line**: Combine static text with built-in placeholders. Rules can match by priority and target conditions, and refresh at a configured game-tick interval.
- **Player-controlled first line**: Reorder the `prefixes`, `name` and `suffixes` segments, and configure the separators between them.
- **Rendered for vanilla clients**: Each target gets a pair of virtual `text_display` entities, optionally including a second-line entity. Data is sent through a client-side virtual team for each player; no real scoreboard teams are created or modified on the server.
- **Native GUI menu**: Players can browse, preview and equip titles directly in a vanilla container menu.
- **Data persistence**: Uses SQLite with optimistic revision control. Player choices are retained across server restarts.
- **Configuration hot reload**: Run `/fancycrests reload` to atomically replace the active configuration and revalidate online players.
- **Built-in permission backend**: Works out of the box without an additional permissions plugin. If LuckPerms is installed, its permission backend can also be used.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.20.1+ |
| Fabric API | 0.161.0+26.2 |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 |
| Java | 25 |

Optional integrations: **LuckPerms** (Fabric) 5.5.85 and **Text Placeholder API** 3.1.0-beta.1+26.2.

Nothing needs to be installed on the client.

## Installation

1. Install Fabric Loader, Fabric API and Fabric Language Kotlin on the server.
2. Put `FancyCrests-<version>.jar` into the `mods/` directory.
3. Start the server once and wait for the default configuration file `config/FancyCrests/config.yaml` to be generated.
4. Edit titles, templates and rules as needed, then run `/fancycrests reload` to apply them.

## Commands

The aliases `/names` and `/customnames` behave exactly like `/fancycrests`; use whichever you prefer.

### Player Commands

| Command | Description |
| --- | --- |
| `menu` | Open the prefix, suffix and nickname menus |
| `equip <prefix\|suffix> <id>` | Equip a title you are eligible to use |
| `unequip <prefix\|suffix> <id>` | Unequip a title |
| `unequip <prefix\|suffix> all` | Unequip all titles in the selected slot |
| `nickname <text>` | Set a nickname (requires `fancycrests.nickname.set`) |
| `nickname reset` | Clear the nickname and show the profile name again |
| `nametag <on\|off>` | Show or hide your own permitted nametag lines |

Click the buttons at the top right of the menu to switch between the prefix, suffix and nickname views. In the title list, click an equipped title to unequip it, or an available title to equip it. In the nickname menu, players can choose an administrator-configured preset or reset their nickname. Manually entering a custom nickname requires the `fancycrests.nickname.set` permission. Note that the vanilla anvil input supports at most 50 characters, including formatting codes. If the configuration allows longer nicknames, use the nickname command instead.

### Administrator Commands

| Command | Description |
| --- | --- |
| `grant <player> <titleId>` | Add a local title ownership record |
| `revoke <player> <titleId>` | Revoke a local title ownership record |
| `inspect <player>` | Show the player's state, ownership and equipped titles |
| `preview <player>` | Resolve the first and second lines without changing state |
| `reload` | Atomically reload the configuration |
| `validate` | Validate the configuration file on disk without applying it |
| `diagnose` | Show channel, permission backend, catalog and storage status |
| `nickname reset <player>` | Reset the specified player's nickname |

Targets can be specified by UUID or exact online player name. Nicknames are not used for player lookups.

## Permissions

| Node | Default |
| --- | --- |
| `fancycrests.menu` | Everyone |
| `fancycrests.equip` | Everyone |
| `fancycrests.nickname.set` | Operators; other players need an explicit grant |
| `fancycrests.nickname.bypass_reserved` | Operators; other players need an explicit grant |
| `fancycrests.admin.grant` | Operators |
| `fancycrests.admin.revoke` | Operators |
| `fancycrests.admin.inspect` | Operators |
| `fancycrests.admin.preview` | Operators |
| `fancycrests.admin.reload` | Operators |
| `fancycrests.admin.validate` | Operators |
| `fancycrests.admin.diagnose` | Operators |
| `fancycrests.admin.nickname_reset` | Operators |

By default, operators also have the custom permission nodes used by title definitions and second-line rule conditions within Fancy Crests. These permissions only apply within the mod; they are not written to LuckPerms or granted to other mods. A player's operator status is determined by the vanilla operator list, including lower-level operators and eligible singleplayer world owners.

Each node is evaluated in this order: explicit deny → explicit allow → operator access to mod nodes → the default listed above. A deny from any backend takes precedence over an allow or operator status. You can use the built-in `permissions.nodes` deny list or set a node to `false` in LuckPerms to disable a specific node for an operator. If an operator's external permission data is still loading, the system waits until it has loaded and any deny rules can be checked before granting access. When an online player's operator status changes, the mod revalidates their equipped titles and display state.

The nickname bypass node skips reserved-name checks and checks for conflicts with other online players' real names. Disabling the nickname permission does not remove an existing nickname. Similarly, because local title ownership is an independent eligibility source, denying a title's permission node does not revoke existing ownership. The console and RCON can run administrator commands but cannot run player-only commands.

## Configuration

`config/FancyCrests/config.yaml` is strictly validated when loaded, and problems are reported with their exact configuration paths. Unknown or misspelled fields are errors. If a reload fails, the previous configuration remains active. If startup loading fails, the most recent valid copy (`config.yaml.last-good`) is used; if neither file is usable, protection mode prevents saved title choices from being changed. Fix the file and restart or use `/fancycrests reload`.

On the first start after upgrading, the previous JSON configuration, its last-good copy and the SQLite database are moved into `config/FancyCrests`, with JSON converted to YAML. Existing files at both the old and new locations prevent migration so neither copy is overwritten. Stop the server and resolve the conflict before starting again.

| Configuration section | Purpose |
| --- | --- |
| `channels` | Independent switches: `headRender`, `chat`, `tabList`, `placeholdersExport`; optional `headRenderTemplate` / `chatTemplate` / `tabListTemplate` override the global first-line template for each channel (unset means inherit the global template; `[]` means the channel has no first line) |
| `equipment` | Per-player `maxPrefixes` / `maxSuffixes` limits (0–10; `0` disables the slot) |
| `nickname` | `maxLength` / `minLength` (1–64), `pattern`, `reservedNames`, `allowSet` |
| `permissions.nodes` | Built-in backend: per-UUID `allow` / `deny` lists (supports wildcards ending in `.*`) |
| `firstLineTemplate` | Ordered segments of type `prefixes` / `name` / `suffixes`; each can set `separator` and optional `separatorStyle` |
| `secondLineRules` | `id`, `priority` (0–1000), `condition` (`dimension` / `permission`), `template`, `refreshTicks` (1–72000, default 20) |
| `nametag.lines` | Up to 255 independently configured head lines, ordered top to bottom |
| `render` | `distanceBlocks` (8–128) and offsets for the first and second lines |
| `titles` | `id`, `slot` (`prefix`/`suffix`), `displayName`, `text` (`&` color codes), `permission`, `category`, `iconItem`, `sortOrder` (0–10000) |

You can add a `presets` list to the `nickname` section to list selectable nicknames in the order they should appear in the menu, for example: `presets: ["&aExplorer", "&bBuilder"]`. Players with `fancycrests.menu` can choose a preset or reset their nickname from the menu even without the custom nickname permission. Presets still undergo nickname length, pattern, reserved-name and player-name checks. Setting `allowSet` to `false` disables both preset selection and custom input, but still allows players to reset their nickname.

Titles use a name tag icon in the menu by default. To change it, set `iconItem` to a registered item ID, such as `minecraft:diamond`. Equipped titles have an enchantment glint in the menu, while unequipped ones do not, making them easy to distinguish.

Numeric fields are checked strictly: `3.0` is accepted as the integer `3`, while `3.9`, `1e5` and values outside the allowed range produce an error with the exact path. If a `dimension` condition has no namespace, the vanilla namespace is added automatically (for example, `overworld` → `minecraft:overworld`). Nicknames also cannot begin or end with whitespace.

Titles with a `permission` can only be equipped by players who own them locally or are allowed to use the corresponding node by the active permission backend.

To replace the legacy two head lines, add `nametag.lines`. Each line has its own template, visibility through blocks, refresh period in server ticks and permission to be seen by its owner:

```yaml
nametag:
  lines:
    - content: "%fancycrests:prefix% %fancycrests:nickname% %fancycrests:suffix%"
      seeThrough: false
      refreshTicks: 20
      selfVisible: true
    - content: "%condition:example%"
      seeThrough: true
      refreshTicks: 40
      selfVisible: false
```

`seeThrough` and `selfVisible` default to `false`; `refreshTicks` defaults to 20. Each player's own-visibility preference defaults to on and is saved across restarts. Use the eye in the menu or `/fancycrests nametag on|off` to change it. A player sees their own line only when both their preference and that line's `selfVisible` are enabled. Omitting `nametag` preserves the existing two-line rendering; `lines: []` shows no custom head lines. The existing first and second line offsets determine vertical spacing.

## Text Formatting

Title text, nickname input, `separatorStyle` and static template text all support classic color codes. Use the `&` prefix (for example, `&6Gold`, `&lbold`, and `&r` to reset formatting), which is convenient in chat and configuration files. Existing configurations can continue to use the vanilla `§` prefix. Doubling the prefix as `&&` escapes it to a literal `&`, so `R&&D` displays as written. Unrecognized combinations such as `Tom & Jerry` are also preserved as-is.

## Placeholders

Templates can use `%player%`, the placeholders below, named conditions, and placeholders registered by other mods through Fabric Text Placeholder API:

| Placeholder | Value |
| --- | --- |
| `%fancycrests:prefix%` | Equipped prefixes joined with the configured separator; empty when there are none |
| `%fancycrests:suffix%` | Equipped suffixes joined with the configured separator; empty when there are none |
| `%fancycrests:nickname%` | Nickname, or the current profile name if unset |
| `%fancycrests:belowname%` | Resolved administrator second line; empty when no rule matches |

Define reusable conditional placeholders in the YAML file:

```yaml
conditions:
  nickname:
    conditions:
      - "%player%=%player:displayname%"
    true: "%player%"
    false: "~%player:displayname%"
  example:
    conditions: ["%player%=TestPlayer", "permission:example.staff"]
    type: AND
    true: "&bStaff %condition:nickname%"
    false: ""
```

Use `%condition:nickname%` in a template. `type` defaults to `AND` and can also be `OR`. Comparisons support `=`, `==`, `!=`, `<`, `<=`, `>` and `>=`; ordering comparisons require numbers. References to missing or cyclic conditions are configuration errors. Missing external placeholders remain visible as written and do not produce a successful comparison.

## Integrations

- **LuckPerms**: When Fabric LuckPerms is detected, the mod registers the `luckperms` backend after the built-in backend. Explicit allow or deny rules take precedence; undefined nodes fall back to the defaults described above. Permission changes are rechecked on the main thread so titles that are no longer allowed are removed promptly.
- **Text Placeholder API**: When installed, templates can read registered provider placeholders such as `%player:displayname%`, `%player:statistic_raw used netherite_hoe%`, or TAB's Fabric exports. Each provider and its compatible version must actually be installed. Bukkit-only Essentials/PlaceholderAPI expansions are not available on a Fabric server unless an appropriate bridge supplies them. The four Fancy Crests placeholders can also be exported to other mods when `channels.placeholdersExport` is enabled.
- **LuckPerms placeholders**: With Fabric LuckPerms installed, `%luckperms:prefix%`, `%luckperms:suffix%`, `%luckperms:primary_group_name%` and `%luckperms:meta key%` work directly; other tokens require a compatible provider.

## License

[Apache-2.0](LICENSE)
