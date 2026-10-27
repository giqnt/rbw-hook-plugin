package dev.giqnt.rbw.hook.api;

import org.bukkit.entity.Player;

import java.util.Collection;

/** Hosts ranked games on a BedWars plugin. rbw-hook calls every method on the main thread. */
public interface BedWarsAdapter {
    /** Maps this server can host. */
    Collection<MapInfo> getMaps();

    /** True if {@link #startGame} can take this player; a player spectating another game counts as ready. */
    boolean isReady(Player player);

    /**
     * Puts the players into an arena and returns its map name as soon as the countdown begins.
     * The name is never null and is one of {@link RankedGame#mapNames()} if given, otherwise one from {@link #getMaps()}.
     * Throws {@link GameCreateException}, with the chosen map name if there is one, when the game can't start.
     * Must not call an end callback before returning, and must not throw after calling {@link GameCallbacks#started()}.
     */
    String startGame(RankedGame game, GameCallbacks callbacks);
}
