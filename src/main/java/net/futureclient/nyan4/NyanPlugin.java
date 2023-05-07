package net.futureclient.nyan4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.futureclient.headless.eventbus.EventPriority;
import net.futureclient.headless.eventbus.SubscribeEvent;
import net.futureclient.headless.eventbus.events.PacketEvent;
import net.futureclient.headless.eventbus.events.TickEvent;
import net.futureclient.headless.eventbus.events.UserGameEvent;
import net.futureclient.headless.game.HeadlessMinecraft;
import net.futureclient.headless.plugin.Plugin;
import net.futureclient.headless.user.User;
import net.futureclient.nyan4.slave.Slave;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Plugin.Metadata(name = "Nyan", version = "4.0")
public final class NyanPlugin implements Plugin {
    private static final Logger LOGGER = LogManager.getLogger("Nyan");

    private final Map<Minecraft, Slave> slaves = new ConcurrentHashMap<>();

    public ScheduledExecutorService executor;

    private NyanServer nyanServer;
    private DatabaseJuggler juggler;
    public NyanDatabase database;
    private OnlinePlayerTracker playerTracker;
    private ServerTracker serverTracker;
    private long lastHeartBeatMillis = 0L;

    private final BlockingQueue<JsonObject> eventQueue = new LinkedBlockingQueue<>();
    private Thread eventWriterThread;

    @Override
    public void onEnable(final PluginContext ctx) {
        this.executor = Executors.newScheduledThreadPool(1);
        this.database = new NyanDatabase();
        try {
            this.nyanServer = new NyanServer(this.database);
        } catch (Exception ex) {
            LOGGER.warn("Failed to nyan server", ex);
        }
        String nyan4id = ManagementFactory.getRuntimeMXBean().getName() + ":" + new String(Base64.getUrlEncoder().encode(new SecureRandom().generateSeed(16))).replaceAll("=", "");
        // ^ looks like 12345@blahaj:123abcABC (the first few numbers are the PID)
        this.juggler = new DatabaseJuggler(database, event -> event.addProperty("nyan4id", nyan4id));
        this.eventWriterThread = new Thread(() -> {
            while (true) {
                try {
                    JsonObject event = this.eventQueue.take();
                    this.juggler.writeEvent(event);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        });
        this.eventWriterThread.setDaemon(true);
        this.eventWriterThread.start();

        // Currently serves no purpose but may be useful in the future
        JsonObject event = new JsonObject();
        event.addProperty("type", "plugin_startup");
        event.addProperty("timestamp", System.currentTimeMillis());
        this.eventQueue.add(event);
        this.playerTracker = new OnlinePlayerTracker();
        this.serverTracker = new ServerTracker();
        ctx.userManager().users().forEach(this::attachSlave);
        ctx.subscribers().register(this);
    }

    @Override
    public void onDisable(final PluginContext ctx) {
        ctx.subscribers().unregister(this);
        ctx.userManager().users().forEach(this::detachSlave);
        try {
            this.executor.shutdown();
            this.executor.awaitTermination(1, TimeUnit.SECONDS);
            this.executor.shutdownNow();
            this.executor = null;
        } catch (final Throwable t) {
            LOGGER.warn("Failed to stop executor", t);
        }
        if (this.nyanServer != null) {
            this.nyanServer.shutdown();
            this.nyanServer = null;
        }
        if (this.juggler != null) {
            this.juggler.shutdown();
            this.juggler = null;
        }
        this.eventWriterThread.interrupt();
    }

    @SubscribeEvent
    public void onUserGame(final UserGameEvent.Attached event) {
        this.attachSlave(event.ctx);
    }

    @SubscribeEvent
    public void onUserGame(final UserGameEvent.Detached event) {
        this.detachSlave(event.ctx);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) // higher than proxy :sunglasses:
    public void onPacket(final PacketEvent.Receive event) {
        final HeadlessMinecraft mc = event.getMinecraft();
        if (mc != null) {
            final Slave slave = this.slaves.get(mc);
            if (slave != null) {
                slave.onPacket(event);
            }
        }
    }

    private Collection<Slave> getOnlineSlaves() {
        return this.slaves.values().stream().filter(slave -> slave.ctx.player != null && !slave.probablyInQueue()).collect(Collectors.toList());
    }

    @SubscribeEvent
    public void onTick(final TickEvent.Tasks event) {
        this.eventQueue.addAll(this.playerTracker.tick(getOnlineSlaves()));
    }

    @SubscribeEvent
    public void onGlobalTick(final TickEvent.Global event) {
        Collection<Slave> slaves = getOnlineSlaves();
        this.eventQueue.addAll(this.serverTracker.tick(slaves));

        final long now = System.currentTimeMillis();
        if (now - lastHeartBeatMillis > 5000) {
            JsonObject json = new JsonObject();
            Set<String> servers = new ObjectArraySet<>();
            JsonArray serversJson = new JsonArray();
            for (Slave s : slaves) {
                servers.add(s.serverConnectedTo());
            }
            for (String s : servers) {
                serversJson.add(s);
            }
            json.addProperty("type", "heartbeat");
            json.addProperty("timestamp", now);
            json.add("servers", serversJson);
            this.eventQueue.add(json);
            lastHeartBeatMillis = now;
        }
    }

    private void attachSlave(final User user) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "attach");
        event.addProperty("username", user.getUsername());
        if (user.getUuid() != null) {
            event.addProperty("uuid", user.getUuid());
        }
        event.addProperty("timestamp", System.currentTimeMillis());
        this.eventQueue.add(event);
        final HeadlessMinecraft mc = user.getGame();
        if (mc != null) {
            LOGGER.info("Slave attached {}", user.getUsername());
            this.slaves.put(mc, new Slave(mc, this.executor, this.juggler, this.database, user.getUsername()));
        }
    }

    private void detachSlave(final User user) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "detach");
        event.addProperty("username", user.getUsername());
        if (user.getUuid() != null) {
            event.addProperty("uuid", user.getUuid());
        }
        event.addProperty("timestamp", System.currentTimeMillis());
        this.eventQueue.add(event);
        final HeadlessMinecraft mc = user.getGame();
        if (mc != null) {
            LOGGER.info("Slave detached {}", user.getUsername());
            final Slave slave = this.slaves.remove(mc);
            if (slave != null) {
                slave.close();
            }
        }
    }
}