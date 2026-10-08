# ADiscord

A Paper plugin that links Minecraft accounts to Discord. Players get a code in game, verify it on Discord, and from then on their roles, nickname and chat stay in sync between the two.

## Features

- Account linking with `/discord link` in game and `/verify <code>` or a verify button on Discord
- A verified role for every linked member, so the rest of your Discord can stay hidden until they link
- LuckPerms groups synced to Discord roles, including temporary ranks that run out
- Discord nicknames set to the player's Minecraft name
- Two-way chat between the server and a Discord channel, with player heads through a webhook
- Join, quit, death, advancement and server start/stop messages as embeds
- `/playerlist` on Discord
- Rewards for linking that can only be claimed once per account
- SQLite or MySQL/MariaDB storage
- Every message can be changed: MiniMessage in game, embeds on Discord

## Requirements

- Paper 26.2 or newer running on Java 25
- [LuckPerms](https://luckperms.net) for role sync (optional, linking works without it)

JDA, HikariCP and the database drivers are downloaded by Paper on the first start, so the server needs internet access at that point.

## Setup

1. Create an application at the [Discord Developer Portal](https://discord.com/developers/applications). Under **Bot**, turn on the **Server Members** and **Message Content** intents and copy the token.
2. Invite the bot with the `bot` and `applications.commands` scopes and these permissions: View Channels, Send Messages, Embed Links, Read Message History, Manage Roles, Manage Nicknames and Manage Webhooks. If the bot is not in your server yet, the plugin prints a ready-made invite link in the console.
3. Drop the jar into `plugins/`, start the server once, then fill in `plugins/ADiscord/config.yml`: the token, your server ID, the verify channel, the verified role, the chat channel and the role for each LuckPerms group. Restart the server.
4. Set up the channel permissions on Discord:
   - `@everyone` can only see the verify channel
   - the verified role can see the rest of the server
   - in the verify channel, deny Send Messages for `@everyone`. Slash commands and the verify button keep working.
5. Move the bot's role above every role it should hand out, otherwise Discord will not let it manage them.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/discord` | Shows the Discord invite | none |
| `/discord link` | Gives you a code to verify on Discord | `adiscord.command.link` |
| `/discord unlink` | Unlinks your Discord account | `adiscord.command.link` |
| `/discord info` | Shows which Discord account you are linked to | `adiscord.command.link` |
| `/discord reload` | Reloads the configuration files | `adiscord.command.reload` |
| `/discordadmin lookup <target>` | Looks up a link by player name or Discord ID | `adiscord.command.admin` |
| `/discordadmin link <player> <discord id>` | Links a player without a code | `adiscord.command.admin` |
| `/discordadmin unlink <player>` | Removes a player's link | `adiscord.command.admin` |
| `/discordadmin sync [player]` | Syncs roles for one player or everyone | `adiscord.command.admin` |

`adiscord.command.link` is given to everyone by default, the others to operators. `adiscord.*` grants all of them.

On Discord the bot adds `/verify <code>` and `/playerlist`. Both names can be changed in `discord.yml`.

## Configuration

| File | Contents |
| --- | --- |
| `config.yml` | Bot token, database, linking, role sync, nickname, chat and event settings |
| `messages.yml` | In-game messages in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format |
| `discord.yml` | Embeds and texts the bot sends on Discord |

Most changes apply with `/discord reload`. Changing the bot token or the database needs a restart.

### Role sync

Map LuckPerms groups to Discord role IDs under `role-sync.groups`. The `mode` setting decides which groups count:

- `DIRECT`: groups given to the player directly, temporary ones included
- `ALL`: also groups inherited from other groups
- `PRIMARY`: only the primary group

Roles update right away when a rank changes in game, when a temporary rank expires, when a linked player joins and when a linked member rejoins the Discord server. A full sync also runs every `role-sync.interval` minutes. Only the roles listed in the config are ever added or removed.

## Building

```bash
mvn package
```

The jar ends up in `target/`.
