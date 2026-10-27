package dev.giqnt.rbw.hook.api;

import java.util.List;

public record TeamResult(boolean win, List<PlayerResult> players) {
}
