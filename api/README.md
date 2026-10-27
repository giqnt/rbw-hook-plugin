# rbw-hook adapter API

rbw-hook hosts ranked games on BedWars1058 out of the box. To host them on another BedWars plugin, write an adapter:
implement `BedWarsAdapter` in your plugin and register it with rbw-hook. The adapter only does the BedWars side
(picking an arena, moving players, telling rbw-hook when the game starts and ends). rbw-hook keeps doing everything
that talks to RBW: reporting results and retrying them, the game list RBW polls, repeated create requests, and
deciding whether a game failed to start or was dropped.

The classes are in `dev.giqnt.rbw.hook.api`.

## Dependency

The API is published through [JitPack](https://jitpack.io/#giqnt/rbw-hook-plugin) from v2.2.0 on. Earlier versions on
the same coordinate (such as `v2.0.0`) are builds of the whole plugin, not the API.

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.giqnt:rbw-hook-plugin:v2.2.0")
}
```

Maven:

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.giqnt</groupId>
    <artifactId>rbw-hook-plugin</artifactId>
    <version>v2.2.0</version>
    <scope>provided</scope>
</dependency>
```

Use `compileOnly` (`provided`) and never shade the API into your jar. rbw-hook looks the adapter up by its own
`BedWarsAdapter` class; a shaded copy is a different class and rbw-hook never sees your adapter.

The API is compiled for Java 17 and only uses Bukkit's `Player`, so it works on every server version rbw-hook
supports (Paper 1.8.8 to 1.21.4).

## Registering

Depend on rbw-hook in `plugin.yml` so it is enabled first:

```yaml
depend: [rbw-hook]
```

With `paper-plugin.yml`, load it before your plugin; the default leaves the order undefined:

```yaml
dependencies:
  server:
    rbw-hook:
      load: BEFORE
```

Register the adapter in `onEnable`:

```java
@Override
public void onEnable() {
    getServer().getServicesManager()
            .register(BedWarsAdapter.class, new MyAdapter(this), this, ServicePriority.Normal);
}
```

- Register and unregister on the main thread.
- Bukkit unregisters the adapter when your plugin is disabled. You don't need to do it yourself.
- rbw-hook uses the registered adapter with the highest priority. Its built-in BedWars1058 adapter is registered at
  `Lowest`, so `Normal` replaces it. If several share the highest priority, rbw-hook uses the one Bukkit picks and
  logs a warning.
- The RBW web panel shows, per game server token, the name and version of the plugin that registered the adapter in use
  (the `plugin` argument above).
- When the adapter in use changes (yours is registered or unregistered), rbw-hook abandons every game the previous
  adapter had in progress. Later callbacks for those games are ignored. Ending those arenas is up to your plugin.
- If rbw-hook is reloaded, reload your plugin too. Your plugin still holds the old `BedWarsAdapter` class, which the
  new rbw-hook doesn't recognize.

### Optional dependency

If your plugin should also run on servers without rbw-hook, use `softdepend: [rbw-hook]` (with `paper-plugin.yml`,
add `required: false` next to `load: BEFORE`) and register only when rbw-hook is enabled. Keep every reference to
`dev.giqnt.rbw.hook.api` in classes that are loaded after that check, or your plugin fails with
`NoClassDefFoundError` on servers without rbw-hook:

```java
@Override
public void onEnable() {
    if (getServer().getPluginManager().isPluginEnabled("rbw-hook")) {
        RbwHookSupport.register(this); // the only class that uses the API
    }
}
```

## Implementing `BedWarsAdapter`

rbw-hook calls every method on the main thread.

### `getMaps()`

Every map the server can host, as `MapInfo(category, name, buildHeight)`:

- `category` is the queue's map category on the RBW web panel. A queue only creates games with maps of its category.
- `name` is what a game is created with and what players see.
- `buildHeight` is the highest Y players can build at, shown to players. It is optional (`null`).
- Two `MapInfo`s are the same map if their `category` and `name` match.

