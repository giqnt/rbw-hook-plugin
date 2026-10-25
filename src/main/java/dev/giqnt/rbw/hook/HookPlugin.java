package dev.giqnt.rbw.hook;

import com.google.gson.Gson;
import dev.giqnt.rbw.hook.adapter.Adapter;
import dev.giqnt.rbw.hook.adapter.AdapterFactory;
import dev.giqnt.rbw.hook.leaderboard.LeaderboardManager;
import dev.giqnt.rbw.hook.placeholder.RBWPlaceholderExpansion;
import dev.giqnt.rbw.hook.profile.PlayerProfileManager;
import dev.giqnt.rbw.hook.update.UpdateManager;
import dev.giqnt.rbw.hook.utils.APIUtils;
import dev.giqnt.rbw.hook.websocket.WebSocketManager;
import lombok.Getter;
import okhttp3.HttpUrl;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public class HookPlugin extends JavaPlugin {
    @Getter
    private final Gson gson = new Gson();
    private File configFile;
    @Getter
    private ConfigHolder configHolder;
    private WebSocketManager webSocketManager;
    @Getter
    private APIUtils api;
    @Getter
    private LeaderboardManager leaderboardManager;
    @Getter
    private PlayerProfileManager playerProfileManager;
    @Getter
    private Adapter bedWars;
    private RBWPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        this.configFile = new File(getDataFolder(), "config.yml");
        reloadConfig();
        if (this.configHolder.apiUrl().isEmpty() || this.configHolder.token().isEmpty()) {
            getLogger().severe("Set api-url and token (issued on the RBW dashboard) in config.yml, then restart.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (HttpUrl.parse(this.configHolder.apiUrl()) == null) {
            getLogger().severe("api-url must be an http(s) URL, e.g. https://rbw.example.com");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.api = new APIUtils(
                this.configHolder.apiUrl(),
                this.configHolder.token(),
                this.configHolder.proxy(),
                getDescription().getVersion()
        );
        this.leaderboardManager = new LeaderboardManager(this);
        this.playerProfileManager = new PlayerProfileManager(this);
        this.bedWars = AdapterFactory.getAdapter(this);
        this.webSocketManager = new WebSocketManager(this);

        webSocketManager.connect();
        this.leaderboardManager.init();
        this.playerProfileManager.init();
        new UpdateManager(this, this.api.httpClient(), getFile().getName()).init();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderExpansion = new RBWPlaceholderExpansion(this);
            this.placeholderExpansion.register();
        }
        if (this.bedWars instanceof Listener adapter) {
            Bukkit.getPluginManager().registerEvents(adapter, this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::updateMaps, 20 * 30, 20 * 30);
    }

    @Override
    public void onDisable() {
        if (this.placeholderExpansion != null) {
            this.placeholderExpansion.unregister();
        }
        if (this.webSocketManager != null) {
            this.webSocketManager.close();
            this.webSocketManager = null;
        }
    }

    @Override
    public void reloadConfig() {
        configHolder = ConfigHolder.load(YamlConfiguration.loadConfiguration(configFile));
        saveConfig();
    }

    @Override
    public void saveConfig() {
        if (configHolder == null) return;
        try {
            configHolder.toYaml().save(configFile);
        } catch (final IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save config to " + configFile, ex);
        }
    }

    /** Main thread only. */
    public void updateMaps() {
        if (!this.bedWars.canHostGames()) return;
        final var maps = this.bedWars.getMaps();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                this.api.request("/maps", "PUT", gson.toJson(maps));
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Failed to update maps data", e);
            }
        });
    }
}
