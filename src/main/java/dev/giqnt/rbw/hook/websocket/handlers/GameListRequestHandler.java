package dev.giqnt.rbw.hook.websocket.handlers;

import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.websocket.MessageHandler;
import dev.giqnt.rbw.hook.websocket.MessageHandlerContext;
import org.jspecify.annotations.NonNull;

/** The api voids a hosted game that stops showing up here, so every game it may still score must be listed. */
public class GameListRequestHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final var plugin = context.plugin();
        final JsonObject responseData = new JsonObject();
        responseData.add("games", plugin.getGson().toJsonTree(plugin.getGameManager().getGames()));
        context.messageReplier().accept(responseData);
    }
}
