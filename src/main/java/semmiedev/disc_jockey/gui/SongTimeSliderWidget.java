package semmiedev.disc_jockey.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;

public class SongTimeSliderWidget extends AbstractSliderButton {

    public SongTimeSliderWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), 0);
    }

    private static String padZeroes(int number) {
        StringBuilder builder = new StringBuilder(Integer.toString(number));
        while (builder.length() < 2) {
            builder.insert(0, '0');
        }
        return builder.toString();
    }

    public static String formatTimestamp(int seconds) {
        return padZeroes(seconds / 60) + ":" + padZeroes(seconds % 60);
    }

    @Override
    protected void updateMessage() {
        // The bar itself stays unlabelled: the screen draws elapsed and total time below it,
        // scaled by the current playback speed.
        setMessage(Component.empty());
    }

    @Override
    protected void applyValue() {
        if (Main.SONG_PLAYER.song == null) return;
        double total = Main.SONG_PLAYER.song.getLengthInSeconds();
        double seconds = this.value * total;
        Main.SONG_PLAYER.setSongElapsedSeconds(seconds);
    }

    public void update() {
        if (Main.SONG_PLAYER.song == null) return;
        double elapsed = Main.SONG_PLAYER.getSongElapsedSeconds();
        double total = Main.SONG_PLAYER.song.getLengthInSeconds();
        this.value = elapsed / total;
        updateMessage();
    }
}
