package net.futureclient.nyan4.slave;

import com.google.common.net.InternetDomainName;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.futureclient.headless.eventbus.events.PacketEvent;
import net.futureclient.headless.game.HeadlessMinecraft;
import net.futureclient.nyan4.DatabaseJuggler;
import net.futureclient.nyan4.LatticeReverser;
import net.futureclient.nyan4.NyanDatabase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Slave {
    private static final Logger LOGGER = LogManager.getLogger("Slave");

    public final MinecraftClient ctx;
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
        if (event.getPacket() instanceof PlayerListS2CPacket packet) {
            long now = System.currentTimeMillis();
            ctx.execute(() -> {
                if (probablyInQueue()) {
                    return;
                }
                for (PlayerListS2CPacket.Entry data : packet.getEntries()) {
                    if (packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                        recentlyJoinedTheGame.put(data.profile().getId(), now);
                        recentlyLeftTheGame.remove(data.profile().getId());
                        if ("100010".equals(data.profile().getName())) {
                            LOGGER.warn("100010 joined the game from the pov of {}", whoamiForDebug);
                        }
                    }
                }
            });
        } else if (event.getPacket() instanceof PlayerRemoveS2CPacket packet) {
            long now = System.currentTimeMillis();
            ctx.execute(() -> {
                if (probablyInQueue()) {
                    return;
                }
                for (UUID id : packet.profileIds()) {
                    recentlyLeftTheGame.put(id, now);
                    recentlyJoinedTheGame.remove(id);
                    if (UUID.fromString("1e567ed0-1eba-4262-9073-085c23897dd9").equals(id)) { //100010
                        LOGGER.warn("100010 left the game from the pov of {}", whoamiForDebug);
                    }
                }
            });

        }
    }

    public boolean probablyInQueue() {
        return ctx.player.isSpectator() || Math.abs(ctx.player.getX()) <= 16 && Math.abs(ctx.player.getZ()) <= 16;
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
            LOGGER.info("skipping troll item not from a block drop {} {} {} {} {} {}", blockX, blockY, blockZ, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z));
            return;
        }
        if (!verifyBlockDrop(next24_1, blockX, x) || !verifyBlockDrop(next24_2, blockY, y) || !verifyBlockDrop(next24_3, blockZ, z)) {
            LOGGER.fatal("sanity check failed, this should be impossible {} {} {} {} {} {}", blockX, blockY, blockZ, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z));
            throw new IllegalStateException("sanity check " + Double.doubleToRawLongBits(x) + " " + Double.doubleToRawLongBits(y) + " " + Double.doubleToRawLongBits(z)); // should be literally impossible based on the above two debug checks
        }
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
        long seed = LatticeReverser.crackOptimizedDoesntMakeSense(next24_1, next24_2, next24_3);
        if (seed != -1) {
            seeds.add(Long.toString(seed));
        }
        event.add("seeds", seeds);
        output.writeEvent(event);
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

    public Collection<PlayerListEntry> getOnlinePlayers() {
        // same deal as whenDidThisUUIDJoin
        return Collections.unmodifiableCollection(ctx.getNetworkHandler().getPlayerList());
    }

    public String serverConnectedTo() {
        ServerInfo currentServer = this.ctx.getCurrentServerEntry();
        if (currentServer == null) return null;
        String ip = currentServer.address;
        final int portIdx = ip.indexOf(':');
        if (portIdx != -1) ip = ip.substring(0, portIdx);

        return getBaseDomain(ip);
    }

    private static String getBaseDomain(String ip) {
        try {
            return InternetDomainName.from(ip).topPrivateDomain().toString();
        } catch (IllegalArgumentException ex) { // not a domain
            return ip;
        }
    }

    public void close() {
        // flush database?
    }
}
