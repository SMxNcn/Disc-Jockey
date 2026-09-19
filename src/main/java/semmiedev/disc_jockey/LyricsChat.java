package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Pushes lyric lines into the game, either to public chat or to the players nearby.
 * <p>
 * Everything goes through the vanilla chat and command paths, so no other mod is required on the
 * server. Sending is rate limited because vanilla disconnects players who exceed roughly one chat
 * message or command per second.
 */
public final class LyricsChat {
    /** Vanilla rejects longer chat messages and commands. */
    private static final int MAX_LENGTH = 256;

    private static long lastSentAt;

    private LyricsChat() {
    }

    /** Registers the listener that notices when a server refuses target selectors. */
    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!LyricsDispatch.awaitingSelectorProbe()) return;
            if (!(message.getContents() instanceof TranslatableContents contents)) return;
            if (!"argument.entity.selector.not_allowed".equals(contents.getKey())) return;
            LyricsDispatch.onSelectorRefused();
        });
    }

    public static void send(String text) {
        String message = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (message.isEmpty()) return;
        if (message.length() > MAX_LENGTH) message = message.substring(0, MAX_LENGTH);

        long now = System.currentTimeMillis();
        if (now - lastSentAt < Math.max(100, Main.config.lyricsMinIntervalMs)) return;

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        lastSentAt = now;

        if (Main.config.lyricsOutputToPublic) {
            connection.sendChat(message);
        } else {
            LyricsDispatch.send(connection, message);
        }
    }

    /** Warns the player that the private mode is spread over many players. Returns true if warned. */
    public static boolean shouldWarnAboutTargets() {
        // Counted before the recipients are capped to the burst, which never exceeds nine players.
        return LyricsDispatch.playersInRadius() > LyricsDispatch.WARN_TARGET_COUNT;
    }

    public static Component warningText(int targets, int intervalMillis) {
        return Component.translatable(Main.MOD_ID + ".lyrics.dm_warning", targets, Math.max(1, intervalMillis / 1000));
    }
}
