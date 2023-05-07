package net.futureclient.nyan4;

import com.google.common.net.InternetDomainName;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.futureclient.nyan4.slave.Slave;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class ServerTracker {
    private static final Logger LOGGER = LogManager.getLogger("ServerTracker");

    private Set<String> activeServerSet = new ObjectArraySet<>();

    public synchronized List<JsonObject> tick(Collection<Slave> currentlyOnline) {
        final long now = System.currentTimeMillis();
        Set<String> current = new ObjectArraySet<>();
        currentlyOnline.stream()
                .map(Slave::serverConnectedTo)
                .filter(x -> x != null)
                .forEach(current::add);
        Set<String> newServers = new ObjectArraySet<>(current);
        newServers.removeAll(activeServerSet);
        Set<String> disconnectedServers = new ObjectArraySet<>(activeServerSet);
        disconnectedServers.removeAll(current);

        List<JsonObject> events = new ArrayList<>();
        if (!newServers.isEmpty()) {
            JsonArray servers = new JsonArray();
            for (String s : newServers) {
                servers.add(s);
                LOGGER.info("Now connected to {}", s);
            }
            JsonObject json = new JsonObject();
            json.addProperty("type", "start");
            json.addProperty("timestamp", now);
            json.add("servers", servers);
            events.add(json);
        }
        if (!disconnectedServers.isEmpty()) {
            JsonArray servers = new JsonArray();
            for (String s : disconnectedServers) {
                servers.add(s);
                LOGGER.info("No longer connected to {}", s);
            }
            JsonObject json = new JsonObject();
            json.addProperty("type", "stop");
            json.addProperty("timestamp", now);
            json.add("servers", servers);
            events.add(json);
        }

        activeServerSet = current;
        return events;
    }


}