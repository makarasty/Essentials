# Essentials (makarasty fork)
[![CI](https://github.com/makarasty/Essentials/actions/workflows/ci.yml/badge.svg)](https://github.com/makarasty/Essentials/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/makarasty/Essentials?style=flat-square)](https://github.com/makarasty/Essentials/releases/latest)
![Downloads](https://img.shields.io/github/downloads/makarasty/Essentials/total?style=flat-square)
![Downloads of the latest release](https://img.shields.io/github/downloads/makarasty/Essentials/latest/total?style=flat-square)

A Mindustry server plugin that adds moderation, accounts, voting, statistics, achievements, server
hubs and a lot of admin tooling. This is a fork of [Kieaer/Essentials](https://github.com/Kieaer/Essentials)
run on a group of servers that share one database. It follows upstream closely and merges it
regularly; everything listed under [What this fork changes](#what-this-fork-changes) is on top of it.

Currently based on upstream **v22** for **Mindustry v160.4** (8.0).

## Requirements

- **Java 17 or newer.**
- A Mindustry dedicated server on **v160.4**.
- Essentials does not get along with other plugins that ship their own copy of the same libraries
  (Kotlin, Ktor, Exposed, Flyway, SLF4J and so on). Mindustry loads plugins into a shared classloader, so
  two copies of one package clash. That is a limitation of Mindustry, not something either plugin can fix.

## Installation

Download `Essential-all.jar` from the [latest release](https://github.com/makarasty/Essentials/releases/latest)
and put it into `<server>/config/mods`. On the first start the plugin writes its configuration to
`<server>/config/mods/Essentials/`. The [dev build](https://github.com/makarasty/Essentials/releases/tag/dev)
is the newest commit on `main`, rebuilt on every push; you can also build the jar yourself (see
[Building](#building)).

- [Config reference](.github/Config.md): every key of `config.yaml` and the module config files.
- [Permissions](.github/Permission.md): groups, nodes and `permission_user.yaml`.
- [Translating](TRANSLATING.md): adding or fixing a language.

## What this fork changes

### Commands

- **Vanilla commands are never overwritten.** When a plugin client command name is already taken on the
  server, the plugin registers its own version with an `e` prefix. On a vanilla server that is `/ehelp`,
  `/et`, `/evote` and `/evotekick`, and Mindustry's `/help`, `/t`, `/vote` and `/votekick` keep working.
  Permission nodes and translation keys keep the original name (`vote`, not `evote`).
  Switching `feature.vote.enabled` or `feature.vote.enableVotekick` off removes both the plugin's command
  and the vanilla one, so the server has no such vote at all. `enableVotekick` is off by default.
- **`/rtv`** (rock the vote): once `feature.vote.rtv.ratio` of the online players voted, the server moves
  on to the next map.
- **`/votemap <id>`**: shortcut for a map vote on the map with that ID from `/maps`.
- **`/lang [language/auto]`**: a player picks the language the server writes to them in, and the choice is
  stored in the database instead of being overwritten by the client's locale on the next join.
- **Cyrillic keyboard layout**: `.кем` and `/кем` both run `/rtv`. Toggle with `command.layoutFix`.
- **One player lookup everywhere.** A player argument is matched by the `#ID` from `/players`, the same
  number without `#`, a UUID, an exact name, a unique prefix and finally a unique substring. When several
  players still match, the command lists them with their IDs instead of picking one, so `/kick max` no
  longer hits `maxim`.

### Moderation

- **`/undo`**: every ban, kick, mute, build restriction, permission or team change an admin makes goes onto
  a per-admin stack of the last five actions. A follow-up menu offers `Undo` (and `Ban` after a kick) for
  a minute, and `/undo [n]` and `/undo list` work in chat and on the console. Entries are reverted by UUID,
  so they still work after the target has left.
- **`tempban` is a real vanilla ban** with an expiry. A scheduler lifts it once the time has passed.
  `permaban` makes an existing ban permanent without unbanning first.
- **`/rollback`** records who acted and what was configured, and matches history by account (UUID)
  rather than by name. World history is only recorded while
  `command.rollback.enabled` is true, and the periodic map backup has its own switch,
  `command.rollback.mapBackup`.
- **Admin status is synced with the vanilla admin list** in both directions. A vanilla admin joins into
  `feature.permission.vanillaAdminGroup`, and `setperm` writes the vanilla flag back.
- New console commands: `perm` (effective group of a player), `permaban`, `delete` (player data and
  achievements), `mergeplayer`, `reloadplayer` and `undo`.

### Player data and the shared database

- **Several servers, one database.** Rows that mean "on this server" carry the server's identity, kept in
  `config/mods/Essentials/data/server-id` (seeded from `plugin.serverId` when set). A server that crashed
  clears only its own stale "connected" flags on the next boot, not the players online elsewhere. Copy
  that file if you clone an install; delete it to give the server a new identity.
- **The database being down does not lock players out.** With `feature.playerData.allowWithoutData` on,
  a player joins with temporary in-memory data and the default group, and the real data is swapped in
  in the background once the database answers (`retryAttempts`, `loadTimeout`). Turn it off on servers
  where only registered players may build.
- **Achievement progress is persisted** (`players.status_data`), so counters no longer restart on every
  leave, restart or server change.
- **Duplicate accounts are merged, not deleted.** Upstream's Flyway migration V8 puts a unique index on
  `account_id` and deletes every duplicate row but the newest. Before it runs, this fork folds the older
  rows into the newest one with the same logic as `mergeplayer`: stats are summed and achievements move
  over.
- **Legacy upgrades are tolerant.** Databases from before v5 are upgraded by the V4/V5 scripts statement by
  statement: a non-critical statement that fails is logged and skipped instead of abandoning the upgrade,
  and the boot log says exactly which statements were skipped and whether the upgrade finished. Flyway
  then applies V6 onwards. If the legacy upgrade did not finish, Flyway is not run over the half-migrated
  schema.
- Missing columns are added at boot one table at a time, and schema differences the plugin will not repair
  on a live table (indexes, constraints) are reported in the log instead of silently ignored.

### Server description

`feature.description` keeps live values in the server description. Put placeholders such as `{players}`,
`{playerLimit}`, `{map}`, `{mode}`, `{wave}`, `{playTime}`, `{matchTime}`, `{uptime}` or `{peace}` into
`config desc` (or `feature.description.template`) and the plugin keeps them up to date on every change
and/or every N seconds.

### Logs

`feature.log` has per-type switches (`player`, `chat`, `report`, `block`, `tap`, `item`,
`other`), and log files are written on a background thread instead of the game thread.

### Stability

The fork carries a long list of fixes on top of upstream: race conditions between the game thread and the
database coroutines, listeners and menus that leaked on every map, config reloads that did not apply,
effect packet floods (`feature.level.effect.maxPacketsPerRun`), and many more. `git log` has the details; most
commits explain what was broken and why.

## Building

Gradle and a JDK 17+ are all that is needed; the wrapper downloads the rest.

```shell
# Fat jar with every module: Essential/build/libs/Essential-all.jar
./gradlew :Essential:shadowJar

# Minified jar, the one upstream releases
./gradlew :Essential:proguardJar

# Tests (boots a headless Mindustry server; takes a few minutes)
./gradlew :Essential:test
```

### Modular builds

`-PexcludeModules` builds a jar without optional services. Their Kotlin sources, generated code,
resources and module-only libraries are left out of both `shadowJar` and `proguardJar`.

```shell
# Without the web service and its assets/libraries
./gradlew :Essential:shadowJar -PexcludeModules=web

# Smaller jar without the services a typical server here does not use
./gradlew :Essential:shadowJar -PexcludeModules=web,discord,achievements,bridge

# A server on a shared database that must not run or ship Flyway migrations
./gradlew :Essential:proguardJar -PexcludeModules=migration

# Every optional service left out
./gradlew :Essential:proguardJar -PexcludeModules=services
```

Module names: `achievements`, `bridge`, `chat`, `contribution`, `discord`, `effect`, `migration`,
`protect`, `vote`, `web`. `achievement` and `flyway` are accepted as aliases, and `services` (also
`core/services`) expands to all of them. A build without `migration` keeps database access but skips
Flyway, drops the PostgreSQL/MariaDB JDBC drivers, and cannot upgrade a pre-v5 database.

`:Essential:verifyModuleExclusions` inspects a `shadowJar` build and fails if anything that was meant to
be left out is still inside. For a modular build, `test` runs a headless boot smoke test against exactly
the sources packaged in that jar; the full suite targets the full distribution.

Most modules can also be switched off in `config.yaml` under `module:` without rebuilding.

### Releasing

Push a tag that starts with `v`. CI writes the tag into `plugin.json` (the update check compares the two),
runs the tests and publishes a release with `Essential-all.jar` and notes generated from the commits.
Every push to `main` replaces the jar of the `dev` prerelease.

```shell
git tag v22-fork.1 && git push origin v22-fork.1
```

### Deploying to a server folder

```shell
python scripts/deploy.py --server /path/to/mindustry-server
```

The script backs up the installed jar and `config/mods/Essentials` into
`<server>/essentials-backup-<timestamp>/`, copies the new jar, and adds the `rtv` and `votemap`
permissions to the `user` group of `permission.yaml` if they are missing. `--jar` deploys a jar from
another path (default: `Essential/build/libs/Essential-all.jar`).

## Working next to WebSocketAdmin

The WebSocketAdmin plugin bridges a Discord bot to the same servers. Mindustry's own `Administration`
state stays the source of truth for bans and admin status, and this plugin keeps its side consistent with
it in both directions.

- Keep `ban.useDatabase: false`. The plugin then reads the vanilla ban list, so both plugins agree on who
  is banned. With `true` the database list wins and the bot's ban view drifts.
- A player who is admin in the vanilla admin list joins into the group named by
  `feature.permission.vanillaAdminGroup` (default `admin`), and `setperm` writes the vanilla admin flag
  back. A vanilla admin flag this plugin did not set is never cleared on join.
- `tempban` creates a real vanilla ban, so the bot sees it in its ban list.

WebSocketAdmin calls these console commands when this plugin is loaded:

| Bot action | Console command |
|:---|:---|
| Grant admin | `setperm <uuid> admin` |
| Revoke admin | `setperm <uuid> user` |
| Ban with a duration | `tempban <uuid> <minutes> <reason>` |
| Unban | `unban <uuid>` |

The bot's `/logs` command reads the log files this plugin writes, so the `feature.log` types it needs are
`player` (joins, leaves, kicks, bans), `chat`, `block`, `tap` and `item` (both the deposit and the
withdraw file). Its `server` log is the Mindustry server log and does not depend on `feature.log`.

## Commands

Commands from a module exist only while that module is built in and enabled. Who may run what is set in
`permission.yaml`; see [Permissions](.github/Permission.md).

### Client commands

| Command | Parameters | Module | Description |
|:--|:--|:--|:--|
| `/ach` | [page] | achievements | Show your achievements |
| `/broadcast` | &lt;message...&gt; | bridge | Send message to all connected servers |
| `/changemap` | &lt;name&gt; [gamemode] | core | Change the world or game mode immediately. |
| `/changename` | &lt;target&gt; &lt;new_name&gt; | core | Change player name |
| `/changepw` | &lt;new_password&gt; &lt;password_repeat&gt; | core | Change account password. |
| `/chars` | &lt;text...&gt; | core | Make pixel texts on ground. |
| `/chat` | &lt;on/off&gt; | core | Mute all players without admins |
| `/color` |  | core | Enable color nickname |
| `/contribution` | [player...] | contribution | Show average contribution score |
| `/discord` |  | discord | Open server discord url |
| `/dps` |  | core | Create damage per seconds meter block |
| `/effect` | &lt;on/off/level&gt; [color] | core | Turn other players' effects on or off, or set effects and colors for each level. |
| `/exp` | &lt;set/hide/add/remove&gt; [values/player] [player] | core | Edit account exp values |
| `/fillitems` | [team] | core | Fill the core with items. |
| `/fuck` | [command] | core | Corrects and executes a command with typos |
| `/gg` | [team] | core | Make game over immediately. |
| `/god` | [player] | core | Set max player health |
| `/ehelp` | [page] | core | Show command lists |
| `/hub` | &lt;parameter&gt; [ip] [parameters...] | core | Create a server to server point. |
| `/info` | [player...] | core | Show player info |
| `/js` | [code...] | core | Execute JavaScript code |
| `/kickall` |  | core | Kick all players without admins. |
| `/kill` | [player] | core | Kill player's unit. |
| `/killall` | [team] | core | Kill all enemy units |
| `/killunit` | &lt;name&gt; [amount] [team] | core | Destroy specific units |
| `/lang` | [language/auto] | core | Choose the language this server writes to you in. |
| `/log` |  | core | Enable block history view mode |
| `/login` | &lt;id&gt; &lt;password&gt; | protect | Log-in to account. |
| `/maps` | [page] | core | Show server map lists |
| `/me` | &lt;text...&gt; | chat | Chat with special prefix |
| `/meme` | &lt;type&gt; | core | Enjoy mindustry meme features! |
| `/motd` |  | core | Show server's message of the day |
| `/mute` | &lt;player&gt; | core | Mute player |
| `/nextmap` | [map] | core | Set the next map to move to after game over |
| `/pause` |  | core | Pause or Unpause map |
| `/players` | [page] | core | Show current players list |
| `/pm` | &lt;player&gt; &lt;message...&gt; | chat | Send a private message |
| `/ranking` | &lt;time/exp/attack/place/break/pvp&gt; [page] | core | Show player ranking |
| `/reg` | &lt;id&gt; &lt;password&gt; &lt;password_repeat&gt; | protect | Register account |
| `/report` | &lt;player&gt; &lt;reason...&gt; | protect | Report a player |
| `/rollback` | &lt;player&gt; | core | Undo all actions taken by the player. |
| `/rtv` |  | core | Vote to move on to the next map |
| `/setfeedbackprovider` | &lt;player&gt; | achievements | Set the FeedbackProvider achievement for a player |
| `/setitem` | &lt;item&gt; &lt;amount&gt; [team] | core | Set item to team core |
| `/setmapprovider` | &lt;player&gt; | achievements | Set the MapProvider achievement for a player |
| `/setperm` | &lt;player&gt; &lt;group&gt; | core | Set the player's permission group. |
| `/skip` | &lt;wave&gt; | core | Start n wave immediately |
| `/spawn` | &lt;unit/block&gt; &lt;name&gt; [amount(rotate)/block_team] [unit_team] | core | Spawn units or block at the player's current location. |
| `/status` |  | core | Show current server status |
| `/strict` | &lt;player&gt; | core | Set whether the target player can build or not. |
| `/et` | &lt;message...&gt; | core | Send a message only to your teammates. |
| `/team` | &lt;team&gt; [name] | core | Set player team |
| `/time` |  | core | Show current server time |
| `/tp` | &lt;player&gt; | core | Teleport to other players |
| `/track` |  | core | Display the mouse positions of players. |
| `/unban` | &lt;player&gt; | core | Unban player |
| `/undo` | [id/list] | core | Undo the last administrative action. |
| `/unmute` | &lt;player&gt; | core | Unmute player |
| `/url` | &lt;command&gt; | core | Opens a URL contained in a specific command. |
| `/evote` | &lt;kick/map/gg/skip/back/random/draw&gt; [player/amount/world] [reason] | core | Start voting |
| `/evotekick` | &lt;player&gt; | core | Start kick voting |
| `/votemap` | &lt;id&gt; | core | Start a vote to change to the map with the given ID (see /maps) |
| `/weather` | &lt;weather&gt; &lt;seconds&gt; | core | Adds a weather effect to the map. |
| `/ws` | [args...] | core | WorldEdit selection and block manipulation |

`/ehelp`, `/et`, `/evote` and `/evotekick` are `help`, `t`, `vote` and `votekick` with the `e` prefix the
plugin adds on a vanilla server; their permission nodes are `help`, `t`, `vote` and `votekick`.

### Server (console) commands

| Command | Parameters | Module | Description |
|:--|:--|:--|:--|
| `chat` | &lt;on/off&gt; | core | Mute all players without admins |
| `debug` | [parameter...] | core | Debug any commands |
| `delete` | &lt;uuid/name/id&gt; | core | Delete player data and achievements from database |
| `gen` |  | core | Generate wiki docs |
| `kickall` |  | core | Kick all players. |
| `kill` | &lt;player&gt; | core | Kill player's unit |
| `killall` | [team] | core | Kill all units |
| `killunit` | &lt;name&gt; [amount] [team] | core | Destroy specific units |
| `mergeplayer` | &lt;from_uuid&gt; &lt;to_uuid&gt; | core | Merge two player accounts (from &rarr; to). |
| `mute` | &lt;player&gt; | core | Mute player |
| `perm` | &lt;player&gt; | core | Show the player's effective permission group. |
| `permaban` | &lt;player&gt; | core | Make an existing ban permanent by clearing its expiry |
| `reload` |  | core | Reload essential plugin configs. |
| `reloadplayer` | &lt;uuid/name&gt; | core | Reload the player data of an online player. |
| `setfeedbackprovider` | &lt;player&gt; | achievements | Set the FeedbackProvider achievement for a player |
| `setmapprovider` | &lt;player&gt; | achievements | Set the MapProvider achievement for a player |
| `setperm` | &lt;player&gt; &lt;group&gt; | core | Set the player's permission group. |
| `strict` | &lt;player&gt; | core | Set whether the target player can build or not. |
| `team` | &lt;team&gt; &lt;name&gt; | core | Set player team |
| `tempban` | &lt;player&gt; &lt;time&gt; [reason...] | core | Ban the player for a certain period of time |
| `unban` | &lt;player&gt; | core | Unban player |
| `undo` | [id/list] | core | Undo the last administrative action. |
| `unmute` | &lt;player&gt; | core | Unmute player |

## License

Upstream's license applies: **Free License**. Copy it, change it, claim it as your own. Upstream is
[Kieaer/Essentials](https://github.com/Kieaer/Essentials) by Gureumi; thanks to them for the plugin this
fork is built on.
