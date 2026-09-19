package semmiedev.disc_jockey;

import com.mojang.blaze3d.platform.InputConstants;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import semmiedev.disc_jockey.gui.hud.BlocksOverlay;
import semmiedev.disc_jockey.gui.screen.DiscJockeyScreen;

import java.io.File;
import java.util.ArrayList;

public class Main implements ClientModInitializer {
    public static final String MOD_ID = "disc_jockey";
    public static final MutableComponent NAME = Component.literal("Disc Jockey");
    public static final Logger LOGGER = LogManager.getLogger("Disc Jockey");
    public static final ArrayList<ClientTickEvents.StartLevelTick> TICK_LISTENERS = new ArrayList<>();
    public static final Previewer PREVIEWER = new Previewer();
    public static final SongPlayer SONG_PLAYER = new SongPlayer();
    public static final PlaylistManager PLAYLIST = new PlaylistManager();

    public static File songsFolder;
    public static Config config;
    public static ConfigHolder<Config> configHolder;

    @Override
    public void onInitializeClient() {
        configHolder = AutoConfig.register(Config.class, JanksonConfigSerializer::new);
        config = configHolder.getConfig();
        PlaylistManager.syncPlayerState();
        LyricsPlayer.register();
        LyricsChat.register();

        songsFolder = new File(FabricLoader.getInstance().getConfigDir() + File.separator + MOD_ID + File.separator + "songs");
        if (!songsFolder.isDirectory()) songsFolder.mkdirs();

        SongLoader.loadSongs();

        KeyMapping openScreenKeyBind = KeyMappingHelper.registerKeyMapping(new KeyMapping(MOD_ID + ".key_bind.open_screen", InputConstants.KEY_J, KeyMapping.Category.MISC));

        ClientTickEvents.START_CLIENT_TICK.register(new ClientTickEvents.StartTick() {
            private ClientLevel prevLevel;

            @Override
            public void onStartTick(@NonNull Minecraft client) {
                if (prevLevel != client.level) {
                    PREVIEWER.stop();
                    SONG_PLAYER.stop();
                }
                prevLevel = client.level;

                if (openScreenKeyBind.consumeClick()) {
                    if (SongLoader.loadingSongs) {
                        client.gui.hud.getChat().addClientSystemMessage(Component.translatable(Main.MOD_ID + ".still_loading").copy().withStyle(ChatFormatting.RED));
                        SongLoader.showToast = true;
                    } else {
                        client.gui.setScreen(new DiscJockeyScreen());
                    }
                }
            }
        });

        ClientTickEvents.START_LEVEL_TICK.register(world -> {
            for (ClientTickEvents.StartLevelTick listener : new ArrayList<>(TICK_LISTENERS)) listener.onStartTick(world);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, _) -> DiscjockeyCommand.register(dispatcher));

        ClientLoginConnectionEvents.DISCONNECT.register((_, _) -> {
            PREVIEWER.stop();
            SONG_PLAYER.stop();
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Main.MOD_ID, "blocks_overlay"), BlocksOverlay::render);
    }
}
