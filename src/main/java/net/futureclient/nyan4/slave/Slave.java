package net.futureclient.nyan4.slave;

import com.seedfinding.latticg.RandomReverser;
import net.futureclient.headless.eventbus.events.PacketEvent;
import net.futureclient.headless.game.HeadlessMinecraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.network.play.server.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;

public final class Slave {
    private static final Logger LOGGER = LogManager.getLogger("Slave");

    public final Minecraft ctx;
    private final ScheduledExecutorService pluginExecutor;

    public Slave(final HeadlessMinecraft ctx, ScheduledExecutorService pluginExecutor) {
        this.ctx = ctx;
        this.pluginExecutor = pluginExecutor;
    }

    public void onPacket(final PacketEvent.Receive event) {
        // NOTE: NETTY THREAD! NOT MAIN THREAD!
        out:
        if (event.getPacket() instanceof SPacketPlayerListItem) {
            final long recTime = System.currentTimeMillis();
            final SPacketPlayerListItem packet = event.getPacket();
            // extreme paranoia about someone leaving the server in the same tick that we establish a connection to the master
            ctx.addScheduledTask(() -> {
                // TODO: sqlite
//                synchronized (this) {
//                    if (connection == null) { // this null check *must* be inside this scheduled task
//                        return;
//                    }
//                    if (packet.getAction() == SPacketPlayerListItem.Action.ADD_PLAYER || packet.getAction() == SPacketPlayerListItem.Action.REMOVE_PLAYER) {
//                        for (SPacketPlayerListItem.AddPlayerData data : packet.getEntries()) {
//                            connection.outgoing.add(connection.new PlayerJoinLeavePacket(data.getProfile(), packet.getAction() == SPacketPlayerListItem.Action.ADD_PLAYER));
//                        }
//                    }
//                }
            });
        } else if (event.getPacket() instanceof SPacketSpawnObject) {
            final SPacketSpawnObject packet = event.getPacket();
            // the type for item entities is 2 and the data is always 1
            if (packet.getType() != 2 || packet.getData() != 1) {
                break out;
            }
            // dropped items from blocks always have motionY 0.2 which when encoded by 8000 in the packet comes out to 1600 integer
            if (packet.getSpeedY() != 1600) {
                break out;
            }
            final long recTime = System.currentTimeMillis();
            final double x = packet.getX();
            final double y = packet.getY();
            final double z = packet.getZ();
            ctx.addScheduledTask(() -> {
                // disconnected
                if (ctx.world == null || ctx.player == null) {
                    return;
                }
                // in the queue
                if (probablyInQueue()) {
                    return;
                }
                // not loaded in world
                if (ctx.currentScreen instanceof GuiDownloadTerrain || !ctx.world.isBlockLoaded(new BlockPos(ctx.player), false)) {
                    return;
                }
                final DimensionType dimension = ctx.world.provider.getDimensionType();
                if (!DimensionType.OVERWORLD.equals(dimension)) {
                    return;
                }
                pluginExecutor.execute(() -> {
                    // process async
                    try {
                        processItemDropAsync(x, y, z, recTime);
                    } catch (final Throwable t) {
                        LOGGER.error("Error while processing item drop", t);
                    }
                });
            });
        }
    }
    private boolean probablyInQueue() {
        return ctx.player.isSpectator() || Math.abs(ctx.player.posX) <= 16 && Math.abs(ctx.player.posZ) <= 16;
    }

    private static long nextSeed(long seed) {
        return (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
    }

    private static long prevSeed(long nextseed) {
        return ((nextseed - 0xBL) * 0xdfe05bcb1365L) & ((1L << 48) - 1);
    }

    private static final int WOODLAND_BOUNDS = 23440;

    @SuppressWarnings("UnstableApiUsage")
    private static void processItemDropAsync(final double x, final double y, final double z,
                                             final long timestamp) {
        final long start = System.currentTimeMillis();
        final float rnd1 = ((float) (x - (int) Math.floor(x) - 0.25d)) * 2;
        final float rnd2 = ((float) (y - (int) Math.floor(y) - 0.25d)) * 2;
        final float rnd3 = ((float) (z - (int) Math.floor(z) - 0.25d)) * 2;
        if (rnd1 <= 0 || rnd1 >= 1 || rnd2 <= 0 || rnd2 >= 1 || rnd3 <= 0 || rnd3 >= 1) {
            LOGGER.info("skipping troll item maybe already on ground");
            return;
        }
        final RandomReverser rev = new RandomReverser(new ArrayList<>());
        rev.addNextFloatCall(rnd1, rnd1, true, true);
        rev.addNextFloatCall(rnd2, rnd2, true, true);
        rev.addNextFloatCall(rnd3, rnd3, true, true);
        final long[] found = rev.findAllValidSeeds().toArray();
        if (found.length == 0) {
            insertIntoSqlite(timestamp, -1);
        }
        boolean match = false;
        for (long candidate : found) {
            insertIntoSqlite(timestamp, candidate);
            long stepped = candidate;
            for (int stepsBack = 0; stepsBack < 10000; stepsBack++) {
                long meow = stepped ^ 0x5DEECE66DL;
                ChunkPos pos = woodlandValid(meow); // not a real chunkpos its *80
                if (pos != null) {
                    LOGGER.info("Match at " + pos.x + "," + pos.z + " assuming rng was stepped by " + stepsBack);
                    LOGGER.info("In blocks that's between " + (pos.x * 16 * 80) + "," + (pos.z * 16 * 80) + " and " + ((pos.x * 80 + 79) * 16 + 15) + "," + ((pos.z * 80 + 79) * 16 + 15));
                    LOGGER.info("Match time: " + (System.currentTimeMillis() - start));
                    match = true;
                    break;
                }
                stepped = prevSeed(stepped);
            }
            LOGGER.info("Candidate: " + candidate);
        }
        if (!match) {
            LOGGER.info("No match. Total time: " + (System.currentTimeMillis() - start));
        }
    }

    public static void insertIntoSqlite(long timestamp, long candidate) {
        if (candidate != -1 && candidate != (candidate & ((1L << 48) - 1))) {
            throw new IllegalStateException();
        }
        // runSQL("INSERT INTO raw_data(timestamp, rng_state) VALUES (?, ?)", timestamp, candidate)
        LOGGER.warn("not implemented yet " + timestamp + " " + candidate);
    }

    private static ChunkPos woodlandValid(long candidate) {
        for (int x = -WOODLAND_BOUNDS; x <= WOODLAND_BOUNDS; x++) {
            long z = reverseWoodlandZGivenX(candidate, x);
            if (z >= -WOODLAND_BOUNDS && z <= WOODLAND_BOUNDS) {
                return new ChunkPos(x, (int) z);
            }
        }
        return null;
    }

    private static final long worldseed = -4172144997902289642L;

    private static final long chunkZMultInv248 = 211541297333629L; // https://www.wolframalpha.com/input?i=132897987541%5E-1+mod+2%5E48

    private static long woodlandMansionSeed(int chunkX, int chunkZ) { // from World and WoodlandMansion
        return ((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L + worldseed + (long) 10387319) & ((1L << 48) - 1); // rand drops highest 16 bits anyway
    }

    private static long reverseWoodlandZGivenX(long seed48Bits, int chunkX) {
        return (((seed48Bits - worldseed - 10387319 - (long) chunkX * 341873128712L) & ((1L << 48) - 1)) * chunkZMultInv248) << 16 >> 16;
    }

    public void close() {
        // flush database?
    }
}