rbw-hook sends this list to RBW every 30 seconds and it **replaces the whole map list of the server's community**,
not only this server's. Register the adapter once your maps are loaded, and don't return an empty list while it is
registered.

### `isReady(Player)`

Whether `startGame` can take this player right now, for example false while they are playing another game. rbw-hook
only asks about online players, and RBW tries again later while someone isn't ready. A player spectating another
game should count as ready; `startGame` moves them out.

### `startGame(RankedGame, GameCallbacks)`

Called when RBW creates a game and every player is ready. `RankedGame` holds:

- `id`: the game id, also used in rbw-hook's log messages.
- `mapNames`: the maps the game may be played on, or `null` for any map from `getMaps()`.
- `players`: every player, and `teams`: the same players split into teams.

rbw-hook handles a repeated create request for the same game itself; `startGame` is called once per game.

`startGame` runs synchronously. It picks a map, puts the teams into an arena and **returns the map name as soon as
the countdown begins**:

- The name is never `null` and is one of `mapNames`, or one from `getMaps()` when `mapNames` is `null`.
- If the game can't start, move the players back out and throw `GameCreateException`, with the chosen map name if
  there is one. RBW shows the message on Discord and tries again. Any other exception fails the same way, with a generic
  message.
- Don't call `ended` or `abandoned` before returning. A game that ends inside `startGame` counts as a failed start and
  its result is discarded.
- Don't throw after calling `started()`. The arena keeps playing, but RBW has been told the game failed and the game
  ends up voided.

Keep the `GameCallbacks` for the game; it is how the adapter reports what happens next.

## `GameCallbacks`

| Callback | When |
| --- | --- |
| `started()` | The countdown is over and the game is being played. An adapter without a countdown calls it before `startGame` returns |
| `ended(GameResult)` | The game is over with a winner |
| `abandoned(String reason)` | The game is over without a result, a draw included. The reason is logged and, before `started()`, sent to RBW |

Rules:

- **Main thread only.** From any other thread a callback throws `IllegalStateException` and changes nothing. If you
  learn about the end from an async task, move back with `Bukkit.getScheduler().runTask`. rbw-hook doesn't do it for
  you because the hop could run `abandoned` before an earlier `started`.
- **Only the first of `ended` and `abandoned` counts.** Later calls are ignored and logged.
- **`started()` changes what `abandoned` means.** Before it, RBW is told the game failed to start and creates it again,
  so move every player out of the arena first; otherwise they are still in it when the next attempt comes. After it,
  the game is dropped and RBW voids it.
- A player leaving during the countdown is a typical `abandoned` before `started()`. A player leaving during the game
  is not an end; the game goes on and ends with `ended` or `abandoned`.
- An arena that restarts or closes without a winner is `abandoned`.

### `ended(GameResult)`

`GameResult(teams)`, with each `TeamResult(win, players)` and each `PlayerResult(uuid, name, kills, bedsBroken)`:

- Exactly one team has `win` set, and that team has at least one player. A draw is `abandoned`, not `ended`.
- No `null` lists, players, UUIDs or names, and `kills` and `bedsBroken` are 0 or more.

rbw-hook checks this before reporting. A result that breaks it is not reported: rbw-hook logs it as an error and the
game is voided, without a new attempt. A valid result is retried for about 10 minutes until RBW accepts it, and the
game stays in the list RBW polls in the meantime.

## Compatibility

rbw-hook updates itself on every server, so the API only grows and an adapter keeps working with newer versions:

- Methods added to `BedWarsAdapter` or `GameCallbacks` have a default implementation.
- The constructors of the types adapters create (`MapInfo`, `GameResult`, `TeamResult`, `PlayerResult`,
  `GameCreateException`) keep their signatures.
- `RankedGame` is created only by rbw-hook and may gain components. Read it through its accessors; don't construct it
  or take it apart with a record pattern.
