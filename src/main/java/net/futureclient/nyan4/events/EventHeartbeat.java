package net.futureclient.nyan4.events;


public class EventHeartbeat {
    public final long timestamp;
    public final String server;

    public EventHeartbeat(long timestamp, String server) {
        this.timestamp = timestamp;
        this.server = server;
    }
}