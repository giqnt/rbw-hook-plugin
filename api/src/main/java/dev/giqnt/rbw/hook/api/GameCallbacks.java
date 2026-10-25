package dev.giqnt.rbw.hook.api;

/**
 * Reports one game's progress back to rbw-hook. Call these on the main thread only: from any other thread they throw
 * {@link IllegalStateException} and change nothing. Only the first of {@link #ended} and {@link #abandoned} counts.
 */
public interface GameCallbacks {
    /** The countdown is over and the game is being played. After this, {@link #abandoned} drops the game instead of failing its start. */
    void started();

    /** The game is over with a winner. Exactly one team must have {@code win} set; a draw is {@link #abandoned}. */
    void ended(GameResult result);

    /**
     * The game is over without a result, a draw included. Before {@link #started()} the api is told the game failed to
     * start and may create it again, so move the players out of the arena first. After it, the game is dropped.
     */
    void abandoned(String reason);
}
