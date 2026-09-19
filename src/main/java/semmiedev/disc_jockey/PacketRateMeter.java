package semmiedev.disc_jockey;

/**
 * Counts the packets the mod itself sends to the server so the song selection screen can show how
 * hard playback is currently hitting the server's packet limits.
 * <p>
 * Only note block interaction packets are counted (look, swing, block actions and tuning
 * interacts), never chat or command traffic, because this number exists to judge whether the
 * playback will look like cheating to a server. Counts come from both the playback thread and the
 * client thread, hence the synchronization.
 */
public final class PacketRateMeter {
    /** One second is measured as this many buckets, so the window slides smoothly. */
    private static final int BUCKETS = 10;
    private static final long BUCKET_MILLIS = 100L;

    private static final long[] BUCKET_INTERVAL = new long[BUCKETS];
    private static final int[] BUCKET_COUNT = new int[BUCKETS];

    private PacketRateMeter() {
    }

    /** Registers one packet that the mod just sent to the server. */
    public static synchronized void count() {
        long interval = System.currentTimeMillis() / BUCKET_MILLIS;
        int slot = (int) Math.floorMod(interval, BUCKETS);
        if (BUCKET_INTERVAL[slot] != interval) {
            BUCKET_INTERVAL[slot] = interval;
            BUCKET_COUNT[slot] = 0;
        }
        BUCKET_COUNT[slot]++;
    }

    /** Packets sent during the last second. */
    public static synchronized int perSecond() {
        long now = System.currentTimeMillis() / BUCKET_MILLIS;
        int total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            // Buckets are reused, so only count the ones that fall inside the window.
            if (BUCKET_INTERVAL[i] != 0 && now - BUCKET_INTERVAL[i] < BUCKETS) total += BUCKET_COUNT[i];
        }
        return total;
    }

    /** Green below the green threshold, yellow up to the yellow threshold, red above it. */
    public static int color() {
        int greenMax = Math.max(0, Main.config.packetRateGreenMax);
        int yellowMax = Math.max(greenMax, Main.config.packetRateYellowMax);
        int rate = perSecond();
        if (rate <= greenMax) return 0xFF55FF55;
        if (rate <= yellowMax) return 0xFFFFFF55;
        return 0xFFFF5555;
    }

    /** Used by the screen to show the rate next to the playback controls. */
    public static String text() {
        return perSecond() + " pkt/s";
    }
}
