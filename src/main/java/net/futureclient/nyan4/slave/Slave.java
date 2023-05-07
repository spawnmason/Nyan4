package net.futureclient.nyan4.slave;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.seedfinding.latticg.RandomReverser;
import net.futureclient.headless.eventbus.events.PacketEvent;
import net.futureclient.headless.game.HeadlessMinecraft;
import net.futureclient.nyan4.DatabaseJuggler;
import net.futureclient.nyan4.NyanDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketSpawnObject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Slave {
    private static final Logger LOGGER = LogManager.getLogger("Slave");

    public final Minecraft ctx;
    private final ScheduledExecutorService pluginExecutor;
    private final DatabaseJuggler newDatabase;

    private final NyanDatabase tempDatabase; // only for rng_seeds_raw (which will be removed once we switch to events)

    private final Map<UUID, Long> recentlyLeftTheGame = new HashMap<>();
    private final Map<UUID, Long> recentlyJoinedTheGame = new HashMap<>();
    public final String whoamiForDebug;

    public Slave(final HeadlessMinecraft ctx, ScheduledExecutorService pluginExecutor, DatabaseJuggler juggler, NyanDatabase tempDatabase, String whoamiForDebug) {
        this.ctx = ctx;
        this.pluginExecutor = pluginExecutor;
        this.newDatabase = juggler;
        this.tempDatabase = tempDatabase;
        this.whoamiForDebug = whoamiForDebug;
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
                //LOGGER.info("Processing item drop at y {}", (int) y);
                pluginExecutor.execute(() -> {
                    // process async
                    try {
                        processItemDropAsync(x, y, z, recTime, (short) dimension.getId(), this.newDatabase, this.tempDatabase);
                    } catch (final Throwable t) {
                        LOGGER.error("Error while processing item drop", t);
                    }
                });
            });
        } else if (event.getPacket() instanceof SPacketPlayerListItem) {
            SPacketPlayerListItem packet = event.getPacket();
            long now = System.currentTimeMillis();
            ctx.addScheduledTask(() -> {
                if (probablyInQueue()) {
                    return;
                }
                for (SPacketPlayerListItem.AddPlayerData data : packet.getEntries()) {
                    if (packet.getAction() == SPacketPlayerListItem.Action.ADD_PLAYER) {
                        recentlyJoinedTheGame.put(data.getProfile().getId(), now);
                        recentlyLeftTheGame.remove(data.getProfile().getId());
                        if ("100010".equals(data.getProfile().getName())) {
                            LOGGER.warn("100010 joined the game from the pov of {}", whoamiForDebug);
                        }
                    }
                    if (packet.getAction() == SPacketPlayerListItem.Action.REMOVE_PLAYER) {
                        recentlyLeftTheGame.put(data.getProfile().getId(), now);
                        recentlyJoinedTheGame.remove(data.getProfile().getId());
                        if ("100010".equals(data.getProfile().getName())) {
                            LOGGER.warn("100010 left the game from the pov of {}", whoamiForDebug);
                        }
                    }
                }
            });
        }
    }

    public boolean probablyInQueue() {
        return ctx.player.isSpectator() || Math.abs(ctx.player.posX) <= 16 && Math.abs(ctx.player.posZ) <= 16;
    }

    private static boolean couldBeFromRandNextFloat(float f, int rngNext24) {
        return Float.floatToRawIntBits(f) == Float.floatToRawIntBits(rngNext24 / (float) (1 << 24));
    }

    private static strictfp boolean verifyBlockDrop(int rngNext24, int blockCoord, double itemDrop) {
        double itemDropShouldBe = (double) blockCoord + ((double) (rngNext24 / (float) (1 << 24) * 0.5F) + 0.25D);
        return Double.doubleToRawLongBits(itemDropShouldBe) == Double.doubleToRawLongBits(itemDrop);
    }

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
                                             final long timestamp, final short dimension, final DatabaseJuggler output, final NyanDatabase tempDatabase) {
        if (checkAlreadyProcessed(new Vec3d(x, y, z))) {
            //LOGGER.info("skipping item drop already processed by another bot");
            return;
        }

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
        int next24_2 = (int) (rnd2 * (1 << 24));
        int next24_3 = (int) (rnd3 * (1 << 24));
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
        event.addProperty("server", "2b2t.org");
        event.addProperty("blockX", blockX);
        event.addProperty("blockY", blockY);
        event.addProperty("blockZ", blockZ);
        event.addProperty("rng1", next24_1);
        event.addProperty("rng2", next24_2);
        event.addProperty("rng3", next24_3);
        event.addProperty("dimension", dimension);
        JsonArray seeds = new JsonArray();
        for (long seed : found) {
            seeds.add(Long.toString(seed));
        }
        event.add("seeds", seeds);
        output.writeEvent(event);
        if (found.length != 1) {
            LOGGER.info("Failed match " + x + " " + y + " " + z + " " + Arrays.toString(found));
            tempDatabase.saveData(timestamp, -1);
        }
        for (long candidate : found) {
            tempDatabase.saveData(timestamp, candidate);
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

    public void close() {
        // flush database?
    }
}