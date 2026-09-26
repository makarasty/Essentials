Wiki version: Essentials v22 (makarasty/Essentials fork)

Reference for every configuration file the plugin writes. Search for a key with ``Ctrl+F``: headings use the full dotted path of the key. The YAML blocks show each file's defaults without the comments the plugin writes into it.

# Files and reloading

All YAML files live in ``config/mods/Essentials/config/``. A missing file is created with the defaults below on the first start. An old ``configs/`` folder from earlier versions is renamed to ``config/`` automatically.

| File | Read by | Needs |
|:-----|:--------|:------|
| ``config.yaml`` | the core plugin | always loaded |
| ``config_chat.yaml`` | chat module | ``module.chat: true`` |
| ``config_protect.yaml`` | protect module | ``module.protect: true`` |
| ``config_contribution.yaml`` | contribution module | ``module.contribution: true`` |
| ``config_bridge.yaml`` | bridge module | ``module.bridge: true`` |
| ``config_discord.yaml`` | discord module | ``module.discord: true`` |
| ``config_web.yaml`` | web module | ``module.web: true`` |

A module's file can appear even while the module is off, but it is only acted on while the module is on. Modules left out of a modular build (``-PexcludeModules``) never write their file.

On every load the plugin compares the file with the current defaults:
- Unknown keys (a typo, or a key removed from the plugin) are logged and do nothing.
- Missing keys are logged, and the file is rewritten with them added at their default value. The same rewrite happens when the file lacks the plugin's explanatory comments.
- A rewrite drops unknown keys and your own comments. Before it drops anything, the original is copied to ``<file>.yaml.bak``. An existing backup is never overwritten.

Saving ``config.yaml`` while the server runs reloads it straight away. The ``reload`` console command also reloads it, along with ``permission.yaml``. Edits to ``config_*.yaml`` files, to ``module.*`` and to ``plugin.database`` take effect only after a restart.

Groups, permission nodes and per-group chat formats live in ``permission.yaml`` and ``permission_user.yaml`` (in ``config/mods/Essentials/``, not in ``config/``). See [Permission.md](Permission.md).

# config.yaml

```yaml
plugin:
  lang: "en"
  serverId: ""
  autoUpdate: true
  database:
    url: "h2:./config/mods/Essentials/data/database"
    username: "sa"
    password: ""
feature:
  afk:
    enabled: false
    time: 300
    server: ""
  vote:
    enabled: true
    enableVotekick: false
    rtv:
      ratio: 0.6
      cooldown: 12
      timeout: 60
  unit:
    enabled: false
    limit: 3000
  motd:
    enabled: false
    time: 600
  pvp:
    autoTeam: false
    spector: false
    rememberTeam: false
  level:
    effect:
      enabled: false
      moving: false
      maxPacketsPerRun: 2000
    levelNotify: false
    display: false
  game:
    wave:
      autoSkip: 1
  blacklist:
    enabled: true
    regex: false
  count: false
  mapVote: false
  description:
    enabled: false
    template: ""
    updateOnChange: true
    interval: 30
  playerData:
    allowWithoutData: true
    loadTimeout: 5
    retryAttempts: 30
  log:
    player: true
    chat: false
    report: true
    block: false
    tap: false
    item: false
    other: true
  permission:
    vanillaAdminGroup: "admin"
  name:
    restoreStored: false
module:
  achievement: false
  bridge: false
  chat: true
  contribution: true
  discord: false
  protect: false
  web: false
command:
  skip:
    enabled: true
    limit: 10
    adminLimit: 100
  rollback:
    enabled: true
    mapBackup: true
    time: 300
    limit: 10
  layoutFix: true
  worldEdit:
    maxRegionSize: 10000
ban:
  useDatabase: false
```

## plugin

### plugin.lang

Default: ``en``<br>
Language of the plugin's console messages. Players get the language they picked with ``/lang``, otherwise their client's language when a translation for it ships, otherwise this one.<br>
Shipped translations: en, ja, ko, uk, zh. The console language changes only after a restart.

