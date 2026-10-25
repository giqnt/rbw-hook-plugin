package dev.giqnt.rbw.hook.adapter;

import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.stats.DefaultStatistics;
import com.andrei1058.bedwars.api.arena.team.ITeamAssigner;
import com.andrei1058.bedwars.api.configuration.ConfigPath;
import com.andrei1058.bedwars.api.events.gameplay.GameEndEvent;
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent;
import com.andrei1058.bedwars.api.events.player.PlayerJoinArenaEvent;
import com.andrei1058.bedwars.api.events.player.PlayerLeaveArenaEvent;
import com.andrei1058.bedwars.api.events.server.ArenaDisableEvent;
import com.andrei1058.bedwars.api.events.server.ArenaRestartEvent;
import com.andrei1058.bedwars.arena.Arena;
import com.andrei1058.bedwars.arena.team.TeamAssigner;
import dev.giqnt.rbw.hook.api.BedWarsAdapter;
import dev.giqnt.rbw.hook.api.GameCallbacks;
import dev.giqnt.rbw.hook.api.GameCreateException;
import dev.giqnt.rbw.hook.api.GameResult;
import dev.giqnt.rbw.hook.api.MapInfo;
import dev.giqnt.rbw.hook.api.PlayerResult;
import dev.giqnt.rbw.hook.api.RankedGame;
import dev.giqnt.rbw.hook.api.TeamResult;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class BedWars1058Adapter implements BedWarsAdapter, Listener {
    private final String groupPrefix;
    /**
     * Arena name to its ranked game. A session is removed before any end callback: the arena still fires leave and
     * restart events for it afterwards, which would otherwise call back a second time.
     */
    private final Map<String, ArenaSession> sessions = new HashMap<>();
    private final ITeamAssigner teamAssigner = arena -> {
        final ArenaSession session = sessions.get(arena.getArenaName());
        if (session == null) {
            new TeamAssigner().assignTeams(arena);
            return;
        }
        final List<List<Player>> teams = session.game().teams();
        for (int i = 0; i < teams.size(); i++) {
            arena.getTeams().get(i).addPlayers(teams.get(i).toArray(new Player[0]));
        }
    };

    @Override
    public Collection<MapInfo> getMaps() {
        final Set<MapInfo> maps = new HashSet<>();
        for (final var arena : Arena.getArenas()) {
            final String group = arena.getGroup();
            if (!group.startsWith(groupPrefix)) continue;
            final String mapName = arena.getDisplayName();
            final int maxBuildY = arena.getConfig().getInt(ConfigPath.ARENA_CONFIGURATION_MAX_BUILD_Y);
            maps.add(new MapInfo(group, mapName, maxBuildY));
        }
        return Collections.unmodifiableSet(maps);
    }

    @Override
    public boolean isReady(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        final var arena = Arena.getArenaByPlayer(player);
        return arena == null || !arena.isPlayer(player);
    }

    @Override
    public String startGame(final RankedGame game, final GameCallbacks callbacks) {
        final IArena arena = pickArena(game);
        final var session = new ArenaSession(game, arena.getDisplayName(), callbacks);
        sessions.put(arena.getArenaName(), session);
        try {
            arena.setTeamAssigner(teamAssigner);
            for (final var player : game.players()) {
                // isReady lets spectators through: an eliminated player watches their last game until it restarts.
                final IArena watching = Arena.getArenaByPlayer(player);
                if (watching != null && watching.isSpectator(player)) watching.removeSpectator(player, false);
                if (!arena.addPlayer(player, true)) {
                    throw new GameCreateException(
                            String.format("Player `%s` could not join the game", player.getName()), session.mapName());
                }
            }
            // addPlayer starts the countdown itself once the arena has enough players.
            if (arena.getStatus() == GameState.waiting) {
                throw new GameCreateException("Game not getting started", session.mapName());
            }
        } catch (Throwable ex) {
            discard(arena, session);
            if (ex instanceof GameCreateException || ex instanceof Error) throw ex;
            throw new GameCreateException("Failed to add players to arena", ex);
        }
        return session.mapName();
    }

    private IArena pickArena(final RankedGame game) {
        final List<List<Player>> teams = game.teams();
        final List<String> mapNames = game.mapNames();
        final Set<String> mapNameSet = mapNames == null ? null : Set.copyOf(mapNames);
        final int teamCount = teams.size();
        final int teamSize = teams.stream().mapToInt(List::size).max().orElse(0);

        final Map<String, List<IArena>> mapToArenas = new HashMap<>();
        for (final IArena arena : Arena.getArenas()) {
            if (!arena.getGroup().startsWith(groupPrefix)
                    || arena.getStatus() != GameState.waiting
                    || !arena.getPlayers().isEmpty()
                    || arena.getMaxPlayers() < teamCount * teamSize
                    || arena.getTeams().size() < teamCount
                    || arena.getMaxInTeam() < teamSize) {
                continue;
            }
            if (mapNameSet == null || mapNameSet.contains(arena.getDisplayName())) {
                mapToArenas.computeIfAbsent(arena.getDisplayName(), k -> new ArrayList<>()).add(arena);
            }
        }
        if (mapToArenas.isEmpty()) {
            throw new GameCreateException("No available arena found");
        }
        final List<List<IArena>> arenaGroups = List.copyOf(mapToArenas.values());
        final List<IArena> arenas = arenaGroups.get(ThreadLocalRandom.current().nextInt(arenaGroups.size()));
        return arenas.get(ThreadLocalRandom.current().nextInt(arenas.size()));
    }

    private boolean discard(final IArena arena, final ArenaSession session) {
        if (!sessions.remove(arena.getArenaName(), session)) return false;
        new ArrayList<>(arena.getPlayers()).forEach(player -> arena.removePlayer(player, false));
        return true;
    }

    private void abandon(final IArena arena, final ArenaSession session, final String reason) {
        if (!discard(arena, session)) return;
        session.callbacks().abandoned(reason);
    }

    @EventHandler
    public void onArenaLeave(final PlayerLeaveArenaEvent event) {
        if (event.isSpectator()) return;
        final var arena = event.getArena();
        if (arena == null || (arena.getStatus() != GameState.waiting && arena.getStatus() != GameState.starting)) {
            return;
        }
        final ArenaSession session = sessions.get(arena.getArenaName());
        if (session == null) return;
        abandon(arena, session, String.format("Player `%s` left the game", event.getPlayer().getName()));
    }

    @EventHandler
    public void onGameEnd(final GameEndEvent event) {
        final var arena = event.getArena();
        if (arena == null) {
            return;
        }
        final String arenaName = arena.getArenaName();
        final ArenaSession session = sessions.remove(arenaName);
        if (session == null) {
            return;
        }
        if (arena.getStatsHolder() == null) {
            session.callbacks().abandoned("Arena " + arenaName + " has no statistics");
            return;
        }
        final UUID winningTeamId = event.getTeamWinner().getIdentity();
        final List<TeamResult> teams = new ArrayList<>();
        arena.getTeams().forEach(team -> {
            final List<PlayerResult> players = new ArrayList<>();
            //noinspection deprecation
            team.getMembersCache().forEach(member -> {
                final var stats = arena.getStatsHolder().get(member.getUniqueId());
                final int kills = stats.flatMap(s -> s.getStatistic(DefaultStatistics.KILLS))
                        .map(stat -> (int) stat.getValue()).orElse(0);
                final int bedsBroken = stats.flatMap(s -> s.getStatistic(DefaultStatistics.BEDS_DESTROYED))
                        .map(stat -> (int) stat.getValue()).orElse(0);
                players.add(new PlayerResult(member.getUniqueId(), member.getName(), kills, bedsBroken));
            });
            teams.add(new TeamResult(team.getIdentity().equals(winningTeamId), players));
        });
        session.callbacks().ended(new GameResult(teams));
    }

    @EventHandler
    public void onGameStateChange(final GameStateChangeEvent event) {
        if (event.getArena() == null || event.getNewState() != GameState.playing) return;
        final ArenaSession session = sessions.get(event.getArena().getArenaName());
        if (session != null) session.callbacks().started();
    }

    // An arena can restart without GameEndEvent, e.g. a draw when the game timer runs out.
    // Not on GameStateChangeEvent(restarting): BedWars fires it before GameEndEvent, so every result would be lost.
    @EventHandler
    public void onArenaRestart(final ArenaRestartEvent event) {
        dropSession(event.getArenaName());
    }

    @EventHandler
    public void onArenaDisable(final ArenaDisableEvent event) {
        dropSession(event.getArenaName());
    }

    private void dropSession(final String arenaName) {
        final ArenaSession session = sessions.remove(arenaName);
        if (session == null) return;
        session.callbacks().abandoned("Arena " + arenaName + " closed without a result");
    }

    @EventHandler
    public void onArenaJoin(final PlayerJoinArenaEvent event) {
        if (event.isSpectator()) return;
        final var arena = event.getArena();
        if (arena == null || !arena.getGroup().startsWith(groupPrefix)) {
            return;
        }
        final ArenaSession session = sessions.get(arena.getArenaName());
        if (session == null || !session.game().players().contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private record ArenaSession(RankedGame game, String mapName, GameCallbacks callbacks) {
    }
}
