# Power Ruble Telega

Optional Telegram private-chat addon for [Power Ruble](https://github.com/PowerTronin/power-ruble).

This server-side Fabric 1.20.1 mod lets linked Minecraft players check balances and send Power Ruble transfers through a Telegram bot.

## Install

Put both jars into the server `mods` folder:

- `power-ruble-1.1.0.jar`
- `power-ruble-telega-1.1.0.jar`

Start the server once to create `config/power-ruble-telega.json5`, then put the BotFather token into `telegram.botToken` and set `telegram.enabled` to `true` on the server only. Do not commit a real bot token.

The bot handles private chats only. Group and channel messages are ignored.

## Minecraft Commands

- `/telega help` - show addon commands.
- `/telega link` - create a temporary Telegram link code.
- `/telega unlink` - remove your Telegram link.
- `/telega status` - show your Telegram link status.
- `/telega reload` - reload config and restart the bot, operator-only by default.

## Telegram Commands

- `/start` or `/help` - show bot commands.
- `/link <code>` - link this Telegram chat to a Minecraft account.
- `/unlink` - remove the link.
- `/whoami` - show the linked Minecraft account.
- `/balance` - show your Power Ruble balance.
- `/pay <player> <amount> [comment]` - send money to another player.
- `/players` - show known server players.
- `/online` - show players currently online.
- `/admin help` - show admin commands for linked operators.

## Admin Commands

Telegram admin commands require a linked Minecraft account with the configured permission level or operator status.

- `/admin balance <player>`
- `/admin give <player> <amount> [reason]`
- `/admin take <player> <amount> [reason]`
- `/admin set <player> <amount>`
- `/admin bank balance`
- `/admin bank give <amount>`
- `/admin bank take <amount>`
- `/admin bank set <amount>`
- `/admin bank pay <player> <amount> [comment]`
- `/admin reload`

`/admin take` moves money from the player to the bank account. It does not destroy money.

## Config

The addon creates `config/power-ruble-telega.json5`:

```json5
{
  telegram: {
    enabled: false,
    // Keep empty in version control. Put the BotFather token only on the server.
    botToken: "",
    pollTimeoutSeconds: 25,
    requestTimeoutSeconds: 35,
    reconnectDelayMillis: 5000,
  },
  links: {
    codeLength: 8,
    codeTtlSeconds: 600,
  },
  commands: {
    payEnabled: true,
    maxReasonLength: 120,
    notifyOnlinePlayers: true,
  },
  admin: {
    permissionLevel: 2,
  },
}
```

After editing config, restart the server or run `/telega reload` / `/admin reload`.

## Development note

This repository uses the matching base development jar from `../power-ruble/build/devlibs` when present, otherwise it downloads it from the `power-ruble` release assets. The version is controlled by `base_mod_version` in `gradle.properties`.

## Build

```sh
./gradlew build
```

Jar output:

```text
build/libs/power-ruble-telega-1.1.0.jar
```
