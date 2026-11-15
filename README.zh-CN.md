<img src="src/main/resources/assets/fancycrests/icon.png" align="right" width="128px"/>

# Fancy Crests（Custom Names）

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62B47A)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-DBB69D)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-ED8B00)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)

简体中文 | [English](README.md)

这是一个面向服务端的 [Fabric](https://fabricmc.net/) 模组，客户端无需安装任何内容。它能为玩家提供丰富的自定义称号体验：支持限制数量并自由排序的前后缀、可自定义或从预设挑选的昵称，以及由管理员灵活配置的第二行文本。

> [!CAUTION]
> 本项目在 **GPT-6-Astra**、**DeepSeek-V4.1-Flash-1017** 和 **GLM-5.3** 的协助下完成开发。
> 本模组最初面向作者自己的服务器需求设计，使用前请自行评估并承担风险。

## 功能

- **前缀和后缀**：每位玩家都可以同时装备多个称号，展示顺序与装备顺序一致；每个槽位的数量上限均可按需配置。
- **昵称系统**：支持校验昵称长度、允许字符和保留名称，也可以为特定玩家配置绕过校验的权限节点。
- **管理员控制的第二行**：静态文本可以与内置占位符自由组合；规则支持按优先级和目标条件匹配，并按设定的游戏刻间隔刷新。
- **玩家可控制的第一行**：支持调整 `prefixes`、`name`、`suffixes` 片段的排列顺序，以及它们之间的分隔符。
- **专为原版客户端渲染**：每个目标都会对应一组虚拟 `text_display` 实体（可选包含第二行实体），并通过每位玩家自己的客户端虚拟队伍发送数据，不会在服务端创建或修改任何真实的记分板队伍。
- **原生 GUI 菜单**：玩家可以直接在原版容器菜单中浏览、预览并装备称号，上手直观。
- **数据持久化**：底层使用带乐观版本控制的 SQLite 存储数据，服务器重启后玩家的选择依然完整保留。
- **配置热重载**：执行 `/fancycrests reload` 即可原子替换当前配置，并自动重新校验在线玩家的状态。
- **内置权限后端**：无需额外安装权限插件即可开箱即用；如果服务器装有 LuckPerms，也可以直接选用其权限后端。

## 环境要求

| 组件                   | 版本                 |
| ---------------------- | -------------------- |
| Minecraft              | 26.2                 |
| Fabric Loader          | 0.20.1+              |
| Fabric API             | 0.161.0+26.2         |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 |
| Java                   | 25                   |

可选：**LuckPerms**（Fabric）5.5.85、**Text Placeholder API** 3.1.0-beta.1+26.2。

客户端无需安装任何内容。

## 安装

1. 在服务端安装 Fabric Loader、Fabric API 和 Fabric Language Kotlin。
2. 将 `FancyCrests-<version>.jar` 放入 `mods/` 目录。
3. 首次启动服务器，等待生成默认配置文件 `config/fancycrests.json`。
4. 按需编辑称号、模板和规则，然后执行 `/fancycrests reload` 使其生效。

## 命令

别名 `/names` 和 `/customnames` 与 `/fancycrests` 的行为完全一致，按习惯选用即可。

### 玩家命令

| 命令                            | 说明                                        |
| ------------------------------- | ------------------------------------------- |
| `menu`                          | 打开前缀、后缀和昵称菜单                    |
| `equip <prefix\|suffix> <id>`   | 装备自己有资格使用的称号                    |
| `unequip <prefix\|suffix> <id>` | 卸下一个称号                                |
| `unequip <prefix\|suffix> all`  | 卸下对应槽位的所有称号                      |
| `nickname <text>`               | 设置昵称（需要 `fancycrests.nickname.set`） |
| `nickname reset`                | 清除昵称并恢复显示档案名称                  |

点击菜单右上角的按钮，即可在前缀、后缀和昵称界面之间切换。在称号列表里，点击已装备的称号可以将其卸下，点击可用称号则会直接装备。进入昵称菜单后，玩家可以从管理员配置好的预设昵称中进行选择，或是重置昵称；如果想手动输入自定义昵称，则需要拥有 `fancycrests.nickname.set` 权限。需要注意，原版铁砧输入最多只支持 50 个字符（格式代码同样计入长度），如果配置允许更长的昵称，请改用昵称命令来输入。

### 管理员命令

| 命令                        | 说明                               |
| --------------------------- | ---------------------------------- |
| `grant <player> <titleId>`  | 添加本地称号所有权记录             |
| `revoke <player> <titleId>` | 撤销本地称号所有权记录             |
| `inspect <player>`          | 显示玩家状态、所有权和已装备称号   |
| `preview <player>`          | 解析第一行和第二行，但不修改状态   |
| `reload`                    | 原子重载配置                       |
| `validate`                  | 校验磁盘上的配置文件，但不应用配置 |
| `diagnose`                  | 查看通道、权限后端、目录和存储状态 |
| `nickname reset <player>`   | 重置指定玩家的昵称                 |

指定目标时可以使用 UUID，也可以使用精确匹配的在线玩家名称。需要注意，查找玩家时不会匹配昵称。

## 权限

| 节点                                   | 默认权限                   |
| -------------------------------------- | -------------------------- |
| `fancycrests.menu`                     | 所有人                     |
| `fancycrests.equip`                    | 所有人                     |
| `fancycrests.nickname.set`             | 管理员；其他玩家需明确授权 |
| `fancycrests.nickname.bypass_reserved` | 管理员；其他玩家需明确授权 |
| `fancycrests.admin.grant`              | 管理员                     |
| `fancycrests.admin.revoke`             | 管理员                     |
| `fancycrests.admin.inspect`            | 管理员                     |
| `fancycrests.admin.preview`            | 管理员                     |
| `fancycrests.admin.reload`             | 管理员                     |
| `fancycrests.admin.validate`           | 管理员                     |
| `fancycrests.admin.diagnose`           | 管理员                     |
| `fancycrests.admin.nickname_reset`     | 管理员                     |

在 Fancy Crests 中，管理员默认还拥有称号定义和第二行规则条件中所使用的自定义权限节点。不过这些权限仅在模组内部生效，不会写入 LuckPerms，也不会授予其他模组。玩家的管理员身份由原版管理员列表决定，包括较低等级的管理员以及符合条件的单人游戏存档房主。

每个权限节点的判定顺序依次为：明确拒绝 → 明确允许 → 管理员对模组节点的访问权 → 上表规定的默认值。无论来自哪个后端，拒绝规则的优先级都高于允许或管理员身份。因此，你可以通过内置 `permissions.nodes` 的拒绝列表，或在 LuckPerms 中将节点设为 `false`，来单独禁用某位管理员的特定节点。如果管理员的外部权限数据仍在加载中，系统会等待数据加载完成并确认没有拒绝规则后，再授予其访问权。在线玩家的管理员身份发生变化后，模组会自动重新校验其已装备的称号与显示状态。

拥有昵称绕过节点后，设置昵称时会跳过保留名称检查，以及与其他在线玩家真实名称冲突的检查。禁用昵称权限并不会删除玩家已有的昵称；同样地，由于称号的本地所有权记录是独立的资格来源，拒绝某个称号的权限节点也不会撤销已有所有权。另外，控制台和 RCON 可以执行管理员命令，但无法执行仅限玩家使用的命令。

## 配置

加载 `config/fancycrests.json` 时会执行严格校验，一旦发现问题就会报告精确的 JSON 路径。所有未知字段或拼写错误都会被视为**错误**，绝不静默忽略（过去像 `permisson`、`titels` 这类拼写失误，或者片段中漏写 `type`，曾导致配置行为悄然改变而不易排查）。如果**重载**失败，服务端会继续沿用上一次生效的配置；如果**启动**时加载失败，则会回退到最近一次有效的备份副本（`fancycrests.json.last-good`，每次完整校验通过后都会自动重写）。万一主文件和备份副本都不可用，服务器将进入**保护模式**：此时会拒绝变更称号状态，也不会根据未知目录强行卸下已保存的装备，以免错误配置清掉玩家现有的选择。修复配置文件后重启服务器，或执行 `/fancycrests reload` 即可退出保护模式。

| 配置区段            | 用途                                                         |
| ------------------- | ------------------------------------------------------------ |
| `channels`          | 独立开关：`headRender`、`chat`、`tabList`、`placeholdersExport`；可选的 `headRenderTemplate` / `chatTemplate` / `tabListTemplate` 可为各通道覆盖全局第一行模板（未设置表示继承全局模板；`[]` 表示该通道不显示第一行） |
| `equipment`         | 每位玩家的 `maxPrefixes` / `maxSuffixes` 上限（0–10；`0` 会禁用对应槽位） |
| `nickname`          | `maxLength` / `minLength`（1–64）、`pattern`、`reservedNames`、`allowSet` |
| `permissions.nodes` | 内置后端：按 UUID 设置 `allow` / `deny` 列表（支持以 `.*` 结尾的通配符） |
| `firstLineTemplate` | 按顺序排列的片段，类型为 `prefixes` / `name` / `suffixes`；每段可设 `separator` 和可选的 `separatorStyle` |
| `secondLineRules`   | `id`、`priority`（0–1000）、`condition`（`dimension` / `permission`）、`template`、`refreshTicks`（1–72000，默认 20） |
| `render`            | `distanceBlocks`（8–128）以及第一行和第二行的偏移量          |
| `titles`            | `id`、`slot`（`prefix`/`suffix`）、`displayName`、`text`（`&` 颜色代码）、`permission`、`category`、`iconItem`、`sortOrder`（0–10000） |

你可以在 `nickname` 区段中添加 `presets` 数组，按菜单中希望展示的顺序列出可选昵称，例如：`"presets": ["&aExplorer", "&bBuilder"]`。拥有 `fancycrests.menu` 权限的玩家即使没有自定义昵称权限，也能在菜单里直接选择预设或重置昵称。不过预设依然需要通过昵称长度、格式、保留名称和玩家重名检查；如果将 `allowSet` 设为 `false`，则会同时禁用预设选择和自定义输入，但依然允许玩家重置昵称。

称号在菜单中默认使用命名牌图标。如果想更换图标，只需把 `iconItem` 设为已注册的物品 ID（例如 `minecraft:diamond`）。已装备的称号在菜单中会带有附魔光效，未装备的则没有，方便玩家一眼区分。

所有数值字段都会根据 JSON 原文进行精确解析：`3.0` 会作为整数 `3` 正常接受，而 `3.9`、`1e5` 以及超出字段允许范围的值都会直接报错，并指出精确路径。`dimension` 条件若未指定命名空间，会自动补全原版命名空间（如 `overworld` → `minecraft:overworld`）；此外，昵称的首尾不能包含空白字符。

带有 `permission` 的称号，仅能由在本地拥有该称号，或被当前权限后端允许使用对应节点的玩家装备。

## 文本格式

称号文本、昵称输入、`separatorStyle` 和模板中的静态文本均支持经典颜色代码。你可以直接使用 `&` 前缀（例如 `&6Gold`、`&lbold`，`&r` 用于重置格式），在聊天框和配置文件里输入都很方便；现有配置继续使用原版 `§` 前缀也同样支持。双写前缀 `&&` 可转义为字面量 `&`，因此使用 `R&&D` 即可原样显示出字面内容；而像 `Tom & Jerry` 这样无法识别的组合，也会直接保留原文。

## 占位符

第二行模板和 Text Placeholder API 导出共用同一占位符注册表：

| 占位符                    | 值                                           |
| ------------------------- | -------------------------------------------- |
| `%fancycrests:prefix%`    | 已装备前缀按配置的分隔符连接；没有前缀时为空 |
| `%fancycrests:suffix%`    | 已装备后缀按配置的分隔符连接；没有后缀时为空 |
| `%fancycrests:nickname%`  | 昵称；未设置时为当前档案名称                 |
| `%fancycrests:belowname%` | 已解析的管理员第二行文本；没有匹配规则时为空 |

## 集成

- **LuckPerms**：检测到 Fabric 版 LuckPerms 时，模组会在内置后端之后注册 `luckperms` 后端。明确的允许或拒绝规则会优先生效，未定义的节点则会回退到上文规定的默认值。当权限发生变更时，系统会在主线程重新进行检查，确保被撤销资格的称号能够及时移除。
- **Text Placeholder API**：检测到该模组时，会自动导出上述四个占位符，供聊天、TAB 列表或其他兼容插件/模组调用。即便没有安装它，Fancy Crests 的各项功能依然可以正常运行。

## 许可证

[Apache-2.0](LICENSE)