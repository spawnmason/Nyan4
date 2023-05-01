package net.futureclient.nyan4.events;

public class EventStop {
    public final long timestamp;
    public final String server;

    public EventStop(long timestamp, String server) {
        this.timestamp = timestamp;
        this.server = server;
    }
}