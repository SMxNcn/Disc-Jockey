package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.gui.SongListWidget;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class SongLoader {
    public static final String SONG_EXTENSION = ".nbs";
    public static final String LYRICS_EXTENSION = ".lrc";

    /** Note capacity the growable note buffer starts with; it doubles whenever it runs full. */
    private static final int INITIAL_NOTE_CAPACITY = 1024;

    public static final ArrayList<Song> SONGS = new ArrayList<>();
    public static final ArrayList<String> SONG_SUGGESTIONS = new ArrayList<>();
    public static final ArrayList<String> DIRECTORIES = new ArrayList<>();
    public static volatile boolean loadingSongs;
    public static volatile boolean showToast;
    public static int reloadVersion;

    public static void loadSongs() {
        if (loadingSongs) return;
        loadingSongs = true;
        Minecraft client = Minecraft.getInstance();
        Thread.startVirtualThread(() -> {
            ArrayList<Song> loadedSongs = new ArrayList<>();
            ArrayList<String> loadedDirectories = new ArrayList<>();
            ArrayList<Path> loadedLyrics = new ArrayList<>();
            try {
                Files.walkFileTree(Main.songsFolder.toPath(), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        String relativePath = relativePath(directory);
                        if (!relativePath.isEmpty()) loadedDirectories.add(relativePath);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                        if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (name.endsWith(LYRICS_EXTENSION)) {
                            // Lyrics are paired with their song after everything has been read.
                            loadedLyrics.add(path);
                            return FileVisitResult.CONTINUE;
                        }
                        // Anything that is not a song is skipped instead of being fed to the NBS
                        // parser, which used to log an error for every stray file.
                        if (!name.endsWith(SONG_EXTENSION)) return FileVisitResult.CONTINUE;
                        try {
                            Song song = loadSong(path.toFile(), relativePath(path));
                            if (song != null) loadedSongs.add(song);
                        } catch (Exception exception) {
                            Main.LOGGER.error("Unable to read or parse song {}", path, exception);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException exception) {
                Main.LOGGER.error("Unable to reload songs from {}", Main.songsFolder, exception);
                client.execute(() -> {
                    loadingSongs = false;
                    SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.PACK_LOAD_FAILURE, Main.NAME, Component.translatable(Main.MOD_ID + ".loading_failed"));
                });
                return;
            }

            client.execute(() -> {
                SONGS.clear();
                SONG_SUGGESTIONS.clear();
                DIRECTORIES.clear();
                loadedDirectories.sort(String::compareTo);
                DIRECTORIES.addAll(loadedDirectories);
                loadedSongs.forEach(SongLoader::addSong);
                sort();
                Main.config.favorites.removeIf(favorite -> SONGS.stream().map(song -> song.relativePath).noneMatch(favorite::equals));
                // Songs were recreated, so playlist entries have to be resolved again.
                PlaylistManager.resolveFromConfig();
                attachLyrics(loadedLyrics);
                reloadVersion++;
                loadingSongs = false;
                if (showToast) SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.PACK_LOAD_FAILURE, Main.NAME, Component.translatable(Main.MOD_ID + ".loading_done"));
                showToast = true;
            });
        });
    }

    /**
     * Pairs every song with a .lrc file from the same directory: an exactly matching name wins,
     * otherwise the first file whose name starts with the song name followed by a separator (so
     * that "song.nbs" does not pick up "songbook.lrc").
     */
    private static void attachLyrics(List<Path> lyricsFiles) {
        if (lyricsFiles.isEmpty()) return;

        HashMap<String, HashMap<String, Path>> byDirectory = new HashMap<>();
        for (Path path : lyricsFiles) {
            String relative = relativePath(path);
            int separator = relative.lastIndexOf('/');
            String directory = separator < 0 ? "" : relative.substring(0, separator);
            String name = relative.substring(separator + 1);
            name = name.substring(0, name.length() - LYRICS_EXTENSION.length()).toLowerCase(Locale.ROOT);
            byDirectory.computeIfAbsent(directory, key -> new HashMap<>()).put(name, path);
        }

        for (Song song : SONGS) {
            song.lyrics = null;
            String relative = song.relativePath;
            int separator = relative.lastIndexOf('/');
            String directory = separator < 0 ? "" : relative.substring(0, separator);
            String name = relative.substring(separator + 1);
            if (name.toLowerCase(Locale.ROOT).endsWith(SONG_EXTENSION)) {
                name = name.substring(0, name.length() - SONG_EXTENSION.length());
            }
            name = name.toLowerCase(Locale.ROOT);

            HashMap<String, Path> candidates = byDirectory.get(directory);
            if (candidates == null) continue;

            Path match = candidates.get(name);
            if (match == null) {
                String best = null;
                for (String candidate : candidates.keySet()) {
                    if (candidate.length() <= name.length() || !candidate.startsWith(name)) continue;
                    char following = candidate.charAt(name.length());
                    if (following != '.' && following != '_' && following != '-' && following != ' ') continue;
                    if (best == null || candidate.compareTo(best) < 0) best = candidate;
                }
                if (best == null) continue;
                match = candidates.get(best);
                Main.LOGGER.info("Using {} as the lyrics for {}", best + LYRICS_EXTENSION, song.relativePath);
            }

            try {
                song.lyrics = Lyrics.parse(match);
            } catch (IOException exception) {
                Main.LOGGER.warn("Unable to read lyrics {}", match, exception);
            }
        }
    }

    public static void addSong(Song song) {        song.entry = new SongListWidget.SongEntry(song, SONGS.size());
        song.entry.favorite = Main.config.favorites.contains(song.relativePath);
        SONGS.add(song);
        SONG_SUGGESTIONS.add(song.relativePath);
    }

    public static String relativePath(Path path) {
        return Main.songsFolder.toPath().toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
    }

    public static Song loadSong(File file, String relativePath) throws IOException {
        if (!file.isFile()) return null;
        try (var input = Files.newInputStream(file.toPath())) {
            BinaryReader reader = new BinaryReader(input);
            Song song = new Song();

            song.relativePath = relativePath;
            song.fileName = file.getName().replaceAll("[\\n\\r]", "");

            song.length = reader.readShort();

            boolean newFormat = song.length == 0;
            if (newFormat) {
                song.formatVersion = reader.readByte();
                song.vanillaInstrumentCount = reader.readByte();
                song.length = reader.readShort();
            }

            song.height = reader.readShort();
            song.name = reader.readString().replaceAll("[\\n\\r]", "");
            song.author = reader.readString().replaceAll("[\\n\\r]", "");
            song.originalAuthor = reader.readString().replaceAll("[\\n\\r]", "");
            song.description = reader.readString().replaceAll("[\\n\\r]", "");
            song.tempo = reader.readShort();
            song.autoSaving = reader.readByte();
            song.autoSavingDuration = reader.readByte();
            song.timeSignature = reader.readByte();
            song.minutesSpent = reader.readInt();
            song.leftClicks = reader.readInt();
            song.rightClicks = reader.readInt();
            song.blocksAdded = reader.readInt();
            song.blocksRemoved = reader.readInt();
            song.importFileName = reader.readString().replaceAll("[\\n\\r]", "");

            if (newFormat) {
                song.loop = reader.readByte();
                song.maxLoopCount = reader.readByte();
                song.loopStartTick = reader.readShort();
            }

            song.displayName = song.name.replaceAll("\\s", "").isEmpty() ? song.fileName : song.name + " (" + song.fileName + ")";
            song.searchableRelativePath = song.relativePath.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
            song.searchableName = song.name.toLowerCase(Locale.ROOT).replaceAll("\\s", "");

            short tick = -1;
            short jumps;
            // Notes are collected in a buffer that doubles instead of being appended to the song's
            // array with one Arrays.copyOf per note, which copies the whole array for every single
            // note of the song.
            long[] notes = new long[INITIAL_NOTE_CAPACITY];
            int noteCount = 0;
            // uniqueNotes used to be searched with ArrayList#contains, a linear scan over up to a few
            // hundred instrument and key combinations, repeated once per note of the song.
            HashSet<Note> seenNotes = new HashSet<>();
            while ((jumps = reader.readShort()) != 0) {
                tick += jumps;
                short layer = -1;
                while ((jumps = reader.readShort()) != 0) {
                    layer += jumps;

                    byte instrumentId = reader.readByte();
                    byte noteId = (byte)(reader.readByte() - 33);

                    if (newFormat) {
                        // Data that is not needed as it only works with commands
                        reader.readByte(); // Velocity
                        reader.readByte(); // Panning
                        reader.readShort(); // Pitch
                    }

                    if (noteId < 0) {
                        noteId = 0;
                    } else if (noteId > 24) {
                        noteId = 24;
                    }

                    Note note = new Note(Note.INSTRUMENTS[instrumentId], noteId);
                    if (seenNotes.add(note)) song.uniqueNotes.add(note);

                    if (noteCount == notes.length) notes = Arrays.copyOf(notes, notes.length * 2);
                    notes[noteCount++] = tick | layer << Note.LAYER_SHIFT | (long)instrumentId << Note.INSTRUMENT_SHIFT | (long)noteId << Note.NOTE_SHIFT;
                }
            }

            song.notes = Arrays.copyOf(notes, noteCount);

            return song;
        }
    }

    public static void sort() {
        SONGS.sort(Comparator.comparing((Song song) -> song.displayName).thenComparing(song -> song.relativePath));
    }
}