### plugin.serverId

Default: empty<br>
Identifier of this server for hub routing. It matters once a hub map is set with ``/hub``: a non-hub server then only admits players the hub sent to it, and it matches them against this value.<br>
The hub records the destination as ``<ip>:<port>`` exactly as written in its warp entry, so set this to that same string on every non-hub server. Left empty, every direct join to a non-hub server is refused while a hub is set.<br>
On the first start it also seeds ``data/server-id``, this server's identity in a shared database. After that the file decides, and changing this key does not change the stored identity.

### plugin.autoUpdate

Default: ``true``<br>
On start, check the latest release of this fork on GitHub (``makarasty/Essentials``) and log whether a newer version exists. It never downloads anything.

### plugin.database.url

Default: ``h2:./config/mods/Essentials/data/database``<br>
Where player data is stored.
- ``postgresql://host:port/database``, ``mysql://host:port/database`` or ``mariadb://host:port/database`` connect to that server. The port can be left out (5432 for PostgreSQL, 3306 for MySQL and MariaDB).
- Anything else uses the embedded H2 database. Its location is fixed at ``config/mods/Essentials/data/database``, whatever path follows ``h2:``.

Several servers pointed at the same PostgreSQL, MySQL or MariaDB database share player data. Takes effect after a restart.

```yaml
database:
  url: "postgresql://192.168.0.2:5432/essentials"
  username: "essentials"
  password: "secret"
```

### plugin.database.username

Default: ``sa``<br>
Database user. Ignored for the embedded H2 database.

### plugin.database.password

Default: empty<br>
Database password. Ignored for the embedded H2 database.

## feature.afk

### feature.afk.enabled

Default: ``false``<br>
Kick a player, or move them to ``feature.afk.server``, once they have been idle for ``feature.afk.time`` seconds. A player counts as idle while their unit neither moves nor mines and their cursor stays put. Players with the ``afk.admin`` permission are never affected.

### feature.afk.time

Default: ``300``<br>
Seconds of inactivity before a player counts as AFK.

### feature.afk.server

Default: empty<br>
Server that AFK players are sent to instead of being kicked, as ``host`` or ``host:port`` (6567 when no port is given). Empty kicks them. A value with an unusable port is logged and also kicks.

## feature.vote

### feature.vote.enabled

Default: ``true``<br>
The plugin's vote command (kick, map, gg, skip, back, random). Because vanilla already owns ``/vote``, the plugin's version is registered as ``/evote``.<br>
In this fork, ``false`` removes both ``/evote`` and vanilla's ``/vote``, so the server has no vote command at all.

### feature.vote.enableVotekick

Default: ``false``<br>
The plugin's ``/votekick``, registered as ``/evotekick``.<br>
In this fork, ``false`` (the default) removes vanilla's ``/votekick`` as well, and players get "Unknown command" for it.

### feature.vote.rtv.ratio

Default: ``0.6``<br>
Share of the online players that must use ``/rtv`` before the server moves on to the next map (0.6 = 60%, rounded up, at least one vote).

### feature.vote.rtv.cooldown

Default: ``12``<br>
Seconds a player has to wait between two ``/rtv`` uses.

### feature.vote.rtv.timeout

Default: ``60``<br>
Seconds after the first vote before an unfinished ``/rtv`` vote expires.

## feature.unit

### feature.unit.enabled

Default: ``false``<br>
Turn the unit cap on.

### feature.unit.limit

Default: ``3000``<br>
Maximum number of live units on the server. A unit created above it, wave units included, is killed at once and players are told the cap was reached.

## feature.motd

### feature.motd.enabled

Default: ``false``<br>
Send one line of ``messages/<language>.txt`` (in ``config/mods/Essentials/``) to every player at a regular interval, cycling through the lines. Players whose language has no file get ``messages/en.txt``.<br>
This is not the join message: ``motd/<language>.txt`` is shown on join whether this is on or off.

### feature.motd.time

Default: ``600``<br>
Seconds between two messages. Checked once a minute, so values below 60 behave like 60.

