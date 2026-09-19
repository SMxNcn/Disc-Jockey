package semmiedev.disc_jockey.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.PlaylistManager;
import semmiedev.disc_jockey.Song;

import java.util.List;

/**
 * The playlist panel. Rows mirror {@link PlaylistManager#entries()}; a right pointing
 * arrow at the right edge of a row removes that entry again.
 */
public class PlaylistWidget extends ObjectSelectionList<PlaylistWidget.PlaylistEntry> {
    /** Called after the selection changed through a click, so the screen can clear the other list. */
    public Runnable onSelectionChanged;

    public PlaylistWidget(Minecraft client, int width, int height, int top, int itemHeight) {
        super(client, width, height, top, itemHeight);
    }

    @Override
    public int getRowWidth() {
        return width - 22;
    }

    public @Nullable PlaylistEntry getSelectedEntry() {
        return getSelected() instanceof PlaylistEntry entry ? entry : null;
    }

    /** Rebuilds the rows from the playlist, keeping the selection when the song is still present. */
    public void refresh() {
        Song previouslySelected = getSelectedEntry() == null ? null : getSelectedEntry().song;

        clearEntries();
        setFocused(null);

        List<Song> songs = PlaylistManager.entries();
        for (int i = 0; i < songs.size(); i++) {
            PlaylistEntry entry = new PlaylistEntry(this, songs.get(i), i);
            addEntry(entry, defaultEntryHeight);
        }

        PlaylistEntry toSelect = null;
        if (previouslySelected != null) {
            for (PlaylistEntry entry : children()) {
                if (entry.song.relativePath.equals(previouslySelected.relativePath)) {
                    toSelect = entry;
                    break;
                }
            }
        }
        setSelected(toSelect);
    }

    @Override
    public void updateWidgetNarration(@NonNull NarrationElementOutput builder) {
        // Who cares
    }

    public static class PlaylistEntry extends Entry<PlaylistEntry> {
        private static final Identifier ICONS = Identifier.fromNamespaceAndPath(Main.MOD_ID, "textures/gui/icons.png");

        private final PlaylistWidget widget;
        public final Song song;
        public final int index;

        public PlaylistEntry(PlaylistWidget widget, Song song, int index) {
            this.widget = widget;
            this.song = song;
            this.index = index;
        }

        @Override
        public @NonNull Component getNarration() {
            return Component.literal(song.displayName);
        }

        @Override
        public void extractContent(@NonNull GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            int x = getX();
            int y = getY();
            int entryWidth = getWidth();
            int entryHeight = getHeight();

            boolean isPlaying = PlaylistManager.isPlayingPlaylist() && PlaylistManager.cursor() == index;
            boolean isSelected = this.equals(widget.getSelected());

            if (isPlaying) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFF2E5E33);
            } else if (isSelected) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0xFF000000);
            }

            Minecraft client = Minecraft.getInstance();
            String label = (index + 1) + ". " + song.displayName;
            String trimmed = client.font.plainSubstrByWidth(label, entryWidth - 32);
            // The playing row gets a bright green label on top of its green background, and a song
            // with a paired .lrc file gets a marker right after its name.
            int color = isPlaying ? 0xFF55FF55 : (isSelected ? 0xFFFFFFFF : 0xFFCFCFCF);
            context.text(client.font, trimmed, x + 3, y + 6, color);
            if (song.lyrics != null) {
                int iconX = Math.min(x + 3 + client.font.width(trimmed) + 3, x + entryWidth - 34);
                context.blit(RenderPipelines.GUI_TEXTURED, ICONS, iconX, y + 5, 0.0f, 36.0f, 13, 12, 52, 48);
            }

            boolean overRemove = isOverRemoveButton(mouseX, mouseY) && hovered;            if (hovered) {
                if (overRemove) {
                    context.setTooltipForNextFrame(Component.translatable(Main.MOD_ID + ".screen.playlist.remove"), mouseX, mouseY);
                } else {
                    context.setTooltipForNextFrame(Component.literal(song.displayName), mouseX, mouseY);
                }
            }

            // Right pointing arrow at the far right of the row, on the playlist icon row.
            int u = overRemove ? 39 : 26;
            context.blit(RenderPipelines.GUI_TEXTURED, ICONS, x + entryWidth - 17, y + 3, (float) u, 12.0f, 13, 12, 52, 48);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            if (isOverRemoveButton(mouseX, mouseY)) {
                // The rows are rebuilt on the next screen tick, so the list is not mutated
                // while it is being iterated here.
                PlaylistManager.remove(song);
                return true;
            }
            widget.setSelected(this);
            if (widget.onSelectionChanged != null) widget.onSelectionChanged.run();
            // Double clicking a row switches straight to that song. PlaylistManager#play stops
            // whatever is running first, so this works both while playing and while idle.
            if (doubleClick && event.button() == 0) PlaylistManager.play(song);
            return true;
        }

        private boolean isOverRemoveButton(int mouseX, int mouseY) {
            int iconX = getX() + getWidth() - 17;
            int iconY = getY() + 3;
            return mouseX > iconX && mouseX < iconX + 13 && mouseY > iconY && mouseY < iconY + 12;
        }
    }
}
