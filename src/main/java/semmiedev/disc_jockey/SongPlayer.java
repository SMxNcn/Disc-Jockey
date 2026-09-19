package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static net.minecraft.ChatFormatting.RED;

public class SongPlayer implements ClientTickEvents.StartLevelTick {
    private record NotePrediction(int assumedNote, long expiryTime) {}

    private static boolean warned;
    public boolean running;
    public Song song;

    private int index;
    private double tick; // Aka song position
    private HashMap<NoteBlockInstrument, HashMap<Byte, BlockPos>> noteBlocks = null;
    public boolean tuned;
    private long lastPlaybackTickAt = -1L;

    // Used to check and enforce packet rate limits to not get kicked
    private long last100MsSpanAt = -1L;
    private int last100MsSpanEstimatedPackets = 0;
    // If higher than current millis, don't send any packets of this kind (temp disable)
    private long reducePacketsUntil = -1L, stopPacketsUntil = -1L;

    // Use to limit swings and look to only each tick. More will not be visually visible anyway due to interpolation
    private long lastLookSentAt = -1L, lastSwingSentAt = -1L;

    // The thread executing the tickPlayback method
    private Thread playbackThread = null;
    public long playbackLoopDelay = 5;
    // Just for external debugging purposes
    public HashMap<Block, Integer> missingInstrumentBlocks = new HashMap<>();
    public volatile float speed = 1.0f;
    private Song speedSong;

    private long lastInteractAt = -1;
    private float availableInteracts = 8;
    private int tuneInitialUntunedBlocks = -1;
    private final HashMap<BlockPos, NotePrediction> notePredictions = new HashMap<>();
    public boolean didSongReachEnd = false;
    public boolean loopSong = false;
    private long pausePlaybackUntil = -1L; // Set after tuning, if configured

    public SongPlayer() {
        Main.TICK_LISTENERS.add(this);
    }

    public @NotNull HashMap<NoteBlockInstrument, @Nullable NoteBlockInstrument> instrumentMap = new HashMap<>(); // Toy
    public synchronized void startPlaybackThread() {
        if (Main.config.disableAsyncPlayback) {
            playbackThread = null;
            return;
        }

        this.playbackThread = Thread.ofVirtual().unstarted(() -> {
            Thread ownThread = Thread.currentThread();
            while (this.playbackThread == ownThread) {
                try {
                    // Accuracy doesn't really matter at this precision imo
                    Thread.sleep(playbackLoopDelay);
                }catch (Exception ex) {
                    Main.LOGGER.warn("Exception in playback thread", ex);
                }
                tickPlayback();
            }
        });
        this.playbackThread.start();
    }

    public synchronized void stopPlaybackThread() {
        this.playbackThread = null; // Should stop on its own then
    }

