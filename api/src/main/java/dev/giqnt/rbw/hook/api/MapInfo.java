package dev.giqnt.rbw.hook.api;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A map a server can host. {@code category} is the queue's map category and {@code name} is what game.create picks
 * from. {@code buildHeight}, the highest buildable Y, is optional and shown to players. Two maps are the same if their
 * category and name match.
 */
public record MapInfo(
        String category,
        String name,
        @Nullable Integer buildHeight
) {
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        MapInfo mapInfo = (MapInfo) obj;
        return Objects.equals(category, mapInfo.category) && Objects.equals(name, mapInfo.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, name);
    }
}
