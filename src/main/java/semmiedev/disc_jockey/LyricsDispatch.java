package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sends lyric lines as private messages to the players standing near the user, so a song does not
 * have to be broadcast to the whole server.
 * <p>
 * Vanilla throttles chat and commands separately with roughly one message per second (and a
 * disconnect for non operators above that), so private lyrics can either be sent as a single
 * command using a target selector, or one command per player, which then has to be spread over
 * time. {@link Mode#AUTO} tries the selector first and falls back to the round robin when the
 * server refuses selectors.
 */
public final class LyricsDispatch {
    /** Above this many nearby players the private mode is likely to trip the server's spam filter. */
    public static final int WARN_TARGET_COUNT = 15;

    /** How long a selector probe may stay unanswered before it counts as accepted. */
    private static final long SELECTOR_PROBE_MILLIS = 3000;

    /**
     * Highest burst that still fits into the vanilla spam window: the server disconnects a non
     * operator on the tenth command, so nine commands can be spent in one go.
     */
    public static final int MAX_COMMAND_BURST = 9;
    /** Long term command rate vanilla tolerates: one per second. */
    private static final double COMMANDS_PER_SECOND = 1.0;

    /** Commands left in the burst, or -1 before the first line has been sent. */
    private static double availableCommands = -1;
    private static long lastRefillAt = System.currentTimeMillis();

    private static boolean selectorRefused;
    private static long selectorProbeAt = -1;
    private static boolean warnedAboutSelector;

    private LyricsDispatch() {
    }

    /**
     * How many commands may be sent back to back. Vanilla builds
     * {@code commandSpamThrottler = new TickThrottler(20, 20 * commandSpamThresholdSeconds)}, so with
     * the default ten second threshold a non operator is disconnected on the tenth command inside the
     * window. Staying below that keeps a whole lyric line deliverable in one go.
     */
    public static int commandBurst() {
        return Math.max(1, Math.min(MAX_COMMAND_BURST, Main.config.lyricsDmBurst));
    }

    /** Players inside the configured radius, nearest first, capped at the configured maximum. */
    public static List<AbstractClientPlayer> nearbyPlayers() {
        List<AbstractClientPlayer> result = playersInRadiusList();
        if (result.isEmpty()) return result;

        Minecraft client = Minecraft.getInstance();
        result.sort(Comparator.comparingDouble(client.player::distanceToSqr));

        // A line is only sent when it fits the command budget as a whole, so a target limit above
        // the burst would make every line too expensive and silently stop all delivery. The
        // effective number of recipients is clamped to the burst instead.
        int maximum = Math.max(1, Math.min(40, Math.min(Main.config.lyricsDmMaxTargets, commandBurst())));
        return result.size() > maximum ? new ArrayList<>(result.subList(0, maximum)) : result;
    }

    /**
     * How many players are inside the configured radius, before the recipients are capped to the
     * burst. The cap can only ever be nine players, so a warning that has to count the players
     * around the user has to ask for this number instead of {@link #targetCount()}.
     */
    public static int playersInRadius() {
        return playersInRadiusList().size();
    }

    /** Players inside the configured radius, in the order the level reports them. */
    private static List<AbstractClientPlayer> playersInRadiusList() {
        Minecraft client = Minecraft.getInstance();
        List<AbstractClientPlayer> result = new ArrayList<>();
        if (client.player == null || client.level == null) return result;

        int radius = Math.max(1, Math.min(40, Main.config.lyricsDmRadius));
        double radiusSquared = (double) radius * radius;
        for (AbstractClientPlayer other : client.level.players()) {
            if (other == client.player) continue;
            if (client.player.distanceToSqr(other) <= radiusSquared) result.add(other);
        }
        return result;
    }

    public static void send(ClientPacketListener connection, String message) {
        List<AbstractClientPlayer> targets = nearbyPlayers();
        if (targets.isEmpty()) return;

        String command = Main.config.lyricsCommand;
        if (command == null || command.isBlank()) command = "msg";

        if (useSelector()) {
            Minecraft client = Minecraft.getInstance();
            String self = client.player == null ? "" : client.player.getScoreboardName();
            int radius = Math.max(1, Math.min(40, Main.config.lyricsDmRadius));
            String selector = "@a[distance=.." + radius + (self.isEmpty() ? "" : ",name=!" + self) + "]";
            connection.sendCommand(command + " " + selector + " " + message);
            if (!selectorRefused && selectorProbeAt == -1) selectorProbeAt = System.currentTimeMillis();
            return;
        }

        // Without permission to use selectors vanilla can only address one name per /msg command, so
        // a line has to be sent once per recipient. That spends a command per player, and vanilla
        // disconnects players who send more than roughly one command per second (with a small burst),
        // so a line is either delivered to everybody in range or skipped - never split across players.
        if (!takeCommands(targets.size())) return;
        for (AbstractClientPlayer target : targets) {
            connection.sendCommand(command + " " + target.getScoreboardName() + " " + message);
        }
    }

    /**
     * True while target selectors are the preferred way of addressing the players nearby. Selectors
     * need operator permissions on most servers, so the first refusal switches to one command per
     * player for the rest of the session.
     */
    public static boolean useSelector() {
        return Main.config.lyricsUseSelector && !selectorRefused;
    }

    /** True while a selector command is waiting to see whether the server accepts it. */
    public static boolean awaitingSelectorProbe() {
        if (!useSelector() || selectorProbeAt == -1) return false;
        if (System.currentTimeMillis() - selectorProbeAt > SELECTOR_PROBE_MILLIS) {
            // No complaint arrived, so keep using the selector.
            selectorProbeAt = -1;
            return false;
        }
        return true;
    }

    /** Called when the server answered that selectors are not allowed for this player. */
    public static void onSelectorRefused() {
        if (selectorRefused) return;
        selectorRefused = true;
        selectorProbeAt = -1;
        if (warnedAboutSelector) return;
        warnedAboutSelector = true;
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
                    Main.MOD_ID + ".lyrics.selector_not_allowed"));
        }
    }

    /**
     * Consumes the command budget for one whole lyric line. Returns false when the line cannot be
     * delivered to every player in range right now, in which case it is skipped rather than being
     * sent to only some of them.
     */
    private static synchronized boolean takeCommands(int commands) {
        int burst = commandBurst();
        if (availableCommands < 0 || availableCommands > burst) {
            // First line of the session, or the user lowered the budget: start from a full burst
            // instead of keeping a larger one from the previous setting.
            availableCommands = burst;
            lastRefillAt = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        double refill = (now - lastRefillAt) / 1000.0 * COMMANDS_PER_SECOND;
        if (refill > 0) {
            availableCommands = Math.min(burst, availableCommands + refill);
            lastRefillAt = now;
        }
        if (commands > burst || availableCommands < commands) return false;
        availableCommands -= commands;
        return true;
    }

    /** Number of players that would currently receive private lyrics. */
    public static int targetCount() {
        return nearbyPlayers().size();
    }
}
