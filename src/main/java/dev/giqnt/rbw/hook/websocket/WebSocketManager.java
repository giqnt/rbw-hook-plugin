package dev.giqnt.rbw.hook.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.websocket.handlers.GameCreateRequestHandler;
import dev.giqnt.rbw.hook.websocket.handlers.GameListRequestHandler;
import dev.giqnt.rbw.hook.websocket.handlers.PlayerMessageSendRequestHandler;
import dev.giqnt.rbw.hook.websocket.handlers.PlayerStateGetRequestHandler;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;

public class WebSocketManager implements Listener {
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);
    private final HookPlugin plugin;
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor();
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean manuallyClosed = new AtomicBoolean(false);
    private final Map<String, MessageHandler> handlerMap = Map.of(
            "player.state.get", new PlayerStateGetRequestHandler(),
            "player.message.send", new PlayerMessageSendRequestHandler(),
            "game.create", new GameCreateRequestHandler(),
            "game.list", new GameListRequestHandler()
    );
    private volatile WebSocket webSocket;

    public WebSocketManager(final HookPlugin plugin) {
        this.plugin = plugin;
        startMessageDispatcher();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void connect() {
        manuallyClosed.set(false);
        doConnect();
    }

    private void doConnect() {
        this.webSocket = plugin.getApi().newWebSocket("/ws/gameserver", new InternalWebSocketListener());
        plugin.getLogger().log(Level.INFO, "[ws] Connecting...");
    }

    private void scheduleReconnect() {
        if (manuallyClosed.get()) {
            plugin.getLogger().log(Level.INFO, "[ws] Manual close, skipping reconnect");
            return;
        }
        plugin.getLogger().log(Level.INFO, String.format("[ws] Reconnecting in %d ms...", RECONNECT_DELAY.toMillis()));
        reconnectScheduler.schedule(this::doConnect, RECONNECT_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void send(final MessageType type, final String action, final JsonObject data) {
        final JsonObject json = new JsonObject();
        json.addProperty("type", type.getValue());
        json.addProperty("action", action);
        json.add("data", data);
        sendText(json.toString());
    }

    public void send(final MessageType type, final String action, final String id, final JsonObject data) {
        final JsonObject json = new JsonObject();
        json.addProperty("type", type.getValue());
        json.addProperty("action", action);
        json.addProperty("id", id);
        json.add("data", data);
        sendText(json.toString());
    }

    private void sendText(String message) {
        messageQueue.add(message);
    }

    private void startMessageDispatcher() {
        senderExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String msg = messageQueue.take();
                    final WebSocket ws = this.webSocket;
                    if (ws != null) {
                        boolean sent = ws.send(msg);
                        if (!sent) {
                            plugin.getLogger().log(Level.WARNING, "[ws] Failed to send: " + msg);
                        }
                    } else {
                        plugin.getLogger().log(Level.WARNING, "[ws] Not connected, dropping: " + msg);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "[ws] Error dispatching message", e
                    );
                }
            }
        });
    }

    public void close() {
        try {
            senderExecutor.shutdownNow();
            if (!senderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().log(Level.SEVERE, "[ws] Timed out while finish sending messages in queue");
            }
        } catch (InterruptedException e) {
            plugin.getLogger().log(Level.SEVERE, "[ws] Interrupted while finish sending messages in queue", e);
        }
        manuallyClosed.set(true);
        final WebSocket ws = this.webSocket;
        if (ws != null) {
            ws.close(1000, "Normal closure");
        }
        reconnectScheduler.shutdown();
    }

    private CompletionStage<Void> handleMessage(final String message) {
        return CompletableFuture.supplyAsync(() -> {
            final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            final MessageType type = MessageType.byValue(json.get("type").getAsString());
            final String action = json.get("action").getAsString();
            final var idOpt = Optional.ofNullable(json.get("id")).map(JsonElement::getAsString);
            final JsonObject data = json.get("data").getAsJsonObject();
            final MessageHandler handler = handlerMap.get(action);
            if (handler == null) {
                plugin.getLogger().log(Level.WARNING, String.format("[ws] No handler for action '%s'", action));
                return CompletableFuture.<Void>completedFuture(null);
            }
            final MessageHandlerContext context = new MessageHandlerContext(
                    plugin,
                    plugin.getLogger(),
                    data,
                    type == MessageType.REQUEST && idOpt.isPresent() ? (msgData) -> {
                        send(MessageType.RESPONSE, action, idOpt.get(), msgData);
                    } : (msgData) -> {
                        throw new UnsupportedOperationException("Reply can only be sent to 'request' type message");
                    }
            );
            return CompletableFuture.runAsync(() -> handler.execute(context),
                    task -> Bukkit.getScheduler().runTask(plugin, task));
        }).thenCompose(Function.identity());
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent e) {
        final JsonObject data = new JsonObject();
        data.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        data.addProperty("name", e.getPlayer().getName());
        data.addProperty("timestamp", Instant.now().toEpochMilli());
        send(MessageType.EVENT, "playerJoin", data);
    }

    private class InternalWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            plugin.getLogger().log(Level.INFO, "[ws] Connected");
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            plugin.getLogger().log(Level.FINE, "[ws] Received: " + text);
            handleMessage(text).whenComplete((v, t) -> {
                if (t != null) {
                    plugin.getLogger().log(Level.SEVERE, "[ws] Handler error", t);
                }
            });
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            plugin.getLogger().log(Level.INFO, String.format("[ws] Closing: %d, %s", code, reason));
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            plugin.getLogger().log(Level.INFO, String.format("[ws] Closed: %d, %s", code, reason));
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            plugin.getLogger().log(Level.SEVERE, "[ws] Connection failure", t);
            scheduleReconnect();
        }
    }
}
