package net.futureclient.nyan4.events;


public class EventStartup {
    public final long timestamp;
    public final String server;

    public EventStartup(long timestamp, String server) {
        this.timestamp = timestamp;
        this.server = server;
    }
}