## feature.pvp

### feature.pvp.autoTeam

Default: ``false``<br>
On PvP maps, assign each player a team by player count and the players' PvP win rates, both when the map loads and when a player joins. Players on the derelict team cannot build while this is on.

### feature.pvp.spector

Default: ``false``<br>
On PvP maps, move a player to the derelict (spectator) team as soon as their team loses its last core, until the next map. Players with the ``pvp.spector`` permission are put on the spectator team from the start.

### feature.pvp.rememberTeam

Default: ``false``<br>
Put a player who rejoins during the same PvP match back on the team they had. ``false`` lets them be assigned again.

## feature.level

### feature.level.effect.enabled

Default: ``false``<br>
Level-based visual effects around players. Each player can still turn them off for themselves with ``/effect``. Costs CPU and network traffic.

### feature.level.effect.moving

Default: ``false``<br>
When ``true``, effects are only sent to players whose unit is moving.

### feature.level.effect.maxPacketsPerRun

Default: ``2000``<br>
Maximum number of effect packets one pass may send (20 passes per second). Effects that do not fit are sent by the following passes, not dropped. Below the ceiling nothing changes.

### feature.level.levelNotify

Default: ``false``<br>
At game over, tell each player the EXP they earned and their level.

### feature.level.display

Default: ``false``<br>
Show each player's current EXP and the EXP needed for the next level on their screen, refreshed every second.

## feature.game

### feature.game.wave.autoSkip

Default: ``1``<br>
Number of waves that spawn each time a wave starts. ``1`` is normal play, ``2`` spawns two waves at a time, and so on. High values put a heavy load on the server.

## feature.blacklist

Player name blacklist, checked when a player connects. The names come from the ``blacklistedNames`` list in the plugin data stored in the database. No command edits that list.

### feature.blacklist.enabled

Default: ``true``<br>
Kick players whose name matches an entry of the name blacklist. With an empty list nothing is kicked.

### feature.blacklist.regex

Default: ``false``<br>
Treat each entry as a regular expression that must match the whole name. ``false`` kicks any name that contains the entry as plain text.

## feature (single keys)

### feature.count

Default: ``false``<br>
While the current map has warp entries, the player count this server reports to the server list becomes its own players plus the players on every server those warps point to. Useful on lobby servers.

### feature.mapVote

Default: ``false``<br>
At game over, after a map that ran for at least 5 minutes, ask each player who has not rated that map yet to rate it in a menu (difficulty, then rating).

## feature.description

Live values in the server description shown in the server list.

### feature.description.enabled

Default: ``false``<br>
Let the plugin own the server description and keep its placeholders up to date.<br>
Placeholders: ``{players}`` ``{playerLimit}`` ``{wave}`` ``{map}`` ``{mode}`` ``{playTime}`` ``{matchTime}`` ``{uptime}`` ``{peace}``.<br>
``{playTime}`` counts from the map load. ``{matchTime}`` counts from the moment the map is actually played; on PvP that means two teams each with a core and a player. ``{wave}`` is empty on maps without waves. ``{peace}`` is the remaining PvP peace time, filled only by the protect module.

### feature.description.template

Default: empty<br>
The description text with placeholders. When empty, the text given with the ``config desc`` console command is used as the template. When set, this key wins.<br>
Vanilla clients cut the description at 100 characters in the server list, and colour tags count too.

```yaml
description:
  enabled: true
  template: "{players}/{playerLimit} on {map} - wave {wave}"
```

### feature.description.updateOnChange

Default: ``true``<br>
Re-render as soon as players join or leave, the wave changes, a map loads or the peace timer ticks.

### feature.description.interval

Default: ``30``<br>
Also re-render every N seconds. ``0`` re-renders only on changes.

## feature.playerData

What happens when a player's data cannot be loaded from the database.

### feature.playerData.allowWithoutData

Default: ``true``<br>
When the data could not be loaded, the player still joins with temporary data and the default permission group, and can build and use commands. The real data replaces it in the background once the database answers.<br>
Set ``false`` on servers where only registered players may build: those players then cannot act until their data loads.

