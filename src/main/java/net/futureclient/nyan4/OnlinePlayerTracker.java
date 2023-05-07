package net.futureclient.nyan4;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.futureclient.nyan4.slave.Slave;
import net.minecraft.client.network.NetworkPlayerInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class OnlinePlayerTracker {
    private static final Logger LOGGER = LogManager.getLogger("OnlinePlayerTracker");
    private Set<OnlinePlayer> onlinePlayerSet = new HashSet<>();

    public synchronized List<JsonObject> tick(Collection<Slave> currentlyOnline) {
        long now = System.currentTimeMillis();
        // assemble the new onlinePlayerSet
        Set<OnlinePlayer> current = new HashSet<>();
        // add the online players from every slave
        currentlyOnline.forEach(slave -> slave.getOnlinePlayers().stream().map(NetworkPlayerInfo::getGameProfile).map(OnlinePlayer::new).forEach(current::add));
        // but remove any players if even one of the slaves has observed that player to have recently left (and not rejoined)
        currentlyOnline.forEach(slave -> slave.getRecentlyLeft().keySet().stream().map(OnlinePlayer::new).forEach(current::remove));

        Set<OnlinePlayer> addedSinceLastTick = new HashSet<>(current);
        addedSinceLastTick.removeAll(onlinePlayerSet);
        Set<OnlinePlayer> removedSinceLastTick = new HashSet<>(onlinePlayerSet);
        removedSinceLastTick.removeAll(current);

        List<JsonObject> events = new ArrayList<>();
        for (OnlinePlayer player : addedSinceLastTick) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "player_join");
            event.addProperty("server", "2b2t.org"); // TODO: FIX
            event.addProperty("uuid", player.uuid.toString());
            event.addProperty("username", player.username);
            event.addProperty("tracker_timestamp", now);
            long minJoinTimestamp = Long.MAX_VALUE;
            long maxJoinTimestamp = Long.MIN_VALUE;
            for (Slave slave : currentlyOnline) {
                Long joinTimestamp = slave.whenDidThisUUIDJoin(player.uuid);
                if (player.username.equals("100010")) {
                    LOGGER.warn("100010 info {} {} {}", slave.getRecentlyLeft().get(UUID.fromString("1e567ed0-1eba-4262-9073-085c23897dd9")), slave.whenDidThisUUIDJoin(UUID.fromString("1e567ed0-1eba-4262-9073-085c23897dd9")), slave.getOnlinePlayers().stream().filter(net -> net.getGameProfile().getName().equals("100010")).count());
                    LOGGER.warn("join timestamp of 100010 from pov of {} was {}", slave.whoamiForDebug, joinTimestamp);
                }
                if (joinTimestamp != null) {
                    minJoinTimestamp = Math.min(minJoinTimestamp, joinTimestamp);
                    maxJoinTimestamp = Math.max(maxJoinTimestamp, joinTimestamp);
                }
            }
            event.addProperty("min_join_timestamp", minJoinTimestamp);
            event.addProperty("max_join_timestamp", maxJoinTimestamp);
            // paranoia: record all possible timestamps
            // i can think of edge cases where we might want any of these three lol

            // tracker_timestamp: when all slaves get kicked from 2b, then the min and max join timestamps will be null (aka max_value and min_value), but we still want to know approx when that happened, so, tracker_timestamp is useful
            // min_join_timestamp: in the normal case, this is the one we'll want to rely on
            // max_join_timestamp: tulpa_1 and tulpa_2 observe kenzie join at the same-ish time. after a short time, kenzie leaves and rejoins. tulpa_1 is lagging and doesn't observe this for a few seconds. but tulpa_2 sees it. we want to use tulpa_2's join timestamp (max_join_timestamp) rather than the older (not yet updated) tulpa_1 join timestamp (min_join_timestamp)
            events.add(event);
        }
        for (OnlinePlayer player : removedSinceLastTick) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "player_leave");
            event.addProperty("uuid", player.uuid.toString());
            event.addProperty("username", player.username);
            event.addProperty("tracker_timestamp", now);
            long minLeaveTimestamp = Long.MAX_VALUE;
            long maxLeaveTimestamp = Long.MIN_VALUE;
            for (Slave slave : currentlyOnline) {
                Long leaveTimestamp = slave.getRecentlyLeft().get(player.uuid);
                if (leaveTimestamp != null) {
                    minLeaveTimestamp = Math.min(minLeaveTimestamp, leaveTimestamp);
                    maxLeaveTimestamp = Math.max(maxLeaveTimestamp, leaveTimestamp);
                }
            }
            event.addProperty("min_leave_timestamp", minLeaveTimestamp);
            event.addProperty("max_leave_timestamp", maxLeaveTimestamp);
            events.add(event);
        }

        onlinePlayerSet = current;
        return events;
    }

    public static final class OnlinePlayer {
        public final UUID uuid;
        public final String username;

        public OnlinePlayer(GameProfile profile) {
            this.uuid = profile.getId();
            this.username = profile.getName();
        }

        public OnlinePlayer(UUID uuid) {
            this.uuid = uuid;
            this.username = null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof OnlinePlayer && ((OnlinePlayer) o).uuid.equals(uuid);
        }

        @Override
        public int hashCode() {
            return uuid.hashCode();
        }
    }
}