package net.futureclient.nyan4.slave;

import com.seedfinding.latticg.RandomReverser;
import net.futureclient.headless.eventbus.events.PacketEvent;
import net.futureclient.headless.game.HeadlessMinecraft;
import net.futureclient.nyan4.NyanDatabase;
import net.futureclient.nyan4.Woodland;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketSpawnObject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

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
                LOGGER.info("Processing item drop at y {}", (int) y);
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

    private static boolean couldBeFromRandNextFloat(float f) {
        int next24 = (int) (f * (1 << 24));
        return Float.floatToRawIntBits(f) == Float.floatToRawIntBits(next24 / (float) (1 << 24));
    }

    private static final int WOODLAND_BOUNDS = 23440;

    private static final Set<Long> recentlySlowToProcess = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 100;
        }
    })); // dont store in sqlite obviously, because we are bailing out after only 10k steps, that isn't permanent its just not wanting to use much cpu

    private static final Set<Vec3d> recentlyProcessed = Collections.newSetFromMap(new LinkedHashMap<Vec3d, Boolean>() { // TODO calling super() with accessOrder = true might be more elegant i guess?
        @Override
        protected boolean removeEldestEntry(Map.Entry<Vec3d, Boolean> eldest) {
            return size() > 100;
        }
    });

    private static boolean checkAlreadyProcessed(Vec3d pos) {
        synchronized (recentlyProcessed) {
            // bots standing near each other ingame will get the exact same three doubles
            // save CPU time by not running lattice on the exact same packet twice
            if (recentlyProcessed.contains(pos)) {
                recentlyProcessed.remove(pos);
                recentlyProcessed.add(pos); // move to end of queue
                return true;
            }
            recentlyProcessed.add(pos);
            return false;
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void processItemDropAsync(final double x, final double y, final double z,
                                             final long timestamp) {
        if (checkAlreadyProcessed(new Vec3d(x, y, z))) {
            //LOGGER.info("skipping item drop already processed by another bot");
            return;
        }
        final long start = System.currentTimeMillis();

        final float rnd1 = ((float) (x - (int) Math.floor(x) - 0.25d)) * 2;
        final float rnd2 = ((float) (y - (int) Math.floor(y) - 0.25d)) * 2;
        final float rnd3 = ((float) (z - (int) Math.floor(z) - 0.25d)) * 2;
        if (rnd1 <= 0 || rnd1 >= 1 || rnd2 <= 0 || rnd2 >= 1 || rnd3 <= 0 || rnd3 >= 1) {
            LOGGER.info("skipping troll item maybe already on ground");
            return;
        }
        if (!couldBeFromRandNextFloat(rnd1) || !couldBeFromRandNextFloat(rnd2) || !couldBeFromRandNextFloat(rnd3)) {
            LOGGER.info("skipping troll item not from a block drop");
            return;
        }
        final RandomReverser rev = new RandomReverser(new ArrayList<>());
        rev.addNextFloatCall(rnd1, rnd1, true, true);
        rev.addNextFloatCall(rnd2, rnd2, true, true);
        rev.addNextFloatCall(rnd3, rnd3, true, true);
        final long[] found = rev.findAllValidSeeds().toArray();
        if (found.length != 1) {
            LOGGER.info("Failed match " + x + " " + y + " " + z + " " + Arrays.toString(found));
            NyanDatabase.saveData(timestamp, -1);
        }
        boolean match = false;
        for (long candidate : found) {
            if (NyanDatabase.saveData(timestamp, candidate)) {
                //LOGGER.info("Saved RNG seed to database, and the processing is already cached");
                continue;
            }


            if (true) {
                continue; // temp: skip all seed processing, let it be done remotely
            }


            if (recentlySlowToProcess.contains(candidate)) {
                recentlySlowToProcess.remove(candidate);
                recentlySlowToProcess.add(candidate);
                continue;
            }
            long stepped = candidate;
            for (int stepsBack = 0; stepsBack < 400; stepsBack++) {
                long meow = stepped ^ 0x5DEECE66DL;
                ChunkPos pos = woodlandValid(meow); // not a real chunkpos its *80
                if (pos != null) {
                    LOGGER.info("Match at " + pos.x + "," + pos.z + " assuming rng was stepped by " + stepsBack);
                    LOGGER.info("In blocks that's between " + (pos.x * 16 * 80) + "," + (pos.z * 16 * 80) + " and " + ((pos.x * 80 + 79) * 16 + 15) + "," + ((pos.z * 80 + 79) * 16 + 15));
                    LOGGER.info("Match time: " + (System.currentTimeMillis() - start) + " y:" + Math.floor(y));
                    NyanDatabase.saveProcessedRngSeeds(Collections.singletonList(new NyanDatabase.ProcessedSeed(candidate, stepsBack, pos.x, pos.z)));
                    match = true;
                    break;
                }
                stepped = prevSeed(stepped);
            }
            if (!match) {
                LOGGER.info("");
                LOGGER.info("marking as slow seed. no match, marking as slow seed. Total time: " + (System.currentTimeMillis() - start) + " y:" + Math.floor(y));
                recentlySlowToProcess.add(candidate);
            }
            LOGGER.info("Candidate: " + candidate);
        }
    }

    private static ChunkPos woodlandValid(long candidate) {
        for (int x = -WOODLAND_BOUNDS; x <= WOODLAND_BOUNDS; x++) {
            long z = Woodland.reverseWoodlandZGivenX(candidate, x);
            if (z >= -WOODLAND_BOUNDS && z <= WOODLAND_BOUNDS) {
                return new ChunkPos(x, (int) z);
            }
        }
        return null;
    }

    public void close() {
        // flush database?
    }
}
