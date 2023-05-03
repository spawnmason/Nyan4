package net.futureclient.nyan4;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.futureclient.nyan4.slave.Slave;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.*;

public class OnlinePlayerTracker {
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
            event.addProperty("uuid", player.uuid.toString());
            event.addProperty("username", player.username);
            event.addProperty("tracker_timestamp", now);
            long minJoinTimestamp = Long.MAX_VALUE;
            long maxJoinTimestamp = Long.MIN_VALUE;
            for (Slave slave : currentlyOnline) {
                Long joinTimestamp = slave.whenDidThisUUIDJoin(player.uuid);
                if (joinTimestamp != null) {
                    minJoinTimestamp = Math.min(minJoinTimestamp, joinTimestamp);
                    maxJoinTimestamp = Math.max(maxJoinTimestamp, joinTimestamp);
                }
            }
            event.addProperty("min_join_timestamp", minJoinTimestamp);
            event.addProperty("max_join_timestamp", maxJoinTimestamp);
            // paranoia: record all possible timestamps
            // i can think of edge cases where we might want any of these three lol
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