    public synchronized void start(Song song) {
        if (!Main.config.hideWarning && !warned) {
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.translatable("disc_jockey.warning").copy().withStyle(ChatFormatting.BOLD).withStyle(RED));
            warned = true;
            return;
        }
        if (running) stop();
        this.song = song;
        loadSongSpeed(song);
        //Main.LOGGER.info("Song length: " + song.length + " and tempo " + song.tempo);
        //Main.TICK_LISTENERS.add(this);
        if (this.playbackThread == null) startPlaybackThread();
        running = true;
        lastPlaybackTickAt = System.currentTimeMillis();
        last100MsSpanAt = System.currentTimeMillis();
        last100MsSpanEstimatedPackets = 0;
        reducePacketsUntil = -1L;
        stopPacketsUntil = -1L;
        lastLookSentAt = -1L;
        lastSwingSentAt = -1L;
        missingInstrumentBlocks.clear();
        didSongReachEnd = false;
    }

    public synchronized void loadSongSpeed(Song song) {
        speedSong = song;

        /*
         * Heuristic search: if the configuration has no songSpeeds data for the target song,
         * scan all songSpeeds entries. If an entry's filename exactly matches the current
         * song's filename and the file cannot be found at the path that entry points to,
         * we can infer that the current song file was moved from the old path to the new
         * path, while the configuration still points to the old path.
         * So, delete the old key from the configuration file, create a new key with the same
         * speed data, and load it.
         */
        if (!Main.config.songSpeeds.containsKey(song.relativePath)) {
            String fileName = Path.of(song.relativePath).getFileName().toString();
            String oldKey = null;
            for (String key : Main.config.songSpeeds.keySet()) {
                Path oldPath = Path.of(key);
                if (oldPath.getFileName().toString().equals(fileName)
                        && Files.notExists(Main.songsFolder.toPath().resolve(oldPath))) {
                    oldKey = key;
                    break;
                }
            }
            if (oldKey != null) {
                String savedSpeed = Main.config.songSpeeds.remove(oldKey);
                Main.config.songSpeeds.put(song.relativePath, savedSpeed);
                Main.configHolder.save();
            }
        }
        speed = Float.parseFloat(Main.config.songSpeeds.getOrDefault(song.relativePath, "1"));
    }

    public synchronized void setSpeed(String speed) {
        this.speed = Float.parseFloat(speed);
        if (speedSong != null) {
            Main.config.songSpeeds.put(speedSong.relativePath, speed);
            Main.configHolder.save();
        }
    }

    public synchronized void stop() {
        //MinecraftClient.getInstance().send(() -> Main.TICK_LISTENERS.remove(this));
        stopPlaybackThread();
        running = false;
        index = 0;
        tick = 0;
        noteBlocks = null;
        notePredictions.clear();
        tuned = false;
        tuneInitialUntunedBlocks = -1;
        lastPlaybackTickAt = -1L;
        last100MsSpanAt = -1L;
        last100MsSpanEstimatedPackets = 0;
        reducePacketsUntil = -1L;
        stopPacketsUntil = -1L;
        lastLookSentAt = -1L;
        lastSwingSentAt = -1L;
        didSongReachEnd = false; // Change after running stop() if actually ended cleanly
    }

    public synchronized void tickPlayback() {
        if (!running) {
            lastPlaybackTickAt = -1L;
            last100MsSpanAt = -1L;
            return;
        }
        long previousPlaybackTickAt = lastPlaybackTickAt;
        lastPlaybackTickAt = System.currentTimeMillis();
        if (last100MsSpanAt != -1L && System.currentTimeMillis() - last100MsSpanAt >= 100) {
            last100MsSpanEstimatedPackets = 0;
            last100MsSpanAt = System.currentTimeMillis();
        } else if (last100MsSpanAt == -1L) {
            last100MsSpanAt = System.currentTimeMillis();
            last100MsSpanEstimatedPackets = 0;
        }
        if (noteBlocks != null && tuned) {
            if (pausePlaybackUntil != -1L && System.currentTimeMillis() <= pausePlaybackUntil) return;
            while (running) {
                Minecraft client = Minecraft.getInstance();
                GameType gameType = client.gameMode == null ? null : client.gameMode.getPlayerMode();
                // In the best case, gameMode would only be queried in sync Ticks, no here
                if (gameType == null || !gameType.isSurvival()) {
                    client.gui.hud.getChat().addClientSystemMessage(Component.translatable(Main.MOD_ID + ".player.invalid_game_mode", gameType == null ? "unknown" : gameType.getLongDisplayName()).copy().withStyle(RED));
                    stop();
                    return;
                }

                long note = song.notes[index];
                final long now = System.currentTimeMillis();
                if ((short)note <= Math.round(tick)) {
                    @Nullable BlockPos blockPos = noteBlocks.get(Note.INSTRUMENTS[(byte)(note >> Note.INSTRUMENT_SHIFT)]).get((byte)(note >> Note.NOTE_SHIFT));
                    if (blockPos == null) {
                        // Instrument got likely mapped to "nothing". Skip it
                        index++;
                        continue;
                    }
                    if (!canInteractWith(client.player, blockPos)) {
                        stop();
                        client.gui.hud.getChat().addClientSystemMessage(Component.translatable(Main.MOD_ID + ".player.to_far").copy().withStyle(RED));
                        return;
                    }
                    Vec3 unit = Vec3.atCenterOf(blockPos).subtract(client.player.getEyePosition()).normalize();
                    // At how many packets/100ms should the player just reduce / stop sending packets for a while
                    int last100MsReducePacketsAfter = 300 / 10;
                    if ((lastLookSentAt == -1L || now - lastLookSentAt >= 50) && last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.getConnection().send(new ServerboundMovePlayerPacket.Rot(Mth.wrapDegrees((float) (Mth.atan2(unit.z, unit.x) * 57.2957763671875) - 90.0f), Mth.wrapDegrees((float) (-(Mth.atan2(unit.y, Math.sqrt(unit.x * unit.x + unit.z * unit.z)) * 57.2957763671875))), true, false));
                        PacketRateMeter.count();
                        last100MsSpanEstimatedPackets++;
                        lastLookSentAt = now;
                    } else if (last100MsSpanEstimatedPackets >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }
                    int last100MsStopPacketsAfter = 450 / 10;
                    if (last100MsSpanEstimatedPackets < last100MsStopPacketsAfter && (stopPacketsUntil == -1L || stopPacketsUntil < now)) {
                        // TODO: 5/30/2022 Check if the block needs tuning
                        //client.interactionManager.attackBlock(blockPos, Direction.UP);
                        client.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                        PacketRateMeter.count();
                        last100MsSpanEstimatedPackets++;
                    } else if (last100MsSpanEstimatedPackets >= last100MsStopPacketsAfter) {
                        Main.LOGGER.info("Stopping all packets for a bit!");
                        stopPacketsUntil = Math.max(stopPacketsUntil, now + 250);
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 10000);
                    }
                    if (last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                        PacketRateMeter.count();
                        last100MsSpanEstimatedPackets++;
                    } else if (last100MsSpanEstimatedPackets >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }
                    if ((lastSwingSentAt == -1L || now - lastSwingSentAt >= 50) &&last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.submit(() -> client.player.swing(InteractionHand.MAIN_HAND));
                        PacketRateMeter.count();
                        lastSwingSentAt = now;
                        last100MsSpanEstimatedPackets++;
                    } else if (last100MsSpanEstimatedPackets  >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }

                    index++;
                    if (index >= song.notes.length) {
                        stop();
                        didSongReachEnd = true;
                        if (loopSong) {
                            start(song);
                        }
                        break;
                    }
                } else {
                    break;
                }
            }

            if (running) { // Might not be running anymore (prevent small offset on song, even if that is not played anymore)
                long elapsedMs = previousPlaybackTickAt != -1L && lastPlaybackTickAt != -1L ? lastPlaybackTickAt - previousPlaybackTickAt : (16); // Assume 16ms if unknown
                tick += song.millisecondsToTicks(elapsedMs) * speed;
            }
        }
    }

    @Override
    public void onStartTick(@NonNull ClientLevel world) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (song == null || !running) return;

        // Clear outdated note predictions
        notePredictions.entrySet().removeIf(entry -> entry.getValue().expiryTime() < System.currentTimeMillis());

        if (noteBlocks == null) {
            noteBlocks = new HashMap<>();

            LocalPlayer player = client.player;

            // Create list of available noteblock positions per used instrument
            HashMap<NoteBlockInstrument, ArrayList<BlockPos>> noteblocksForInstrument = new HashMap<>();
            for (NoteBlockInstrument instrument : NoteBlockInstrument.values())
                noteblocksForInstrument.put(instrument, new ArrayList<>());
            final Vec3 playerEyePos = player.getEyePosition();

            final int maxOffset = switch (Main.config.expectedServerVersion) {
                case v1_20_4_Or_Earlier -> 7;
                case v1_20_5_Or_Later -> (int) Math.ceil(player.blockInteractionRange() + 1.0 + 1.0);
                case All -> Math.min(7, (int) Math.ceil(player.blockInteractionRange() + 1.0 + 1.0));
            };
            final ArrayList<Integer> orderedOffsets = new ArrayList<>();
            for (int offset = 0; offset <= maxOffset; offset++) {
                orderedOffsets.add(offset);
                if (offset != 0) orderedOffsets.add(offset * -1);
            }

            for (NoteBlockInstrument instrument : noteblocksForInstrument.keySet().toArray(new NoteBlockInstrument[0])) {
                for (int y : orderedOffsets) {
                    for (int x : orderedOffsets) {
                        for (int z : orderedOffsets) {
                            Vec3 vec3d = playerEyePos.add(x, y, z);
                            BlockPos blockPos = BlockPos.containing(vec3d.x, vec3d.y, vec3d.z);
                            if (!canInteractWith(player, blockPos))
                                continue;
                            BlockState blockState = world.getBlockState(blockPos);
                            if (blockState.getBlock() != Blocks.NOTE_BLOCK || !world.isEmptyBlock(blockPos.above()))
                                continue;

                            if (blockState.getValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT) == instrument)
                                noteblocksForInstrument.get(instrument).add(blockPos);
                        }
                    }
                }
            }

            // Remap instruments for funzies
            if (!instrumentMap.isEmpty()) {
                HashMap<NoteBlockInstrument, ArrayList<BlockPos>> newNoteblocksForInstrument = new HashMap<>();
                for (NoteBlockInstrument orig : noteblocksForInstrument.keySet()) {
                    NoteBlockInstrument mappedInstrument = instrumentMap.getOrDefault(orig, orig);
                    if (mappedInstrument == null) {
                        // Instrument got likely mapped to "nothing"
                        newNoteblocksForInstrument.put(orig, null);
                        continue;
                    }

                    newNoteblocksForInstrument.put(orig, noteblocksForInstrument.getOrDefault(instrumentMap.getOrDefault(orig, orig), new ArrayList<>()));
                }
                noteblocksForInstrument = newNoteblocksForInstrument;
            }

            // Find fitting noteblocks with the least amount of adjustments required (to reduce tuning time)
            ArrayList<Note> capturedNotes = new ArrayList<>();
            for (Note note : song.uniqueNotes) {
                ArrayList<BlockPos> availableBlocks = noteblocksForInstrument.get(note.instrument());
                if (availableBlocks == null) {
                    // Note was mapped to "nothing". Pretend it got captured, but just ignore it
                    capturedNotes.add(note);
                    getNotes(note.instrument()).put(note.note(), null);
                    continue;
                }
                BlockPos bestBlockPos = null;
                int bestBlockTuningSteps = Integer.MAX_VALUE;
                for (BlockPos blockPos : availableBlocks) {
                    int wantedNote = note.note();
                    int currentNote = client.level.getBlockState(blockPos).getValue(BlockStateProperties.NOTE);
                    int tuningSteps = wantedNote >= currentNote ? wantedNote - currentNote : (25 - currentNote) + wantedNote;

                    if (tuningSteps < bestBlockTuningSteps) {
                        bestBlockPos = blockPos;
                        bestBlockTuningSteps = tuningSteps;
                    }
                }

                if (bestBlockPos != null) {
                    capturedNotes.add(note);
                    availableBlocks.remove(bestBlockPos);
                    getNotes(note.instrument()).put(note.note(), bestBlockPos);
                } // else will be a missing note
            }

            ArrayList<Note> missingNotes = new ArrayList<>(song.uniqueNotes);
            missingNotes.removeAll(capturedNotes);
            if (!missingNotes.isEmpty()) {
                ChatComponent chatHud = Minecraft.getInstance().gui.hud.getChat();
                chatHud.addClientSystemMessage(Component.translatable(Main.MOD_ID + ".player.invalid_note_blocks").copy().withStyle(RED));

                HashMap<Block, Integer> missing = new HashMap<>();
                for (Note note : missingNotes) {
                    NoteBlockInstrument mappedInstrument = instrumentMap.getOrDefault(note.instrument(), note.instrument());
                    if (mappedInstrument == null) continue; // Ignore if mapped to nothing
                    Block block = Note.INSTRUMENT_BLOCKS.get(mappedInstrument);
                    Integer got = missing.get(block);
                    if (got == null) got = 0;
                    missing.put(block, got + 1);
                }

                missingInstrumentBlocks = missing;
                missing.forEach((block, integer) -> chatHud.addClientSystemMessage(Component.literal(block.getName().getString() + " × " + integer).copy().withStyle(RED)));
                stop();
            }
        } else if (!tuned) {
            //tuned = true;

            int ping = 0;
            {
                PlayerInfo playerListEntry;
                if (client.getConnection() != null && (playerListEntry = client.getConnection().getPlayerInfo(client.player.getUUID())) != null)
                    ping = playerListEntry.getLatency();
            }

            if (lastInteractAt != -1L) {
                // Paper allows 8 interacts per 300 ms (actually 9 it turns out, but lets keep it a bit lower anyway)
                availableInteracts += ((System.currentTimeMillis() - lastInteractAt) / (310.0f / 8.0f));
                availableInteracts = Math.min(8f, Math.max(0f, availableInteracts));
            } else {
                availableInteracts = 8f;
                lastInteractAt = System.currentTimeMillis();
            }

            int fullyTunedBlocks = 0;
            HashMap<BlockPos, Integer> untunedNotes = new HashMap<>();
            for (Note note : song.uniqueNotes) {
                if (noteBlocks == null || noteBlocks.get(note.instrument()) == null)
                    continue;
                BlockPos blockPos = noteBlocks.get(note.instrument()).get(note.note());
                if (blockPos == null) continue;
                BlockState blockState = world.getBlockState(blockPos);
                int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).assumedNote() : blockState.getValue(BlockStateProperties.NOTE);

                if (blockState.hasProperty(BlockStateProperties.NOTE)) {
                    if (assumedNote == note.note() && blockState.getValue(BlockStateProperties.NOTE) == note.note())
                        fullyTunedBlocks++;
                    if (assumedNote != note.note()) {
                        if (!canInteractWith(client.player, blockPos)) {
                            stop();
                            client.gui.hud.getChat().addClientSystemMessage(Component.translatable(Main.MOD_ID + ".player.to_far").copy().withStyle(RED));
                            return;
                        }
                        untunedNotes.put(blockPos, blockState.getValue(BlockStateProperties.NOTE));
                    }
                } else {
                    noteBlocks = null;
                    break;
                }
            }

            if (tuneInitialUntunedBlocks == -1 || tuneInitialUntunedBlocks < untunedNotes.size())
                tuneInitialUntunedBlocks = untunedNotes.size();

            int existingUniqueNotesCount = 0;
            for (Note n : song.uniqueNotes) {
                if (noteBlocks.get(n.instrument()).get(n.note()) != null)
                    existingUniqueNotesCount++;
            }

            if (untunedNotes.isEmpty() && fullyTunedBlocks == existingUniqueNotesCount) {
                // Wait roundrip + 100ms before considering tuned after changing notes (in case the server rejects an interact)
                if (lastInteractAt == -1 || System.currentTimeMillis() - lastInteractAt >= ping * 2L + 100) {
                    tuned = true;
                    pausePlaybackUntil = System.currentTimeMillis() + (long) (Math.abs(Main.config.delayPlaybackStartBySecs) * 1000);
                    tuneInitialUntunedBlocks = -1;
                    // Tuning finished
                }
            }

            BlockPos lastBlockPos = null;
            int lastTunedNote = Integer.MIN_VALUE;
            float roughTuneProgress = 1 - (untunedNotes.size() / Math.max(tuneInitialUntunedBlocks + 0f, 1f));
            while (availableInteracts >= 1f && !untunedNotes.isEmpty()) {
                BlockPos blockPos = null;
                int searches = 0;
                while (blockPos == null) {
                    searches++;
                    // Find higher note
                    for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                        if (entry.getValue() > lastTunedNote) {
                            blockPos = entry.getKey();
                            break;
                        }
                    }
                    // Find higher note or equal
                    if (blockPos == null) {
                        for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                            if (entry.getValue() >= lastTunedNote) {
                                blockPos = entry.getKey();
                                break;
                            }
                        }
                    }
                    // Not found. Reset last note
                    if (blockPos == null)
                        lastTunedNote = Integer.MIN_VALUE;
                    if (blockPos == null && searches > 1) {
                        // Something went wrong. Take any note (one should at least exist here)
                        blockPos = untunedNotes.keySet().toArray(new BlockPos[0])[0];
                        break;
                    }
                }
                if (blockPos == null) return; // Something went very, very wrong!

                lastTunedNote = untunedNotes.get(blockPos);
                untunedNotes.remove(blockPos);
                int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).assumedNote() : client.level.getBlockState(blockPos).getValue(BlockStateProperties.NOTE);
                notePredictions.put(blockPos, new NotePrediction((assumedNote + 1) % 25, System.currentTimeMillis() + ping * 2L + 100));
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(blockPos), Direction.UP, blockPos, false));
                PacketRateMeter.count();
                lastInteractAt = System.currentTimeMillis();
                availableInteracts -= 1f;
                lastBlockPos = blockPos;
            }
            if (lastBlockPos != null) {
                // Turn head into spinning with time and lookup up further the further tuning is progressed
                //client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(((float) (System.currentTimeMillis() % 2000)) * (360f/2000f), (1 - roughTuneProgress) * 180 - 90, true));
                client.player.swing(InteractionHand.MAIN_HAND);
                PacketRateMeter.count();
            }
        } else if ((playbackThread == null || !playbackThread.isAlive()) && running && Main.config.disableAsyncPlayback) {
            // Sync playback (off by default). Replacement for playback thread
            try {
                tickPlayback();
            }catch (Exception ex) {
                Main.LOGGER.warn("Exception in sync playback", ex);
                stop();
            }
        }
    }

    private HashMap<Byte, BlockPos> getNotes(NoteBlockInstrument instrument) {
        return noteBlocks.computeIfAbsent(instrument, _ -> new HashMap<>());
    }

    // Before 1.20.5, the server limits interacts to 6 Blocks from Player Eye to Block Center
    // With 1.20.5 and later, the server does a more complex check, to the closest point of a full block hitbox
    // (max distance is BlockInteractRange + 1.0).
    private boolean canInteractWith(LocalPlayer player, BlockPos blockPos) {
        final Vec3 eyePos = player.getEyePosition();
        return switch (Main.config.expectedServerVersion) {
            case v1_20_4_Or_Earlier -> eyePos.distanceToSqr(Vec3.atCenterOf(blockPos)) <= 6.0 * 6.0;
            case v1_20_5_Or_Later -> {
                double blockInteractRange = player.blockInteractionRange() + 1.0;
                yield new AABB(blockPos).distanceToSqr(eyePos) < blockInteractRange * blockInteractRange;
            }
            case All -> {
                // Require both checks to succeed (aka use worst distance)
                double blockInteractRange = player.blockInteractionRange() + 1.0;
                yield eyePos.distanceToSqr(Vec3.atCenterOf(blockPos)) <= 6.0 * 6.0
                        && new AABB(blockPos).distanceToSqr(eyePos) < blockInteractRange * blockInteractRange;
            }
        };
    }

    public double getSongElapsedSeconds() {
        if (song == null) return 0;
        return song.ticksToMilliseconds(tick) / 1000;
    }

    public synchronized void setSongElapsedSeconds(double seconds) {
        if (song == null) return;
        tick = song.millisecondsToTicks((long)(seconds * 1000));
        index = 0;
        for (int i = 0; i < song.notes.length; i++) {
            if ((short)song.notes[i] > Math.round(tick)) break;
            index = i;
        }
    }
}
