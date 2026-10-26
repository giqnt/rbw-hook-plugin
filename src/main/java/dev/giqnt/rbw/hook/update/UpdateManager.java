package dev.giqnt.rbw.hook.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.giqnt.rbw.hook.HookPlugin;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Bukkit swaps update-folder jars on the next start.
 * Never reload in place: sessions and pending results live only in memory.
 */
@RequiredArgsConstructor
public class UpdateManager {
    private static final String LATEST_RELEASE = "https://api.github.com/repos/giqnt/rbw-hook-plugin/releases/latest";
    private static final String JAR_ASSET = "rbw-hook.jar";
    private static final String CHECKSUM_ASSET = JAR_ASSET + ".sha256";
    // Unauthenticated GitHub API calls are limited to 60 an hour per IP, shared with anything else on the host.
    private static final long CHECK_INTERVAL_TICKS = 20L * 60 * 60 * 6;

    private final HookPlugin plugin;
    private final OkHttpClient client;
    // Bukkit matches update jars to installed plugins by file name.
    private final String jarName;
    private @Nullable Version staged;

    public void init() {
        if (!plugin.getConfigHolder().autoUpdate()) return;
        final String running = plugin.getDescription().getVersion();
        final Optional<Version> current = Version.parse(running);
        if (current.isEmpty()) {
            plugin.getLogger().info("Auto-update is off for development build " + running);
            return;
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> check(current.get()), 20 * 60, CHECK_INTERVAL_TICKS);
    }

    private void check(final Version current) {
        try {
            final JsonObject release = get(LATEST_RELEASE, body -> JsonParser.parseString(body.string()).getAsJsonObject());
            final String tag = release.get("tag_name").getAsString();
            final Version latest = Version.parse(tag)
                    .orElseThrow(() -> new IOException("Unexpected release tag " + tag));
            if (latest.compareTo(current) <= 0 || latest.equals(staged)) return;
            final String expected = get(assetUrl(release, CHECKSUM_ASSET), body -> body.string().trim().split("\\s+")[0])
                    .toLowerCase(Locale.ROOT);
            stage(assetUrl(release, JAR_ASSET), expected);
            staged = latest;
            plugin.getLogger().info("Downloaded rbw-hook " + latest + ". It is applied on the next server restart.");
        } catch (final IOException e) {
            plugin.getLogger().warning("Failed to check for rbw-hook updates: " + e.getMessage());
        } catch (final RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check for rbw-hook updates", e);
        }
    }

    private void stage(final String url, final String expectedSha256) throws IOException {
        final File folder = Bukkit.getUpdateFolderFile();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Could not create " + folder);
        }
        final Path target = folder.toPath().resolve(jarName);
        final Path partial = Files.createTempFile(folder.toPath(), jarName + ".", ".part");
        try {
            final String actual = get(url, body -> {
                final MessageDigest digest = sha256();
                try (InputStream in = new DigestInputStream(body.byteStream(), digest);
                     OutputStream out = Files.newOutputStream(partial)) {
                    in.transferTo(out);
                }
                return HexFormat.of().formatHex(digest.digest());
            });
            if (!actual.equals(expectedSha256)) {
                throw new IOException("Checksum mismatch: expected " + expectedSha256 + ", got " + actual);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static String assetUrl(final JsonObject release, final String name) throws IOException {
        for (final JsonElement asset : release.getAsJsonArray("assets")) {
            final JsonObject obj = asset.getAsJsonObject();
            if (name.equals(obj.get("name").getAsString())) {
                return obj.get("browser_download_url").getAsString();
            }
        }
        throw new IOException("Release has no " + name);
    }

    private <T> T get(final String url, final BodyHandler<T> handler) throws IOException {
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            final ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Unexpected code " + response.code() + " from " + url);
            }
            return handler.handle(body);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface BodyHandler<T> {
        T handle(ResponseBody body) throws IOException;
    }
}
