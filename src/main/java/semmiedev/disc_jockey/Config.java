package semmiedev.disc_jockey;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@me.shedaniel.autoconfig.annotation.Config(name = Main.MOD_ID)
@me.shedaniel.autoconfig.annotation.Config.Gui.Background("textures/block/note_block.png")
public class Config implements ConfigData {
    public boolean hideWarning;
    @ConfigEntry.Gui.Tooltip(count = 2) public boolean disableAsyncPlayback;
    @ConfigEntry.Gui.Tooltip(count = 2) public boolean omnidirectionalNoteBlockSounds = true;

    public enum ExpectedServerVersion {
        All,
        v1_20_4_Or_Earlier,
        v1_20_5_Or_Later;

        @Override
        public String toString() {
            return switch (this) {
                case All -> "All (universal)";
                case v1_20_4_Or_Earlier -> "≤1.20.4";
                case v1_20_5_Or_Later -> "≥1.20.5";
            };
        }
    }

    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public ExpectedServerVersion expectedServerVersion = ExpectedServerVersion.All;

    @ConfigEntry.Gui.Tooltip()
    public float delayPlaybackStartBySecs = 0.0f;

    @ConfigEntry.Gui.Excluded
    public ArrayList<String> favorites = new ArrayList<>();

    @ConfigEntry.Gui.Excluded
    public Map<String, String> songSpeeds = new HashMap<>();

    /** Playlist entries, in user order, stored as song paths relative to the songs folder. */
    @ConfigEntry.Gui.Excluded
    public ArrayList<String> playlist = new ArrayList<>();

    /** What happens when a song finishes. */
    public enum RepeatMode {
        /** Play through the playlist once, then stop. */
        SEQUENTIAL,
        /** After the last playlist entry, wrap around to the first. */
        PLAYLIST,
        /** Keep repeating the song that is currently playing. */
        SINGLE
    }

    @ConfigEntry.Gui.Excluded
    public RepeatMode repeatMode = RepeatMode.SEQUENTIAL;

    /** When true, the playlist is traversed in a shuffled order instead of its stored order. */
    @ConfigEntry.Gui.Excluded
    public boolean shufflePlaylist = false;

    /** Packet rate that is still displayed in green, in packets per second. */
    @ConfigEntry.BoundedDiscrete(min = 0, max = 5000)
    @ConfigEntry.Gui.Tooltip()
    public int packetRateGreenMax = 200;

    /** Packet rate that is still displayed in yellow; anything above it is shown in red. */
    @ConfigEntry.BoundedDiscrete(min = 0, max = 5000)
    @ConfigEntry.Gui.Tooltip()
    public int packetRateYellowMax = 500;

    /** Master switch for pushing the lyrics of the playing song into chat. */
    @ConfigEntry.Gui.Excluded
    public boolean lyricsChatOutput = false;

    /** true = public chat, false = private messages to the players standing nearby. */
    @ConfigEntry.Gui.Excluded
    public boolean lyricsOutputToPublic = true;

    /** Minimum delay between two lyric messages, in milliseconds. */
    @ConfigEntry.BoundedDiscrete(min = 100, max = 5000)
    @ConfigEntry.Gui.Tooltip()
    public int lyricsMinIntervalMs = 1000;

    /** Radius in blocks used to find the players that receive private lyrics. */
    @ConfigEntry.BoundedDiscrete(min = 1, max = 40)
    @ConfigEntry.Gui.Tooltip()
    public int lyricsDmRadius = 20;

    /** Upper bound on how many players receive private lyrics, nearest first. */
    @ConfigEntry.BoundedDiscrete(min = 1, max = 40)
    @ConfigEntry.Gui.Tooltip()
    public int lyricsDmMaxTargets = 5;

    /**
     * How many private message commands may be sent back to back for one lyric line. Vanilla
     * throttles commands with {@code new TickThrottler(20, 20 * commandSpamThresholdSeconds)} and
     * disconnects a non operator on the tenth command inside that window, so nine is the highest
     * value the server still accepts.
     */
    @ConfigEntry.BoundedDiscrete(min = 1, max = 9)
    @ConfigEntry.Gui.Tooltip()
    public int lyricsDmBurst = 8;

    /** Command used for private lyrics; some servers disable or rename /msg. */
    @ConfigEntry.Gui.Tooltip()
    public String lyricsCommand = "msg";

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean lyricsUseSelector = true;
}