### feature.playerData.loadTimeout

Default: ``5``<br>
Seconds to wait for the player data to load before falling back.

### feature.playerData.retryAttempts

Default: ``30``<br>
How many times the background reload tries again, once every 10 seconds, before giving up. The default covers about 5 minutes.

## feature.log

Which events are written to the log files in ``config/mods/Essentials/log/``. Writing happens on a background thread. The bot commands of ServerAdminer that read a log need its type turned on.

### feature.log.player

Default: ``true``<br>
Joins, leaves, kicks and bans, written to ``log/Player.log``.

### feature.log.chat

Default: ``false``<br>
Chat messages, written to ``log/Chat.log``.

### feature.log.report

Default: ``true``<br>
Player reports, written to ``log/report/``.

### feature.log.block

Default: ``false``<br>
Block place and break records, written to ``log/Block.log``.

### feature.log.tap

Default: ``false``<br>
Block tap records, written to ``log/Tap.log``.

### feature.log.item

Default: ``false``<br>
Item deposits and withdrawals, written to ``log/Deposit.log`` and ``log/WithDraw.log``.

### feature.log.other

Default: ``true``<br>
Everything else, such as web panel actions, written to ``log/Web.log``.

## feature.permission

### feature.permission.vanillaAdminGroup

Default: ``admin``<br>
Group given on join to a player who is an admin in Mindustry's own admin list but is not yet in an admin group. The group is stored in the player's database record, not in ``permission_user.yaml``. Pointing it at a group that is not an admin group removes the player's vanilla admin flag.

## feature.name

### feature.name.restoreStored

Default: ``false``<br>
``true`` forces the name stored in the database back onto the player on join and once a second after that.<br>
``false``: a player keeps the nickname they joined with, and the stored name follows it. A name written into ``permission_user.yaml`` is applied either way.

## module

Switches for the optional modules. Each is read at start, so restart after changing one. A module left out of a modular build stays off whatever its switch says.

### module.achievement

Default: ``false``<br>
Achievements and the ``/achievements`` command. No config file.

### module.bridge

Default: ``false``<br>
Link between servers for ``/broadcast``. Settings in ``config_bridge.yaml``.

### module.chat

Default: ``true``<br>
Chat word blacklist and the per-group chat formats from ``permission.yaml``. Settings in ``config_chat.yaml``.

### module.contribution

Default: ``true``<br>
Per-game contribution score from mining, factory builds, item and power output, damage dealt and lost units, shown by ``/contribution``. Every second the scorer walks all buildings on the map, so the cost grows with the size of the bases, not with the player count. The score is written to the database once per game. Weights in ``config_contribution.yaml``.

### module.discord

Default: ``false``<br>
The ``/discord`` command. Settings in ``config_discord.yaml``.

### module.protect

Default: ``false``<br>
Accounts, join rules and PvP protection. Settings in ``config_protect.yaml``.

### module.web

Default: ``false``<br>
Web server with map, statistics, achievement and login pages. Settings in ``config_web.yaml``.

## command.skip

### command.skip.enabled

Default: ``true``<br>
Not read by the current code: ``/vote skip`` and ``/skip`` work whatever it says.

### command.skip.limit

Default: ``10``<br>
Maximum number of waves one ``/vote skip`` may skip.

### command.skip.adminLimit

Default: ``100``<br>
Maximum number of waves the ``/skip`` command may spawn at once. All of them spawn in one go on the main thread.

## command.rollback

### command.rollback.enabled

Default: ``true``<br>
Record block history, used by ``/log`` and ``/rollback``, and allow the map backups below. With ``false`` nothing is recorded.

### command.rollback.mapBackup

Default: ``true``<br>
Save a map backup (``rollback_<time>.msav`` in the saves folder) every ``command.rollback.time`` seconds. ``/vote back`` restores the newest one. Backups are deleted when a new map loads.

### command.rollback.time

