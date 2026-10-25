package dev.giqnt.rbw.hook.api;

import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

public record RankedGame(
        String id,
        @Nullable List<String> mapNames,
        Set<Player> players,
        List<List<Player>> teams
) {
}
