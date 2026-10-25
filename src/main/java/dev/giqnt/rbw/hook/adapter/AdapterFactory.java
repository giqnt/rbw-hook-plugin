package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.HookPlugin;
import org.jspecify.annotations.Nullable;

public class AdapterFactory {
    public static @Nullable BedWars1058Adapter getAdapter(final HookPlugin plugin) {
        if (isClassLoaded("com.andrei1058.bedwars.BedWars")) {
            return new BedWars1058Adapter(plugin.getConfigHolder().groupPrefix());
        } else {
            plugin.getLogger().severe("No BedWars plugin detected, this server can't host games");
            return null;
        }
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
