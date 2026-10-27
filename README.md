# rbw-hook

A Spigot/Paper plugin that connects a Minecraft BedWars server to RBW (Ranked Bedwars). It creates ranked games when
RBW asks for them, reports their results, keeps RBW's map list up to date and provides leaderboard and stats
placeholders.

## Requirements

- Java 17
- Spigot or Paper, tested on Paper 1.21.4 and Paper 1.8.8
- To host games: [BedWars1058](https://www.spigotmc.org/resources/bedwars1058.50942/), or another BedWars plugin with
  an [adapter](#other-bedwars-plugins). A server without one, such as a lobby, can't host games but still serves the
  placeholders.
- Optional: [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) for the placeholders

## Installation

1. Download `rbw-hook.jar` from the [latest release](https://github.com/giqnt/rbw-hook-plugin/releases/latest) and put
   it in `plugins/`.
2. Start the server once. rbw-hook writes `plugins/rbw-hook/config.yml` and disables itself because `api-url` and
   `token` are empty.
3. Fill in the config (below) and restart.

### Updates

With `auto-update` on, rbw-hook checks for a new release every 6 hours, downloads it into `plugins/update/` and the
server applies it on the next restart. Builds that are not a release (a version other than `X.Y.Z`) never update.

Don't reload rbw-hook with `/reload` or a plugin manager. Games in progress live only in memory and are lost.

## Configuration

`plugins/rbw-hook/config.yml`:

| Key | Meaning |
| --- | --- |
| `api-url` | The RBW api address, `https://rbw-api.giqnt.dev` |
| `token` | The game server token issued under "Game servers" on the RBW web panel. It also decides which community the server belongs to |
| `group-prefix` | BedWars1058 only, see below |
| `proxy.enabled`, `proxy.username`, `proxy.password`, `proxy.ip`, `proxy.port` | An HTTP proxy for outgoing requests. Off by default |
| `rank-color.<elo>` | A one-character color code for `%rbw_rank_color%`; a player gets the entry with the highest `<elo>` not above their elo |
| `auto-update` | Download new releases (default `true`) |

- If `api-url` or `token` is empty, or `api-url` is not an http(s) URL, rbw-hook logs why and disables itself.
- rbw-hook rewrites the file every time it starts, so comments in it are lost.

## BedWars1058

Set `group-prefix`. Arenas whose group starts with it are used for ranked games:

- The arena group is the map category, so it must match the queue's map category on the RBW web panel. The map name
  is the arena's display name.
- Players can only join those arenas through a ranked game.

## Other BedWars plugins

rbw-hook needs an adapter for the BedWars plugin, registered by another plugin. If you are writing one, see
[api/README.md](api/README.md).

## Placeholders

With PlaceholderAPI installed: `%rbw_leaderboard_<category>_<rank>_<name|value>%` (rank 1 to 10),
`%rbw_stats_<type>%` (for example `elo` or `wins`), `%rbw_position%` and `%rbw_rank_color%`. A leaderboard, stats or
position value that isn't known yet shows as `???`.