Default: ``300``<br>
Seconds between two map backups. Checked once a minute. Too short and griefing may be saved over, too long and a rollback loses more play.

### command.rollback.limit

Default: ``10``<br>
Number of map backup files kept. Older ones are deleted.

## command (single keys)

### command.layoutFix

Default: ``true``<br>
Run commands typed with a Cyrillic keyboard layout without switching layouts: ``.кем`` and ``/кем`` both run ``/rtv``.

### command.worldEdit.maxRegionSize

Default: ``10000``<br>
Maximum number of tiles ``/ws f``, ``/ws r`` and ``/ws d`` may change in one selection. Every tile is changed on the main thread, so a huge selection stalls the server.

## ban

### ban.useDatabase

Default: ``false``<br>
``true`` checks joining players against the ban list stored in the database. Servers sharing one database then share their bans.<br>
``false`` uses only Mindustry's own ban list. Keep it ``false`` next to the ServerAdminer plugin, so both plugins agree on who is banned.

# config_chat.yaml

```yaml
chatFormat: ""
strict:
  enabled: false
  language: "en-US"
blacklist:
  enabled: false
  regex: false
```

### chatFormat

Default: empty<br>
Not read by the current code. Chat formats are set per group or per player with ``chatFormat`` in ``permission.yaml`` (see [Permission.md](Permission.md)). A player whose group has none gets ``[name]: message``.

### strict.enabled

Default: ``false``<br>
Not implemented. Meant to allow only chat in ``strict.language``.

### strict.language

Default: the JVM's default language tag, for example ``en-US``<br>
Not implemented. Language for ``strict.enabled``.

### blacklist.enabled

Default: ``false``<br>
Block chat messages that contain a word from ``chat_blacklist.txt`` (in ``config/mods/Essentials/``, one entry per line). The file is created when the chat module starts. The same check applies to chat sent from the web panel.

### blacklist.regex

Default: ``false``<br>
Treat each line of ``chat_blacklist.txt`` as a regular expression searched anywhere in the message. An invalid expression is logged and skipped. ``false`` matches plain text.

# config_protect.yaml

```yaml
pvp:
  peace:
    enabled: false
    time: 0
  border:
    enabled: false
  destroyCore: false
account:
  enabled: false
  authType: "None"
  discordURL: ""
protect:
  unbreakableCore: false
  powerDetect: false
rules:
  vpn: false
  foo: false
  mobile: false
  steamOnly: false
  minimalName:
    enabled: false
    length: 0
  strict: false
  blockNewUser: false
```

## pvp

### pvp.peace.enabled

Default: ``false``<br>
At the start of a PvP map, set block and unit damage to 0% for ``pvp.peace.time`` seconds. Players are told when peace ends.

### pvp.peace.time

Default: ``0``<br>
Seconds of peace. ``0`` keeps peace for the whole match.

### pvp.border.enabled

Default: ``false``<br>
Kill every unit that leaves the map area. On PvP servers this stops units attacking from outside the world.

### pvp.destroyCore

Default: ``false``<br>
On PvP maps with core capture on, a destroyed block sets off a blast of the drop zone radius that destroys everything around it, so captured cores are not taken back at once. The current code does this for every destroyed block, not only cores.

## account

### account.enabled

Default: ``false``<br>
Turn the account system on. ``account.authType`` only has an effect while this is ``true``.

### account.authType

Default: ``None``<br>
Available: ``None``, ``Password``, ``Discord`` (the first letter may be lowercase).
- ``None``: players play without an account.
- ``Password``: players register with ``/reg`` and log in with ``/login``. Until they do, they cannot act and are reminded every 20 seconds.
- ``Discord``: players cannot act until their account is linked to Discord. The login prompt comes from the separate essential-discord mod; without it only this block remains.

### account.discordURL

Default: empty<br>
Not read by the current code. The link opened by ``/discord`` is ``url`` in ``config_discord.yaml``.

## protect

### protect.unbreakableCore

Default: ``false``<br>
Keep every core at 100 million health so it cannot be destroyed. Useful on sandbox servers; on wave or attack maps the game may never end.

