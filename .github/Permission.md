# What's permission in this plugin?
Permissions decide which plugin commands and features each player can use. Every player resolves to one
group, and a group is a list of permission nodes. A player can run a plugin command only if their group
holds that command's node (or `all`).

A permission is decided by the group alone. The admin flag (Mindustry's own admin list, or `admin: true`)
grants no node by itself.

# How to use?

Both files live in `<server>/config/mods/Essentials/`. Apply edits with the console command `reload` or a
restart; editing the files does not reload them.

## permission.yaml: groups

Written from the built-in defaults on the first start that finds it missing, and never updated after that.
A node added in a later build has to be added to this file by hand (only `lang` is allowed for everyone
regardless of the file).

```yaml
admin:                  # group name
  admin: true           # members wear the admin flag; setperm into or out of the group also sets or
                        # clears the player's Mindustry admin
  inheritance: user     # optional; copies every node of that group (and of its own parent, and so on)
  default: false        # optional; see below
  chatFormat: "[scarlet][A][] %player.name[orange] > [white]%chat"   # optional
  permission:
    - kill
    - kill.other
```

- `inheritance` copies nodes only, never `admin`, `chatFormat` or `all`. A group that inherits in a circle is
  cut there with a warning.
- `default: true` names the group a `permission_user.yaml` entry gets when it has no `group:` line. If several
  groups have it, the first one counts; with none, it is `user`. It is also the group of a player whose data
  could not be loaded, unless the account service is running, which picks `user` or `visitor` itself.
- New players get `user`: the row created on first join and an account made with `/reg` both start there.
- A Mindustry admin who is not in an admin group is moved on join into `feature.permission.vanillaAdminGroup`
  in `config.yaml` (default `admin`).
- `chatFormat` variables: `%player.name`, `%player.uuid`, `%player.level`, `%player.exp`, `%player.permission`,
  `%player.playtime`, `%player.blockPlace`, `%player.blockBreak`, `%player.attackClear`, `%player.waveClear`,
  `%player.pvpWin`, `%player.pvpLose`, `%player.pvpEliminated`, `%player.pvpMvp`, `%player.attendance`,
  `%player.language`, `%player.world`, `%player.worldMode`, and `%chat` for the message.

The default groups are `owner` (`all`), `admin` (inherits `user`), `user` and `visitor` (`default: true`).
`user` does not inherit `visitor`.

## permission_user.yaml: single players

Keyed by UUID. An entry here wins over the group stored in the database, on this server only.

```yaml
uuid123:
  name: "asdfg"            # optional; forced display name
  group: "admin"           # optional; the permission.yaml group
  admin: true              # optional; admin flag for this player only
  isAlert: true            # optional; announce when they join
  alertMessage: "Player asdfg has entered the server!"
  chatFormat: "[admin] %player.name[orange] >[white] %chat"   # overrides the group's chatFormat
```

`setperm <player> <group>` (console or in game) changes the group in the database, and also in this file if
the player already has an entry here. `perm <player>` in the console shows the group a player resolves to.

# Command names and the `e` prefix

When a plugin client command name is already taken by vanilla Mindustry, the plugin registers its version
with an `e` prefix instead of replacing vanilla's. On a vanilla server that is:

| Plugin command | Vanilla command kept | Permission node |
|:---------------|:---------------------|:----------------|
| `/ehelp`       | `/help`              | `help`          |
| `/et`          | `/t`                 | `t`             |
| `/evote`       | `/vote`              | `vote`          |
| `/evotekick`   | `/votekick`          | `votekick`      |

The node always keeps the original name. A node like `evote` in permission.yaml grants nothing, and the
server warns about it on start: `'evote' is not a permission node, nobody gets anything from it. Use 'vote'`.

Vanilla commands never check plugin permissions. `/ehelp` only lists the commands whose node the player
holds, vanilla ones included, so granting `a` or `sync` only makes those vanilla commands show up there.

# Available permission nodes

"Default group" is the group that lists the node in the shipped permission.yaml. `admin` also has every
`user` node through inheritance, and `owner` has everything through `all`. *(none)* means only a group with
`all` has it until you add it. Commands of a module that is turned off are not registered at all.

| Command              | Permission node       | Description                                                                                  | Default group  |
|:---------------------|:----------------------|:---------------------------------------------------------------------------------------------|:---------------|
|                      | `all`                 | Every node, including ones no group lists. Not passed on through `inheritance`.              | owner          |
|                      | `admin`               | Needed on top of their own node by `/setmapprovider` and `/setfeedbackprovider`.             | admin          |
|                      | `afk.admin`           | Never treated as AFK, so never kicked or moved for idling.                                   | admin          |
|                      | `chat.admin`          | Can still chat, in game and from the web, while `/chat off` is on.                           | admin          |
|                      | `hub.build`           | Can build on the hub map.                                                                    | *(none)*       |
|                      | `kick.admin`          | Cannot be kicked by `/kickall`, `/evote kick` or `/evotekick`.                               | admin          |
|                      | `pvp.spector`         | Joins PvP games as a spectator when `feature.pvp.spector` is on.                             | admin          |
|                      | `pm.other`            | Meant to show other players' private messages. The check is commented out, so it does nothing today. | admin |
| `/ach`               | `ach`                 | Show your achievements.                                                                      | user           |
| `/broadcast`         | `broadcast`           | Send a message to all connected servers.                                                     | *(none)*       |
| `/changemap`         | `changemap`           | Change the map or game mode immediately.                                                     | admin          |
| `/changename`        | `changename`          | Change a player's name.                                                                      | *(none)*       |
| `/changepw`          | `changepw`            | Change your account password.                                                                | user           |
| `/chars`             | `chars`               | Write pixel text on the ground.                                                              | admin          |
| `/chat`              | `chat`                | Mute or unmute chat for everyone without `chat.admin`.                                       | admin          |
| `/color`             | `color`               | Toggle an animated color nickname.                                                           | admin          |
| `/contribution`      | `contribution`        | Show the average contribution score.                                                         | user           |
| `/discord`           | `discord`             | Open the server's Discord link.                                                              | user           |
| `/dps`               | `dps`                 | Place a damage-per-second meter block.                                                       | admin          |
| `/effect`            | `effect`              | Turn other players' effects on or off, or pick your own level effect and color.              | user           |
| `/exp`               | `exp`                 | Edit account exp values.                                                                     | *(none)*       |
| `/fillitems`         | `fillitems`           | Fill a core with items.                                                                      | admin          |
| `/fuck`              | `fuck`                | Correct a mistyped command and run it.                                                       | *(none)*       |
| `/gg`                | `gg`                  | End the game immediately.                                                                    | admin          |
| `/god`               | `god`                 | Set your unit's health to max.                                                               | admin          |
| `/ehelp`             | `help`                | Show the commands you can use.                                                               | visitor, user  |
| `/hub`               | `hub`                 | Create hub server-to-server points.                                                          | admin          |
| `/info`              | `info`                | Show your own player info.                                                                   | user           |
| `/info`              | `info.other`          | `/info <player>` with the ban and kick menu, and UUIDs in `/players`. A target who holds it gets no menu. | admin |
| `/js`                | `js`                  | Run JavaScript. A player without the node who tries it is kicked.                            | *(none)*       |
| `/kickall`           | `kickall`             | Kick every player without `kick.admin`.                                                      | *(none)*       |
| `/kill`              | `kill`                | Kill your own unit.                                                                          | admin          |
| `/kill`              | `kill.other`          | `/kill <player>`: kill another player's unit.                                                | admin          |
| `/killall`           | `killall`             | Kill all units, or all units of one team.                                                    | *(none)*       |
| `/killunit`          | `killunit`            | Destroy units of one type.                                                                   | *(none)*       |
| `/lang`              | `lang`                | Choose the language the server writes to you in. Allowed for everyone whatever the file says. | visitor, user |
| `/log`               | `log`                 | Toggle block history view.                                                                   | *(none)*       |
| `/login`             | `login`               | Log in to an account.                                                                        | visitor        |
| `/maps`              | `maps`                | Show the server's maps.                                                                      | user           |
| `/me`                | `me`                  | Chat with a special prefix.                                                                  | user           |
| `/meme`              | `meme`                | Meme features.                                                                               | admin          |
| `/motd`              | `motd`                | Show the message of the day.                                                                 | user           |
| `/mute`              | `mute`                | Mute a player.                                                                               | admin          |
| `/nextmap`           | `nextmap`             | Vote for the next map, or see the votes.                                                     | user           |
| `/nextmap`           | `nextmap.admin`       | `/nextmap <map>` sets the next map outright instead of voting.                               | admin          |
| `/pause`             | `pause`               | Pause or unpause the game.                                                                   | admin          |
| `/players`           | `players`             | Show the players online.                                                                     | user           |
| `/pm`                | `pm`                  | Send a private message.                                                                      | user           |
| `/ranking`           | `ranking`             | Show player rankings.                                                                        | user           |
| `/reg`               | `reg`                 | Register an account.                                                                         | visitor        |
| `/report`            | `report`              | Report a player.                                                                             | user           |
| `/rollback`          | `rollback`            | Undo all actions taken by a player.                                                          | admin          |
| `/rtv`               | `rtv`                 | Vote to move on to the next map.                                                             | user           |
| `/setfeedbackprovider` | `setfeedbackprovider` | Give a player the FeedbackProvider achievement. Also needs `admin`.                        | admin          |
| `/setitem`           | `setitem`             | Set an item amount in a team's core.                                                         | *(none)*       |
| `/setmapprovider`    | `setmapprovider`      | Give a player the MapProvider achievement. Also needs `admin`.                               | admin          |
| `/setperm`           | `setperm`             | Set a player's permission group.                                                             | *(none)*       |
| `/skip`              | `skip`                | Skip ahead a number of waves.                                                                | admin          |
| `/spawn`             | `spawn`               | Spawn units or blocks at your position.                                                      | admin          |
| `/status`            | `status`              | Show server status.                                                                          | user           |
| `/strict`            | `strict`              | Set whether a player can build.                                                              | admin          |
| `/et`                | `t`                   | Send a message to your team only.                                                            | user           |
| `/team`              | `team`                | Change your own team.                                                                        | admin          |
| `/team`              | `team.other`          | `/team <team> <player>`: change another player's team.                                       | admin          |
| `/time`              | `time`                | Show the server time.                                                                        | user           |
| `/tp`                | `tp`                  | Teleport to a player.                                                                        | user           |
| `/track`             | `track`               | Show other players' cursor positions.                                                        | user           |
| `/unban`             | `unban`               | Unban a player.                                                                              | *(none)*       |
| `/undo`              | `undo`                | Undo the last admin action (ban, kick, mute, build restriction, group or team change).       | admin          |
| `/unmute`            | `unmute`              | Unmute a player.                                                                             | admin          |
| `/url`               | `url`                 | Open the URL a command points to.                                                            | user           |
| `/evote`             | `vote`                | Start a vote. Each vote type also needs its own node below.                                  | user           |
| `/evote`             | `vote.admin`          | Start a vote with 3 or fewer eligible voters online.                                         | admin          |
| `/evote`             | `vote.back`           | `/evote back`                                                                                | user           |
| `/evote`             | `vote.draw`           | `/evote draw`                                                                                | user           |
| `/evote`             | `vote.gg`             | `/evote gg`                                                                                  | user           |
| `/evote`             | `vote.kick`           | `/evote kick`, and the vote `/evotekick` starts.                                             | user           |
| `/evote`             | `vote.map`            | `/evote map`                                                                                 | user           |
| `/evote`             | `vote.pass`           | Pass your own vote instantly by typing yes, cancel any vote by typing no, and skip the cooldown after a gg or draw vote. | admin |
| `/evote`             | `vote.random`         | `/evote random`                                                                              | user           |
| `/evote`             | `vote.random.bypass`  | Start `/evote random` before its cooldown is over.                                           | admin          |
| `/evote`             | `vote.reset`          | `/evote reset`: clear the running vote and all vote cooldowns.                               | admin          |
| `/evote`             | `vote.skip`           | `/evote skip`                                                                                | user           |
| `/evotekick`         | `votekick`            | Start a kick vote. Also needs `vote.kick`.                                                    | *(none)*       |
| `/votemap`           | `votemap`             | Start a vote for the map with a given ID from `/maps`.                                       | user           |
| `/weather`           | `weather`             | Add a weather effect to the map.                                                             | admin          |
| `/ws`                | `ws`                  | WorldEdit: select an area, then fill, replace or delete blocks.                              | *(none)*       |
