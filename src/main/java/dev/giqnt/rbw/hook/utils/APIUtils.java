package dev.giqnt.rbw.hook.utils;

import dev.giqnt.rbw.hook.ConfigHolder;
import okhttp3.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;

public class APIUtils {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final String apiUrl;
    private final String token;
    private final OkHttpClient client;
    private volatile @Nullable String adapter;

    public APIUtils(
            final String apiUrl,
            final String token,
            final ConfigHolder.ProxyConfigHolder proxyConfig,
            final String version
    ) {
        this.apiUrl = apiUrl.replaceFirst("/+$", "");
        this.token = token;
        final String userAgent = "rbw-hook/" + version;
        // The api records this per token so the panel shows which jar each game server runs.
        // The client also fetches GitHub downloads: the api token stays per-request, never in an interceptor.
        final OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .pingInterval(Duration.ofSeconds(30))
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder().header("User-Agent", userAgent).build()
                ));
        if (proxyConfig.enabled()) {
            final String ip = proxyConfig.ip();
            final int port = proxyConfig.port();
            final String username = proxyConfig.username();
            final String password = proxyConfig.password();
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ip, port)))
                    .proxyAuthenticator((route, response) -> {
                        String credential = Credentials.basic(username, password);
                        return response
                                .request()
                                .newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    });
        }
        this.client = builder.build();
    }

    @NonNull
    public OkHttpClient httpClient() {
        return client;
    }

    /** Main thread writes it, request threads read it. */
    public void setAdapter(@Nullable final String adapter) {
        this.adapter = adapter;
    }

    @NonNull
    public WebSocket newWebSocket(@NonNull final String endpoint, @NonNull final WebSocketListener listener) {
        return client.newWebSocket(apiRequest(apiUrl.replaceFirst("^http", "ws") + endpoint).build(), listener);
    }

    @NonNull
    public String request(
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body
    ) throws IOException {
        return request(endpoint, method, body, response -> {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            final var responseBody = response.body();
            return responseBody == null ? "" : responseBody.string();
        });
    }

    /** The tenant comes from the gameserver token, so paths carry no server name. */
    public <T> T request(
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body,
            @NonNull final ResponseHandler<T> handler
    ) throws IOException {
        final Request request = apiRequest(apiUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint))
                .addHeader("Content-Type", "application/json")
                .method(method, body == null ? null : RequestBody.create(body, JSON))
                .build();
        try (final Response response = client.newCall(request).execute()) {
            return handler.handle(response);
        }
    }

    private Request.Builder apiRequest(final String url) {
        final Request.Builder builder = new Request.Builder().url(url).addHeader("Authorization", "Bearer " + token);
        final String adapter = this.adapter;
        if (adapter != null) builder.addHeader("X-RBW-Adapter", adapter);
        return builder;
    }

    @FunctionalInterface
    public interface ResponseHandler<T> {
        T handle(Response response) throws IOException;
    }
}
