package net.futureclient.nyan4.slave;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.seedfinding.latticg.RandomReverser;
import net.futureclient.headless.eventbus.events.PacketEvent;
import net.futureclient.headless.game.HeadlessMinecraft;
import net.futureclient.nyan4.DatabaseJuggler;
import net.futureclient.nyan4.NyanDatabase;
import net.futureclient.nyan4.Woodland;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketSpawnObject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Slave {
    private static final Logger LOGGER = LogManager.getLogger("Slave");

    public final Minecraft ctx;
    private final ScheduledExecutorService pluginExecutor;
    private final DatabaseJuggler newDatabase;

    private final Map<UUID, Long> recentlyLeftTheGame = new HashMap<>();
    private final Map<UUID, Long> recentlyJoinedTheGame = new HashMap<>();

    public Slave(final HeadlessMinecraft ctx, ScheduledExecutorService pluginExecutor, DatabaseJuggler juggler) {
        this.ctx = ctx;
        this.pluginExecutor = pluginExecutor;
        this.newDatabase = juggler;
    }

    public void onPacket(final PacketEvent.Receive event) {
        // NOTE: NETTY THREAD! NOT MAIN THREAD!
        out:
        if (event.getPacket() instanceof SPacketSpawnObject) {
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
                        processItemDropAsync(x, y, z, recTime, (short) dimension.getId(), this.newDatabase);
                    } catch (final Throwable t) {
                        LOGGER.error("Error while processing item drop", t);
                    }
                });
            });
        } else if (event.getPacket() instanceof SPacketPlayerListItem) {
            SPacketPlayerListItem packet = event.getPacket();
            long now = System.currentTimeMillis();
            ctx.addScheduledTask(() -> {
                for (SPacketPlayerListItem.AddPlayerData data : packet.getEntries()) {
                    if (packet.getAction() == SPacketPlayerListItem.Action.ADD_PLAYER) {
                        recentlyJoinedTheGame.put(data.getProfile().getId(), now);
                        recentlyLeftTheGame.remove(data.getProfile().getId());
                    }
                    if (packet.getAction() == SPacketPlayerListItem.Action.REMOVE_PLAYER) {
                        recentlyLeftTheGame.put(data.getProfile().getId(), now);
                        recentlyJoinedTheGame.remove(data.getProfile().getId());
                    }
                }
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

    private static boolean couldBeFromRandNextFloat(float f, int rngNext24) {
        return Float.floatToRawIntBits(f) == Float.floatToRawIntBits(rngNext24 / (float) (1 << 24));
    }

    private static strictfp boolean verifyBlockDrop(int rngNext24, int blockCoord, double itemDrop) {
        double itemDropShouldBe = (double) blockCoord + ((double) (rngNext24 / (float) (1 << 24) * 0.5F) + 0.25D);
        return Double.doubleToRawLongBits(itemDropShouldBe) == Double.doubleToRawLongBits(itemDrop);
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
                                             final long timestamp, final short dimension, final DatabaseJuggler output) {
        if (checkAlreadyProcessed(new Vec3d(x, y, z))) {
            //LOGGER.info("skipping item drop already processed by another bot");
            return;
        }
        final long start = System.currentTimeMillis();

        final int blockX = (int) Math.floor(x);
        final int blockY = (int) Math.floor(y);
        final int blockZ = (int) Math.floor(z);
        final float rnd1 = ((float) (x - blockX - 0.25d)) * 2;
        final float rnd2 = ((float) (y - blockY - 0.25d)) * 2;
        final float rnd3 = ((float) (z - blockZ - 0.25d)) * 2;
        if (rnd1 <= 0 || rnd1 >= 1 || rnd2 <= 0 || rnd2 >= 1 || rnd3 <= 0 || rnd3 >= 1) {
            LOGGER.info("skipping troll item maybe already on ground");
            return;
        }
        int next24_1 = (int) (rnd1 * (1 << 24)); // java.util.Random.nextFloat calls .next(24) which is an integer. let's verify that this is actually what happened, otherwise we can assume this item drop is not from a block drop
        int next24_2 = (int) (rnd1 * (1 << 24));
        int next24_3 = (int) (rnd1 * (1 << 24));
        if (!couldBeFromRandNextFloat(rnd1, next24_1) || !couldBeFromRandNextFloat(rnd2, next24_2) || !couldBeFromRandNextFloat(rnd3, next24_3)) {
            LOGGER.info("skipping troll item not from a block drop");
            return;
        }
        if (!verifyBlockDrop(next24_1, blockX, x) || !verifyBlockDrop(next24_2, blockY, y) || !verifyBlockDrop(next24_3, blockZ, z)) {
            LOGGER.fatal("sanity check failed, this should be impossible {} {} {}", Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z));
            throw new IllegalStateException("sanity check " + Double.doubleToRawLongBits(x) + " " + Double.doubleToRawLongBits(y) + " " + Double.doubleToRawLongBits(z)); // should be literally impossible based on the above two debug checks
        }
        final RandomReverser rev = new RandomReverser(new ArrayList<>());
        rev.addNextFloatCall(rnd1, rnd1, true, true);
        rev.addNextFloatCall(rnd2, rnd2, true, true);
        rev.addNextFloatCall(rnd3, rnd3, true, true);
        final long[] found = rev.findAllValidSeeds().toArray();
        JsonObject event = new JsonObject();
        event.addProperty("type", "seed");
        event.addProperty("timestamp", timestamp);
        event.addProperty("server", "2b2t");
        event.addProperty("blockX", blockX);
        event.addProperty("blockY", blockY);
        event.addProperty("blockZ", blockZ);
        event.addProperty("rng1", next24_1);
        event.addProperty("rng2", next24_2);
        event.addProperty("rng3", next24_3);
        event.addProperty("dimension", dimension);
        JsonArray seeds = new JsonArray();
        for (long seed : found) {
            seeds.add(seed);
        }
        event.add("seeds", seeds);
        try {
            output.writer.writeEvent(event);
        } catch (SQLException ex) {
            ex.printStackTrace();
            LOGGER.warn(ex);
        }
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

    public Map<UUID, Long> getRecentlyLeft() {
        recentlyLeftTheGame.values().removeIf(timestamp -> timestamp < System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(30));
        return Collections.unmodifiableMap(recentlyLeftTheGame);
    }

    public Long whenDidThisUUIDJoin(UUID uuid) {
        // if this UUID has left the game recently, then it'll be in recentlyLeft, and therefore this method won't be called
        // otherwise, this map lookup will succeed
        return recentlyJoinedTheGame.get(uuid);
    }

    public Collection<NetworkPlayerInfo> getOnlinePlayers() {
        // same deal as whenDidThisUUIDJoin
        return Collections.unmodifiableCollection(ctx.player.connection.getPlayerInfoMap());
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
