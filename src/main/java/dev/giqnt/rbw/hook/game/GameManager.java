package dev.giqnt.rbw.hook.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.api.BedWarsAdapter;
import dev.giqnt.rbw.hook.api.GameCallbacks;
import dev.giqnt.rbw.hook.api.GameCreateException;
import dev.giqnt.rbw.hook.api.GameResult;
import dev.giqnt.rbw.hook.api.MapInfo;
import dev.giqnt.rbw.hook.api.PlayerResult;
import dev.giqnt.rbw.hook.api.RankedGame;
import dev.giqnt.rbw.hook.api.TeamResult;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps the api contract for whichever adapter hosts the games: game.list, game.create idempotency, reporting results,
 * and telling a start failure (the api creates the game again) from a drop (the api voids it).
 * Main thread only, except where noted.
 */
public class GameManager {
    // 0, 2, 4, ..., 32s, then every 60s: about 10 minutes, to outlast a slow api deploy.
    private static final int REPORT_ATTEMPTS = 15;
    private static final long REPORT_MAX_DELAY_TICKS = 20 * 60;
    private final HookPlugin plugin;
    private final @Nullable BedWarsAdapter adapter;
    /** Game id to its game, from just before startGame until its end callback. */
    private final Map<String, ActiveGame> activeGames = new HashMap<>();
    /**
     * Game id to map name, for ended games whose result is still being reported. Concurrent because the report
     * removes its entry from the async thread: a hop back to the main thread throws once the plugin is disabled.
     */
    private final ConcurrentMap<String, String> pendingResults = new ConcurrentHashMap<>();

    public GameManager(final HookPlugin plugin, final @Nullable BedWarsAdapter adapter) {
        this.plugin = plugin;
        this.adapter = adapter;
    }

    /** False on servers such as a lobby, which answer game.create as unsupported. */
    public boolean canHostGames() {
        return adapter != null;
    }

    public Collection<MapInfo> getMaps() {
        return adapter == null ? List.of() : adapter.getMaps();
    }

    public boolean isReady(final Player player) {
        return adapter == null ? player.isOnline() : adapter.isReady(player);
    }

    /** Games past game.create, including ended ones whose result is still being reported. */
    public Collection<HostedGame> getGames() {
        final List<HostedGame> games = new ArrayList<>();
        activeGames.values().forEach(game ->
                games.add(new HostedGame(game.id, HostedGame.State.STARTED, game.mapName)));
        pendingResults.forEach((id, mapName) -> games.add(new HostedGame(id, HostedGame.State.ENDED, mapName)));
        return games;
    }

    /** Returns the map name, or throws GameCreateException or whatever the adapter threw if the game did not start. */
    public String startGame(final RankedGame game) {
        if (adapter == null) throw new IllegalStateException("This server can't host games");
        // Registered before startGame: an adapter without a countdown calls started() before returning.
        final ActiveGame active = new ActiveGame(game.id());
        activeGames.put(game.id(), active);
        final String mapName;
        try {
            mapName = adapter.startGame(game, active);
        } catch (Throwable ex) {
            activeGames.remove(game.id(), active);
            if (active.state == State.PLAYING) {
                plugin.getLogger().severe("Adapter threw after game #" + game.id()
                        + " started; the api will create it again and void it");
            }
            throw ex;
        }
        if (active.endedEarly != null) {
            throw new GameCreateException(active.endedEarly, mapName);
        }
        active.mapName = mapName;
        return mapName;
    }

    private void reportStartFailure(final String id, final String mapName, final String reason) {
        final JsonObject data = new JsonObject();
        data.addProperty("message", reason);
        data.addProperty("mapName", mapName);
        postUntilAccepted("/server-games/" + id + "/start-failure", plugin.getGson().toJson(data), () -> {}, 0);
    }

    private void reportResult(final String id, final String mapName, final GameResult result) {
        final JsonArray teamsData = new JsonArray();
        for (final TeamResult team : result.teams()) {
            final JsonArray playersData = new JsonArray();
            for (final PlayerResult player : team.players()) {
                final JsonObject playerData = new JsonObject();
                playerData.addProperty("name", player.name());
                playerData.addProperty("uuid", player.uuid().toString());
                playerData.addProperty("kills", player.kills());
                playerData.addProperty("bedsBroken", player.bedsBroken());
                playersData.add(playerData);
            }
            final JsonObject teamData = new JsonObject();
            teamData.addProperty("win", team.win());
            teamData.add("players", playersData);
            teamsData.add(teamData);
        }
        final JsonObject data = new JsonObject();
        data.add("teams", teamsData);
        pendingResults.put(id, mapName);
        postUntilAccepted("/server-games/" + id + "/result", plugin.getGson().toJson(data),
                () -> pendingResults.remove(id), 0);
    }

