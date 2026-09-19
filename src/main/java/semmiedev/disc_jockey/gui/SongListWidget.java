package semmiedev.disc_jockey.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.PlaylistManager;
import semmiedev.disc_jockey.Song;

import java.util.Collection;
import java.util.function.Consumer;

public class SongListWidget extends ObjectSelectionList<SongListWidget.ListEntry> {
    private boolean showRelativePath;

    /** Called after the selection changed through a click, so the screen can clear the other list. */
    public Runnable onSelectionChanged;

    public SongListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
        super(client, width, height, top, itemHeight);
    }

    @Override
    public int getRowWidth() {
        return width - 40;
    }

    @Override
    public void setSelected(@Nullable ListEntry entry) {
        SongEntry selectedEntry = getSelectedSongEntry();
        if (selectedEntry != null) selectedEntry.selected = false;
        if (entry instanceof SongEntry songEntry) songEntry.selected = true;
        super.setSelected(entry);
    }

    public @Nullable SongEntry getSelectedSongEntry() {
        return getSelected() instanceof SongEntry entry ? entry : null;
    }

    public void setEntries(Collection<ListEntry> entries, boolean showRelativePath) {
        ListEntry selected = getSelected();
        this.showRelativePath = showRelativePath;
        setSelected(null);
        clearEntries();
        setFocused(null);
        for (ListEntry entry : entries) {
            addEntry(entry, showRelativePath && entry instanceof SongEntry ? 32 : defaultEntryHeight);
        }
        setSelected(entries.contains(selected) ? selected : null);
    }

    @Override
    public void updateWidgetNarration(@NonNull NarrationElementOutput builder) {
        // Who cares
    }

    public abstract static class ListEntry extends Entry<ListEntry> {
        protected final Minecraft client = Minecraft.getInstance();

        protected void drawText(GuiGraphicsExtractor context, String text, int x, int y, int width, int color) {
            context.text(client.font, client.font.plainSubstrByWidth(text, width), x, y, color);
        }
    }

    public static class DirectoryEntry extends ListEntry {
        public final String relativePath;
        private final Consumer<String> openDirectory;

        public DirectoryEntry(String relativePath, Consumer<String> openDirectory) {
            this.relativePath = relativePath;
            this.openDirectory = openDirectory;
        }

        @Override
        public @NonNull Component getNarration() {
            return Component.literal(relativePath);
        }

        @Override
        public void extractContent(@NonNull GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            int x = getX();
            int y = getY();
            context.fill(x + 4, y + 5, x + 10, y + 8, 0xFFD5AE50);
            context.fill(x + 4, y + 8, x + 17, y + 16, 0xFFEAC968);
            String name = relativePath.substring(relativePath.lastIndexOf('/') + 1) + "/";
            drawText(context, name, x + 23, y + 6, getWidth() - 27, 0xFFFFFFFF);
            if (hovered) context.setTooltipForNextFrame(Component.literal(relativePath), mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
            openDirectory.accept(relativePath);
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!event.isConfirmation()) return false;
            openDirectory.accept(relativePath);
            return true;
        }
    }

    public static class SongEntry extends ListEntry {
        private static final Identifier ICONS = Identifier.fromNamespaceAndPath(Main.MOD_ID, "textures/gui/icons.png");

        public final int index;
        public final Song song;

        public boolean selected, favorite;
        public SongListWidget songListWidget;

        private int x;
        private int y;

        public SongEntry(Song song, int index) {
            this.song = song;
            this.index = index;
        }

        @Override
        public @NonNull Component getNarration() {
            return Component.empty();
        }

        @Override
        public void extractContent(@NonNull GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            this.x = getX(); this.y = getY();
            int entryWidth = getWidth();
            int entryHeight = getHeight();

            boolean isSelected = songListWidget != null && this.equals(songListWidget.getSelected());
            if (isSelected) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0xFF000000);
            }

            String nameText = client.font.plainSubstrByWidth(song.displayName, entryWidth - 40);
            context.text(client.font, nameText, x + 36, y + 6, isSelected ? 0xFFFFFFFF : 0xFF808080);
            if (song.lyrics != null) {
                // Marker for a song that has a .lrc file next to it, right after the name.
                int iconX = Math.min(x + 36 + client.font.width(nameText) + 3, x + entryWidth - 16);
                context.blit(RenderPipelines.GUI_TEXTURED, ICONS, iconX, y + 5, 0.0f, 36.0f, 13, 12, 52, 48);
            }
            if (songListWidget.showRelativePath) {
                drawText(context, song.relativePath, x + 36, y + 18, entryWidth - 40, 0xFF808080);
            }

            boolean overPlaylistButton = isOverPlaylistButton(mouseX, mouseY);
            boolean inPlaylist = PlaylistManager.contains(song);
            if (hovered) {
                if (overPlaylistButton) {
                    context.setTooltipForNextFrame(Component.translatable(
                            Main.MOD_ID + (inPlaylist ? ".screen.playlist.added" : ".screen.playlist.add")), mouseX, mouseY);
                } else if (!isOverFavoriteButton(mouseX, mouseY)) {
                    context.setTooltipForNextFrame(Tooltip.splitTooltip(client,
                            Component.literal(song.displayName)), mouseX, mouseY);
                }
            }

            // Playlist button sits to the left of the favourite star: a left pointing triangle
            // means "move into the playlist panel on the left". It is drawn dimmed once the
            // song already is in the playlist.
            int playlistU = inPlaylist ? 0 : (overPlaylistButton ? 13 : 0);
            int playlistV = inPlaylist ? 24 : 12;
            context.blit(RenderPipelines.GUI_TEXTURED, ICONS, x + 4, y + 3, (float) playlistU, (float) playlistV, 13, 12, 52, 48);

            int u = (favorite ? 26 : 0) + (isOverFavoriteButton(mouseX, mouseY) ? 13 : 0);
            context.blit(RenderPipelines.GUI_TEXTURED, ICONS, x + 17, y + 3, (float)u, 0.0f, 13, 12, 52, 48);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            if (isOverPlaylistButton(mouseX, mouseY)) {
                if (!PlaylistManager.contains(song)) PlaylistManager.add(song);
                return true;
            }
            if (isOverFavoriteButton(mouseX, mouseY)) {
                favorite = !favorite;
                if (favorite) {
                    Main.config.favorites.add(song.relativePath);
                } else {
                    Main.config.favorites.remove(song.relativePath);
                }
                return true;
            }
            songListWidget.setSelected(this);
            if (songListWidget.onSelectionChanged != null) songListWidget.onSelectionChanged.run();
            // Double clicking plays the song right away, matching the play button, which treats
            // a song list selection as one-shot playback rather than a playlist position.
            if (doubleClick && event.button() == 0) PlaylistManager.playOneShot(song);
            return true;
        }

        private boolean isOverPlaylistButton(int mouseX, int mouseY) {
            return mouseX > x + 4 && mouseX < x + 17 && mouseY > y + 3 && mouseY < y + 15;
        }

        private boolean isOverFavoriteButton(int mouseX, int mouseY) {
            return mouseX > x + 17 && mouseX < x + 30 && mouseY > y + 3 && mouseY < y + 15;
        }
    }
}
