package dev.giqnt.rbw.hook.websocket.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.api.GameCreateException;
import dev.giqnt.rbw.hook.api.RankedGame;
import dev.giqnt.rbw.hook.websocket.MessageHandler;
import dev.giqnt.rbw.hook.websocket.MessageHandlerContext;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameCreateRequestHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final var plugin = context.plugin();
        final var games = plugin.getGameManager();
        if (!games.canHostGames()) {
            final JsonObject responseData = new JsonObject();
            responseData.addProperty("success", false);
            responseData.addProperty("unsupported", true);
            responseData.addProperty("message", "This server can't host games.");
            context.messageReplier().accept(responseData);
            return;
        }
        try {
            final var data = plugin.getGson().fromJson(context.data(), GameCreateRequestData.class);
            // A repeated game.create for the same id must get the same result as the first one.
            final var existing = games.getGames().stream()
                    .filter(game -> game.id().equals(data.id()))
                    .findFirst();
            if (existing.isPresent()) {
                plugin.getLogger().info("Game " + data.id() + " already created, replying with its result");
                sendGameCreateSuccess(context, existing.get().mapName());
                return;
            }

            final List<GameCreateRequestTeamMemberData> missingPlayers = new ArrayList<>();
            final List<List<Player>> teamPlayers = data.teams().stream()
                    .map(team -> team.members()
                            .stream().map(member -> {
                                final Player player = plugin.getServer().getPlayer(member.uuid());
                                if (player == null || !player.isOnline() || !games.isReady(player)) {
                                    missingPlayers.add(member);
                                    return null;
                                }
                                return player;
                            })
                            .toList())
                    .toList();
            if (!missingPlayers.isEmpty()) {
                sendGameCreateFailure(context, missingPlayers);
                return;
            }

            final Set<Player> players = teamPlayers.stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toUnmodifiableSet());
            plugin.getLogger().info("Creating game " + data.id());
            final String mapName = games.startGame(new RankedGame(data.id(), data.mapNames(), players, teamPlayers));
            plugin.getLogger().info("Successfully created game " + data.id() + " on map " + mapName);
            sendGameCreateSuccess(context, mapName);
        } catch (GameCreateException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create game", e);
            sendGameCreateFailure(context, e.getMessage(), e.getMapName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error processing create_game request", e);
            sendGameCreateFailure(context, "Internal server error");
        }
    }

    private void sendGameCreateFailure(final MessageHandlerContext context, final String message) {
        sendGameCreateFailure(context, message, null);
    }

    private void sendGameCreateFailure(final MessageHandlerContext context, final String message, @Nullable final String mapName) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("success", false);
        responseData.addProperty("message", message);
        if (mapName != null) responseData.addProperty("mapName", mapName);
        context.messageReplier().accept(responseData);
    }

    private void sendGameCreateFailure(final MessageHandlerContext context, final List<GameCreateRequestTeamMemberData> offlinePlayers) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("success", false);
        responseData.addProperty("message", "Some players are offline or not ready");
        final JsonArray missingPlayers = new JsonArray();
        offlinePlayers.forEach(data ->
                missingPlayers.add(context.plugin().getGson().toJsonTree(data)));
        responseData.add("missingPlayers", missingPlayers);
        context.messageReplier().accept(responseData);
    }

    private void sendGameCreateSuccess(final MessageHandlerContext context, final String mapName) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("success", true);
        responseData.addProperty("mapName", mapName);
        context.messageReplier().accept(responseData);
    }

    private record GameCreateRequestData(String id, List<String> mapNames, List<GameCreateRequestTeamData> teams) {
    }

    private record GameCreateRequestTeamData(String id, List<GameCreateRequestTeamMemberData> members) {
    }

    private record GameCreateRequestTeamMemberData(String userId, UUID uuid, String name) {
    }
}