    /** The same conditions the api rejects with a 400, which would otherwise only show up as a warning. */
    private static @Nullable String validate(final @Nullable GameResult result) {
        if (result == null || result.teams() == null) return "no teams";
        int winners = 0;
        for (final TeamResult team : result.teams()) {
            if (team == null || team.players() == null) return "a team or its player list is null";
            for (final PlayerResult player : team.players()) {
                if (player == null || player.uuid() == null || player.name() == null) {
                    return "a player, its uuid or its name is null";
                }
                if (player.kills() < 0 || player.bedsBroken() < 0) {
                    return "negative kills or beds broken for " + player.name();
                }
            }
            if (team.win()) {
                if (team.players().isEmpty()) return "the winning team has no players";
                winners++;
            }
        }
        if (winners != 1) return winners + " winning teams, expected exactly one";
        return null;
    }

    // The api ignores a repeated report, so retrying is safe; this covers api restarts during a deploy.
    private void postUntilAccepted(final String endpoint, final String body, final Runnable done, final int attempt) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                final int code = plugin.getApi().request(endpoint, "POST", body, Response::code);
                if (code < 500) {
                    if (code >= 300) plugin.getLogger().warning("POST " + endpoint + " rejected: " + code);
                    done.run();
                    return;
                }
                plugin.getLogger().warning("POST " + endpoint + " failed: " + code);
            } catch (IOException e) {
                plugin.getLogger().warning("POST " + endpoint + " failed: " + e.getMessage());
            }
            if (attempt + 1 < REPORT_ATTEMPTS) {
                postUntilAccepted(endpoint, body, done, attempt + 1);
            } else {
                plugin.getLogger().severe("Giving up POST " + endpoint);
                done.run();
            }
        }, attempt == 0 ? 0 : Math.min(20L << attempt, REPORT_MAX_DELAY_TICKS));
    }

    private enum State {
        COUNTDOWN,
        PLAYING
    }

    private final class ActiveGame implements GameCallbacks {
        private final String id;
        private State state = State.COUNTDOWN;
        /** Null until startGame returns. */
        private @Nullable String mapName;
        /** The reason of an end callback that came before startGame returned, which fails the start. */
        private @Nullable String endedEarly;

        private ActiveGame(final String id) {
            this.id = id;
        }

        @Override
        public void started() {
            if (!accept("started")) return;
            if (state != State.COUNTDOWN) {
                plugin.getLogger().warning("Ignoring started() for game #" + id + ": already started");
                return;
            }
            state = State.PLAYING;
        }

        @Override
        public void ended(final GameResult result) {
            if (!accept("ended")) return;
            activeGames.remove(id);
            if (mapName == null) {
                plugin.getLogger().severe("Game #" + id + " ended before startGame returned; discarding its result");
                endedEarly = "Game ended before it started";
                return;
            }
            // Even in COUNTDOWN a result means the game was played, so it must never become a start failure.
            final String problem = validate(result);
            if (problem != null) {
                plugin.getLogger().severe("Not reporting the result of game #" + id + ": " + problem);
                return;
            }
            reportResult(id, mapName, result);
        }

        @Override
        public void abandoned(final String reason) {
            if (!accept("abandoned")) return;
            activeGames.remove(id);
            if (mapName == null) {
                endedEarly = reason;
            } else if (state == State.COUNTDOWN) {
                reportStartFailure(id, mapName, reason);
            } else {
                plugin.getLogger().warning("Dropping game #" + id + ": " + reason);
            }
        }

        private boolean accept(final String callback) {
            // No hop to the main thread: it could reorder started() and abandoned() into a start failure mid-game.
            if (!Bukkit.isPrimaryThread()) {
                throw new IllegalStateException(callback + "() must be called on the main thread");
            }
            // Bukkit does not disable dependent plugins with us, so an external adapter can still call back.
            if (!plugin.isEnabled()) return false;
            if (activeGames.get(id) != this) {
                plugin.getLogger().warning("Ignoring " + callback + "() for game #" + id + ": the game is already over");
                return false;
            }
            return true;
        }
    }
}
