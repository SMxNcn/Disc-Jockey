package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Owns the user's playlist: the ordered entries, the playback cursor and the
 * repeat/shuffle state.
 * <p>
 * Entries are persisted as paths relative to the songs folder (see {@link Config#playlist}),
 * mirroring how favourites and per-song speeds are stored. {@link SongLoader#loadSongs()}
 * recreates every {@link Song} instance, so this class never keeps {@link Song} references
 * across a reload: {@link #resolveFromConfig()} rebuilds them from the stored paths.
 */
public class PlaylistManager implements ClientTickEvents.StartLevelTick {
    /** Resolved playlist entries in the user's order. Only touched on the client thread. */
    public static final ArrayList<Song> ENTRIES = new ArrayList<>();

    private static final ArrayList<Integer> SHUFFLE_ORDER = new ArrayList<>();
    private static final Random RANDOM = new Random();

    /** Index into {@link #ENTRIES} that playlist playback is on, or -1 when inactive. */
    private static int cursor = -1;
    private static boolean endHandled = true;

    public PlaylistManager() {
        Main.TICK_LISTENERS.add(this);
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    /**
     * Rebuilds the resolved entries from the stored paths. Entries whose file can no
     * longer be found are dropped (after a same-file-name move heuristic), matching how
     * the mod already prunes favourites and per-song speeds.
     */
    public static void resolveFromConfig() {
        ArrayList<String> resolvedPaths = new ArrayList<>();

        ENTRIES.clear();
        for (String storedPath : Main.config.playlist) {
            Song song = findByRelativePath(storedPath);
            if (song == null) song = findByMovedFile(storedPath);
            if (song == null) {
                Main.LOGGER.warn("Playlist entry '{}' no longer exists, dropping it", storedPath);
                continue;
            }
            if (resolvedPaths.contains(song.relativePath)) continue;
            resolvedPaths.add(song.relativePath);
            ENTRIES.add(song);
        }

        if (!resolvedPaths.equals(Main.config.playlist)) {
            Main.config.playlist.clear();
            Main.config.playlist.addAll(resolvedPaths);
            Main.configHolder.save();
        }

        // Keep the cursor on the same song across a reload instead of on the same index.
        Song playing = Main.SONG_PLAYER.song;
        int index = playing == null ? -1 : indexOf(playing);
        cursor = index;
        rebuildShuffleOrder();
    }

    private static Song findByRelativePath(String relativePath) {
        for (Song song : SongLoader.SONGS) {
            if (song.relativePath.equals(relativePath)) return song;
        }
        return null;
    }

    /**
     * Same heuristic {@link SongPlayer#loadSongSpeed(Song)} uses for per-song speeds: if the
     * stored path no longer exists but a loaded song has the same file name, the file was
     * most likely moved, so the entry is migrated instead of dropped.
     */
    private static Song findByMovedFile(String storedPath) {
        Path fileName = Path.of(storedPath).getFileName();
        if (fileName == null) return null;
        if (Files.exists(Main.songsFolder.toPath().resolve(storedPath))) return null;
        for (Song song : SongLoader.SONGS) {
            Path candidate = Path.of(song.relativePath).getFileName();
            if (candidate != null && candidate.toString().equals(fileName.toString())) return song;
        }
        return null;
    }

    private static void save() {
        Main.configHolder.save();
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    public static List<Song> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    public static boolean contains(Song song) {
        return indexOf(song) >= 0;
    }

    public static int indexOf(Song song) {
        if (song == null) return -1;
        for (int i = 0; i < ENTRIES.size(); i++) {
            if (sameSong(ENTRIES.get(i), song)) return i;
        }
        return -1;
    }

    /** Index of the entry playlist playback is currently on, or -1. */
    public static int cursor() {
        return cursor;
    }

    public static boolean isPlayingPlaylist() {
        return cursor >= 0 && cursor < ENTRIES.size() && sameSong(ENTRIES.get(cursor), Main.SONG_PLAYER.song);
    }

    private static boolean sameSong(Song a, Song b) {
        return a != null && b != null && a.relativePath.equals(b.relativePath);
    }

    // ------------------------------------------------------------------
    // editing
    // ------------------------------------------------------------------

    /** @return true if the song was added, false if it already was in the playlist. */
    public static boolean add(Song song) {
        if (song == null || contains(song)) return false;
        ENTRIES.add(song);
        Main.config.playlist.add(song.relativePath);
        rebuildShuffleOrder();
        save();
        return true;
    }

    /** @return true if the song was removed, false if it was not in the playlist. */
    public static boolean remove(Song song) {
        int index = indexOf(song);
        if (index < 0) return false;
        ENTRIES.remove(index);
        Main.config.playlist.remove(song.relativePath);
        // Keep the cursor pointing at the same entry when something before it is removed.
        if (cursor == index) {
            cursor = -1;
        } else if (cursor > index) {
            cursor--;
        }
        rebuildShuffleOrder();
        save();
        return true;
    }

    public static void clear() {
        ENTRIES.clear();
        Main.config.playlist.clear();
        SHUFFLE_ORDER.clear();
        cursor = -1;
        save();
    }

    // ------------------------------------------------------------------
    // repeat / shuffle state
    // ------------------------------------------------------------------

    public static Config.RepeatMode mode() {
        return Main.config.repeatMode == null ? Config.RepeatMode.SEQUENTIAL : Main.config.repeatMode;
    }

    public static void setMode(Config.RepeatMode mode) {
        Main.config.repeatMode = mode;
        // SEQUENTIAL/PLAYLIST advance track by track, so the single-song repeat must be off.
        Main.SONG_PLAYER.loopSong = mode == Config.RepeatMode.SINGLE;
        save();
    }

    /** Cycles SEQUENTIAL -> PLAYLIST -> SINGLE -> SEQUENTIAL. */
    public static Config.RepeatMode cycleMode() {
        Config.RepeatMode next = switch (mode()) {
            case SEQUENTIAL -> Config.RepeatMode.PLAYLIST;
            case PLAYLIST -> Config.RepeatMode.SINGLE;
            case SINGLE -> Config.RepeatMode.SEQUENTIAL;
        };
        setMode(next);
        return next;
    }

    public static boolean shuffle() {
        return Main.config.shufflePlaylist;
    }

    public static void setShuffle(boolean value) {
        Main.config.shufflePlaylist = value;
        rebuildShuffleOrder();
        save();
    }

    public static boolean toggleShuffle() {
        setShuffle(!shuffle());
        return shuffle();
    }

    private static void rebuildShuffleOrder() {
        SHUFFLE_ORDER.clear();
        for (int i = 0; i < ENTRIES.size(); i++) SHUFFLE_ORDER.add(i);
        Collections.shuffle(SHUFFLE_ORDER, RANDOM);
    }

    /** Indices in the order they should be played in, honouring the shuffle setting. */
    private static ArrayList<Integer> activeOrder() {
        if (!shuffle()) {
            ArrayList<Integer> natural = new ArrayList<>(ENTRIES.size());
            for (int i = 0; i < ENTRIES.size(); i++) natural.add(i);
            return natural;
        }
        // The playlist may have changed since the order was built.
        if (SHUFFLE_ORDER.size() != ENTRIES.size()) rebuildShuffleOrder();
        return new ArrayList<>(SHUFFLE_ORDER);
    }

    // ------------------------------------------------------------------
    // playback
    // ------------------------------------------------------------------

    /** Starts a playlist entry, making it the current playlist position. */
    public static void play(Song song) {
        int index = indexOf(song);
        if (index < 0) return;
        cursor = index;
        Main.SONG_PLAYER.start(ENTRIES.get(index));
    }

    /** Starts a song outside of the playlist context (playing straight from the song list). */
    public static void playOneShot(Song song) {
        cursor = -1;
        Main.SONG_PLAYER.start(song);
    }

    public static void stop() {
        cursor = -1;
        Main.SONG_PLAYER.stop();
    }

    /**
     * The entry {@code step} positions away in the active order, wrapping around.
     * Returns null when the playlist is empty.
     */
    public static Song peek(int step) {
        if (ENTRIES.isEmpty()) return null;
        ArrayList<Integer> order = activeOrder();
        int position = order.indexOf(cursor);
        if (position < 0) return ENTRIES.get(order.get(0));
        return ENTRIES.get(order.get(Math.floorMod(position + step, order.size())));
    }

    /** Manual next/previous: always wraps around so the buttons never appear dead. */
    public static void skip(int step) {
        Song target = peek(step);
        if (target != null) play(target);
    }

    /**
     * The entry auto-advance should move to, or null when playback has to stop instead.
     * SEQUENTIAL stops after the last entry; PLAYLIST wraps around.
     */
    private static Song advanceTarget() {
        if (ENTRIES.isEmpty()) return null;
        // Playback was not started from a playlist row (a one-shot from the song list, or it
        // was stopped), so there is nothing to advance to.
        if (cursor < 0) return null;
        ArrayList<Integer> order = activeOrder();
        int position = order.indexOf(cursor);
        if (position < 0) return ENTRIES.get(order.get(0));
        boolean last = position == order.size() - 1;
        if (last && mode() == Config.RepeatMode.SEQUENTIAL) return null;
        return ENTRIES.get(order.get((position + 1) % order.size()));
    }

    /**
     * Keeps {@link SongPlayer#loopSong} in sync with the stored repeat mode. Called once after
     * the config has been loaded, since the flag is otherwise only written when the mode changes.
     */
    public static void syncPlayerState() {
        Main.SONG_PLAYER.loopSong = mode() == Config.RepeatMode.SINGLE;
    }

    @Override
    public void onStartTick(@NonNull ClientLevel level) {
        if (!Main.SONG_PLAYER.didSongReachEnd) {
            endHandled = false;
            return;
        }
        if (endHandled) return;
        endHandled = true;
        onSongEnded();
    }

    private static void onSongEnded() {
        // The song itself repeats: SongPlayer normally already restarted it through its own
        // loopSong flag, so only step in when that did not happen.
        if (mode() == Config.RepeatMode.SINGLE) {
            Song song = Main.SONG_PLAYER.song;
            if (song != null && !Main.SONG_PLAYER.running) Main.SONG_PLAYER.start(song);
            return;
        }
        Song next = advanceTarget();
        if (next != null) play(next);
    }
}