### protect.powerDetect

Default: ``false``<br>
Not implemented.

## rules

Checks run when a player connects.

### rules.vpn

Default: ``false``<br>
Kick players connecting from a known VPN address. The address list is downloaded from the X4BNet VPN list at start. It may not catch every VPN.

### rules.foo

Default: ``false``<br>
Not implemented. Meant to block the foo's client.

### rules.mobile

Default: ``false``<br>
Kick players on mobile devices. ``false`` lets them join.

### rules.steamOnly

Default: ``false``<br>
Not implemented.

### rules.minimalName.enabled

Default: ``false``<br>
Kick players whose name is shorter than ``rules.minimalName.length``.

### rules.minimalName.length

Default: ``0``<br>
Minimum name length, colour tags included. With ``0`` the check never kicks anyone.

### rules.strict

Default: ``false``<br>
When a player's data loads, set their name back to the one stored in the database.

### rules.blockNewUser

Default: ``false``<br>
Kick every player whose UUID was not in the database when the server started, so only returning players can join. Useful against ban evasion with a new UUID while ``account.authType`` stays ``None``.

# config_contribution.yaml

The contribution module is switched on with ``module.contribution`` in ``config.yaml``. Block and item names are Mindustry content names, for example ``graphite-press`` or ``silicon``.

```yaml
enabled: true
miningPerOre: 1.0
postThresholdMultiplier: 0.1
coreThreshold: 30000
titaniumThreshold: 5000
factoryBuildScore:
  graphite-press: 80
  multi-press: 80
  silicon-smelter: 100
  silicon-crucible: 100
  kiln: 150
  pulverizer: 150
  melter: 190
  separator: 190
  disassembler: 250
  plastanium-compressor: 450
  phase-weaver: 600
  surge-smelter: 800
itemProduceScore:
  graphite: 7
  silicon: 15
  metaglass: 10
  thorium: 17
  plastanium: 30
  phase-fabric: 60
  surge-alloy: 90
titaniumScoreBeforeThreshold: 15
titaniumScoreAfterThreshold: 8
powerScoreRatio: 0.1
resourcePenaltyExempt:
  - "conveyor"
  - "duct"
  - "wall"
  - "turret"
buildPenaltyMultiplier: 1.0
```

### enabled

Default: ``true``<br>
Second switch inside the module. Turning on ``module.contribution`` is enough.

### miningPerOre

Default: ``1.0``<br>
Score per mined ore unit while the team's core holds less than ``coreThreshold`` of that item.

### postThresholdMultiplier

Default: ``0.1``<br>
Multiplier on mining score once the core holds ``coreThreshold`` of the item.

### coreThreshold

Default: ``30000``<br>
Amount of copper or lead in the core above which mining scores less.

### titaniumThreshold

Default: ``5000``<br>
Amount of titanium in the core that switches the titanium score from ``titaniumScoreBeforeThreshold`` to ``titaniumScoreAfterThreshold``.

### factoryBuildScore

Default: see the block above<br>
Points for building the first block of each factory type (block name to points).

### itemProduceScore

Default: see the block above<br>
Points for each produced item (item name to points). Items not listed, such as coal, score nothing. Titanium uses the two keys below.

### titaniumScoreBeforeThreshold

Default: ``15``<br>
Points per titanium produced while the core holds less than ``titaniumThreshold``.

### titaniumScoreAfterThreshold

Default: ``8``<br>
Points per titanium produced after that.

### powerScoreRatio

Default: ``0.1``<br>
Share of the net power production turned into score every second.

### resourcePenaltyExempt

Default: ``conveyor``, ``duct``, ``wall``, ``turret``<br>
Blocks whose name contains one of these texts cost no score to build and lose none when their builder deconstructs them.

### buildPenaltyMultiplier

Default: ``1.0``<br>
Multiplier on a block's resource cost when it is subtracted from the builder's score.

# config_bridge.yaml

Links several servers for ``/broadcast``. The first server that can open ``port`` becomes the host, and every other server connects to it at ``address:port``.

