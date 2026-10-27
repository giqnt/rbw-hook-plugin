package dev.giqnt.rbw.hook.api;

import org.jspecify.annotations.Nullable;

public class GameCreateException extends RuntimeException {
    private final @Nullable String mapName;

    public GameCreateException(String message) {
        this(message, (String) null);
    }

    public GameCreateException(String message, @Nullable String mapName) {
        super(message);
        this.mapName = mapName;
    }

    public GameCreateException(String message, Throwable cause) {
        super(message, cause);
        this.mapName = null;
    }

    public @Nullable String getMapName() {
        return mapName;
    }
}
