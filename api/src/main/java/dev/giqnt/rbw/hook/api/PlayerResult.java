package dev.giqnt.rbw.hook.api;

import java.util.UUID;

public record PlayerResult(UUID uuid, String name, int kills, int bedsBroken) {
}