```yaml
address: "127.0.0.1"
port: 34567
sharedSecret: ""
sharing:
  ban: false
  broadcast: false
```

### address

Default: ``127.0.0.1``<br>
Address of the host server that the other servers connect to.

### port

Default: a random port between 10000 and 65535, picked when the file is created<br>
Port of the bridge. Set the same value on every server. If this line goes missing, a new random port is written and the servers stop finding each other.

### sharedSecret

Default: empty<br>
Shared secret that servers use to authenticate to each other. It must be at least 32 bytes (UTF-8) and the same on every server. While it is shorter, the bridge stays off and logs a warning.

### sharing.ban

Default: ``false``<br>
Not read by the current code. To share bans, point the servers at one database and use ``ban.useDatabase`` in ``config.yaml``.

### sharing.broadcast

Default: ``false``<br>
Not read by the current code. ``/broadcast`` reaches every connected server whatever it says.

# config_discord.yaml

```yaml
url: ""
```

### url

Default: empty<br>
Discord invite link that ``/discord`` opens in the player's browser. With an empty value ``/discord`` does nothing.

```yaml
url: "https://discord.gg/invitelink"
```

# config_web.yaml

```yaml
port: 32000
uploadPath: "config/maps"
sessionSecret: ""
secureCookie: true
sessionDuration: 3600
maxFileSize: 10485760
maxImageWidth: 2048
mapRenderServer: "https://api.mindustry-tool.com/api/v4/maps/image"
discordUrl: "https://discord.gg/yourserver"
enableWebSocket: true
```

### port

Default: ``32000``<br>
Port of the web server. Put a reverse proxy such as nginx in front of it to serve it on a real domain.

### uploadPath

Default: ``config/maps``<br>
Folder where maps uploaded through the web page are stored, relative to the server folder.

### sessionSecret

Default: empty, replaced by a generated value on the first start<br>
Secret of at least 32 characters used to encrypt and sign login cookies. When blank, one is generated and written to the file. Changing it logs every open session out.

### secureCookie

Default: ``true``<br>
Only send the login cookie over HTTPS. Set ``false`` when the site is served over plain HTTP, or logins will not stick.

### sessionDuration

Default: ``3600``<br>
Seconds a login stays valid. Must be greater than 0, or the web server does not start.

### maxFileSize

Default: ``10485760`` (10 MB)<br>
Largest map file, in bytes, that can be uploaded.

### maxImageWidth

Default: ``2048``<br>
Largest width, in pixels, that a map image request may ask for.

### mapRenderServer

Default: ``https://api.mindustry-tool.com/api/v4/maps/image``<br>
Service that renders map preview images.

### discordUrl

Default: ``https://discord.gg/yourserver``<br>
Discord invite shown by the web login to players who still have to link their Discord account.

### enableWebSocket

Default: ``true``<br>
Not read by the current code.

# Other files

These live in ``config/mods/Essentials/``, not in ``config/``.

| File | Written by the plugin | Purpose |
|:-----|:----------------------|:--------|
| ``permission.yaml`` | on the first start | Groups and their permission nodes, admin flag and chat format. See [Permission.md](Permission.md). |
| ``permission_user.yaml`` | on the first start (comments only), entries by ``setperm`` | Per-player ``name``, ``group``, ``admin``, ``isAlert``, ``alertMessage`` and ``chatFormat``. Wins over the group stored in the database. |
| ``chat_blacklist.txt`` | when the chat module starts | Words for ``blacklist`` in ``config_chat.yaml``, one per line. |
| ``bannedCommands.txt`` | no, create it yourself | JSON list of command names to remove, for example ``["js", "spawn"]``. |
| ``motd/<language>.txt`` | no | Message shown on join. More than 10 lines opens it in a window. |
| ``messages/<language>.txt`` | no | Lines sent one at a time by ``feature.motd``. |
| ``data/server-id`` | on the first start | This server's identity in a shared database, seeded from ``plugin.serverId``. |
