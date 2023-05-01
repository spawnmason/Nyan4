package net.futureclient.nyan4.events;


import java.util.UUID;

public class EventPlayerSession {
    public final long time;
    public final String server;
    public final UUID[] players;

    public EventPlayerSession(long time, String server, UUID[] players) {
        this.time = time;
        this.server = server;
        this.players = players;
    }
}