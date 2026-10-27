package dev.giqnt.rbw.hook.websocket.handlers;

import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.websocket.MessageHandler;
import dev.giqnt.rbw.hook.websocket.MessageHandlerContext;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public class PlayerStateGetRequestHandler extends MessageHandler {
    @Override
    public void execute(@NonNull MessageHandlerContext context) {
        final var plugin = context.plugin();
        final var data = plugin.getGson().fromJson(context.data(), PlayerStateGetRequestData.class);
        final Player player = plugin.getServer().getPlayer(data.uuid());
        boolean isReady = player != null && plugin.getGameManager().isReady(player);
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("ready", isReady);
        context.messageReplier().accept(responseData);
    }

    private record PlayerStateGetRequestData(UUID uuid) {
    }
}
