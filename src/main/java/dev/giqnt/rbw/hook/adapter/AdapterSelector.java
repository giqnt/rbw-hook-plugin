package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.api.BedWarsAdapter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;

import java.util.stream.Collectors;

/**
 * Hosts games on the highest priority {@link BedWarsAdapter} in the ServicesManager. The built-in BedWars1058 adapter
 * registers at the lowest priority, so any adapter another plugin registers takes over.
 */
public class AdapterSelector implements Listener {
    private final HookPlugin plugin;
    private final @Nullable BedWars1058Adapter builtIn;
    private @Nullable BedWarsAdapter selected;

    public AdapterSelector(final HookPlugin plugin) {
        this.plugin = plugin;
        this.builtIn = isClassLoaded("com.andrei1058.bedwars.BedWars")
                ? new BedWars1058Adapter(plugin.getConfigHolder().groupPrefix())
                : null;
    }

    public void init() {
        if (builtIn != null) {
            Bukkit.getServicesManager().register(BedWarsAdapter.class, builtIn, plugin, ServicePriority.Lowest);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        select();
        if (selected == null) {
            plugin.getLogger().warning("No BedWars adapter registered, this server can't host games");
        }
    }

    @EventHandler
    public void onServiceRegister(final ServiceRegisterEvent event) {
        if (event.getProvider().getService() == BedWarsAdapter.class) select();
    }

    @EventHandler
    public void onServiceUnregister(final ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == BedWarsAdapter.class) select();
    }

    private void select() {
        final var registration = Bukkit.getServicesManager().getRegistration(BedWarsAdapter.class);
        final BedWarsAdapter next = registration == null ? null : registration.getProvider();
        if (registration != null) warnIfTied(registration);
        if (next == selected) return;
        // Sessions first: removing the players fires leave events, which must not find a session to call back.
        if (selected == builtIn && builtIn != null) builtIn.clearSessions();
        plugin.getGameManager().setAdapter(next);
        // Only while selected: its join guard would otherwise block the arenas of another adapter's games.
        if (selected == builtIn && builtIn != null) HandlerList.unregisterAll(builtIn);
        if (next == builtIn && builtIn != null) Bukkit.getPluginManager().registerEvents(builtIn, plugin);
        selected = next;
        plugin.getApi().setAdapter(registration == null ? null : reportedPlugin(registration));
        if (registration == null) {
            plugin.getLogger().info("BedWars adapter unregistered, this server can't host games");
        } else {
            plugin.getLogger().info("Using BedWars adapter " + describe(registration));
        }
    }

    private void warnIfTied(final RegisteredServiceProvider<BedWarsAdapter> chosen) {
        final var tied = Bukkit.getServicesManager().getRegistrations(BedWarsAdapter.class).stream()
                .filter(registration -> registration.getPriority() == chosen.getPriority())
                .toList();
        if (tied.size() < 2) return;
        plugin.getLogger().warning("Several BedWars adapters share the priority " + chosen.getPriority() + ": "
                + tied.stream().map(AdapterSelector::describe).collect(Collectors.joining(", "))
                + "; using " + describe(chosen));
    }

    private static String describe(final RegisteredServiceProvider<BedWarsAdapter> registration) {
        return registration.getProvider().getClass().getName() + " from " + registration.getPlugin().getName()
                + " (" + registration.getPriority() + ")";
    }

    /** {@code <name>/<version>} of the BedWars plugin behind the adapter, for the panel. */
    private String reportedPlugin(final RegisteredServiceProvider<BedWarsAdapter> registration) {
        // The built-in adapter is registered by this plugin, but the panel wants the BedWars plugin it drives.
        final Plugin bedWars = builtIn != null && registration.getProvider() == builtIn
                ? builtIn.bedWarsPlugin()
                : registration.getPlugin();
        final String value = bedWars.getName() + "/" + bedWars.getDescription().getVersion();
        // OkHttp throws on a header value outside printable ASCII, and the api drops anything longer than 128.
        final String ascii = value.replaceAll("[^\\x20-\\x7e]", "?");
        return ascii.length() > 128 ? ascii.substring(0, 128) : ascii;
    }

    private static boolean isClassLoaded(final String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
