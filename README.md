# Soulbound SMP (Paper 1.21.4+, Java 21)

## Build
    mvn package
Jar: `target/SoulboundSMP.jar` -> drop into `plugins/`.

## Server setup
- `server.properties`: `hardcore=true` (recommended, gives hardcore hearts). The plugin also works without it.
- Run on Paper 1.21.4 or newer.

## How it plays
- Die = eliminated (spectator, leashed to their death spot, radius in config).
- Craft a **Revive Beacon** (config recipe), place it on the altar
  (default: 3x3 gold-block platform directly under the beacon), right-click it, pick the soul.
- Ritual runs `ritual.duration-seconds`. Coordinates are broadcast to everyone (chat, title, boss bar, reminders).
- Beacon / altar destroyed (break, explosion, piston, anything) = ritual fails -> target permanently banned.
- Success = target returns, their ONE revive is consumed. Dying again = permanent ban.
- Only one ritual can run at a time. It survives server restarts.

## Events (every 12h, configurable)
Blood Moon, Empty Souls, Soul Drain, Eternal Night, Fragile World, Hunger Games,
No Escape, Beast Awakening, Broken Bonds, The Silence.

## Commands (`/soulbound`, alias `/sb`)
- `/sb status [player]`, `/sb event`
- Admin (`soulbound.admin`): `/sb event roll|set <id> [hours]|stop|list`, `/sb revive|eliminate|reset <player>`,
  `/sb givebeacon [player] [amount]`, `/sb ritual info|cancel`, `/sb reload`
- `/sbadmin` (alias `/sa`, ops only): click menu - set any event, roll/stop events, revive any eliminated player.
- `/sbroadcast [-t] [-q] <message>` (aliases `/sbc`, `/announce`, ops only): announce to everyone.
  `-t` also shows a big title, `-q` = silent. MiniMessage works, e.g. `/sbroadcast <red>Server restart in 5 min`.
- Permission `soulbound.bypass`: not affected by elimination (for staff/testing).
