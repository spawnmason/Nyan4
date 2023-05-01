package net.futureclient.nyan4.events;

public class EventSeed {
    public final long seed;
    public final long timestamp;
    public final short dimension;
    public final String server;

    public EventSeed(long seed, long timestamp, short dimension, String server) {
        this.seed = seed;
        this.timestamp = timestamp;
        this.dimension = dimension;
        this.server = server;
    }
}