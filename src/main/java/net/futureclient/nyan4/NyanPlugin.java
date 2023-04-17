package net.futureclient.nyan4;

import net.futureclient.nyan4.slave.Slave;
import net.futureclient.headless.eventbus.EventPriority;
import net.futureclient.headless.eventbus.SubscribeEvent;
import net.futureclient.headless.eventbus.events.PacketEvent;
import net.futureclient.headless.eventbus.events.UserGameEvent;
import net.futureclient.headless.game.HeadlessMinecraft;
import net.futureclient.headless.plugin.Plugin;
import net.futureclient.headless.user.User;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Plugin.Metadata(name = "Nyan", version = "4.0")
public final class NyanPlugin implements Plugin {
    private static final Logger LOGGER = LogManager.getLogger("NoComment");

    private final Map<Minecraft, Slave> slaves = new ConcurrentHashMap<>();

    public ScheduledExecutorService executor;

    @Override
    public void onEnable(final PluginContext ctx) {
        this.executor = Executors.newScheduledThreadPool(1);
        ctx.userManager().users().forEach(this::attachSlave);
        ctx.subscribers().register(this);
    }

    @Override
    public void onDisable(final PluginContext ctx) {
        try {
            this.executor.shutdownNow();
            this.executor = null;
        } catch (final Throwable t) {
            LOGGER.warn("Failed to stop executor", t);
        }
        ctx.commandManager().unregister("nocomment");
        ctx.subscribers().unregister(this);
        ctx.userManager().users().forEach(this::detachSlave);

    }

    @SubscribeEvent
    public void onUserGame(final UserGameEvent.Attached event) {
        this.attachSlave(event.ctx);
    }

    @SubscribeEvent
    public void onUserGame(final UserGameEvent.Detached event) {
        this.detachSlave(event.ctx);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) // higher then proxy :sunglasses:
    public void onPacket(final PacketEvent.Receive event) {
        final HeadlessMinecraft mc = event.getMinecraft();
        if (mc != null) {
            final Slave slave = this.slaves.get(mc);
            if (slave != null) {
                slave.onPacket(event);
            }
        }
    }

    private void attachSlave(final User user) {
        final HeadlessMinecraft mc = user.getGame();
        if (mc != null) {
            LOGGER.info("Slave attached {}", user.getUsername());
            this.slaves.put(mc, new Slave(mc, this.executor));
        }
    }

    private void detachSlave(final User user) {
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
