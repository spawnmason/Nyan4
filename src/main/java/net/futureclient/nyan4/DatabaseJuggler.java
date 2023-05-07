package net.futureclient.nyan4;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class DatabaseJuggler {
    private static final Logger LOGGER = LogManager.getLogger("DatabaseJuggler");
    private volatile EventWriter writer;
    private final Object juggleLock = new Object();
    private final NyanDatabase nyanDatabase;
    private Thread postgresReconnectThread;
    private Thread backfillFromSqliteThread;
    private final Consumer<JsonObject> eventDecorator;
    private volatile boolean shutdown;

    public DatabaseJuggler(NyanDatabase nyanDatabase, Consumer<JsonObject> eventDecorator) {
        this.nyanDatabase = nyanDatabase;
        this.eventDecorator = eventDecorator;
        this.writer = new EventWriter.Sqlite(nyanDatabase.database);
        Optional<BasicDataSource> postgres = NyanPostgres.tryConnect();
        if (postgres.isPresent()) {
            postgresConnectionEstablished(postgres.get());
            this.postgresReconnectThread = null;
        } else {
            // sobbing
            System.out.println("Can't connect to Postgres, falling back to SQLite");
            this.postgresReconnectThread = new Thread(() -> {
                try {
                    while (!shutdown) {
                        try {
                            Thread.sleep(5000);
                            Optional<BasicDataSource> db = NyanPostgres.tryConnect();
                            if (db.isPresent()) {
                                postgresConnectionEstablished(db.get());
                                return;
                            }
                        } catch (InterruptedException ex) {
                            return;
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                } finally {
                    postgresReconnectThread = null;
                }
            }, "Patiently waiting for postgres to come back :(");
            this.postgresReconnectThread.setDaemon(true);
            this.postgresReconnectThread.start();
        }
    }

    private void postgresConnectionEstablished(BasicDataSource postgres) {
        // during the backfill process, still append new events to sqlite
        // only start sending events to postgres once backfill is done
        // this prevents events from getting horribly out of order
        this.backfillFromSqliteThread = new Thread(() -> {
            try {
                while (!shutdown) {
                    try {
                        Thread.sleep(10);
                        if (backfillFromSqlite(postgres)) {
                            // done
                            return;
                        }
                    } catch (InterruptedException ex) {
                        return;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            } finally {
                backfillFromSqliteThread = null;
            }
        }, "Backfill from sqlite");
        this.backfillFromSqliteThread.setDaemon(true);
        this.backfillFromSqliteThread.start();
    }

    private boolean backfillFromSqlite(BasicDataSource postgres) { // returns true when done
        // we can use a postgres transaction to atomically update both the postgres events table and the postgres sqlite_backfill_progress table
        // (guaranteeing that all events are copied from sqlite to postgres exactly once)
        try (Connection postgresConn = postgres.getConnection()) {
            postgresConn.setAutoCommit(false);
            postgresConn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); // the idea is to lock sqlite_backfill_progress upon read
            int latestSqlite = getLatestSqliteEvent(nyanDatabase);
            int progress = getSqliteBackfillProgress(postgresConn);
            if (progress > latestSqlite) {
                LOGGER.warn("sqlite_backfill_progress.last_rowid_processed is greater than the latest sqlite event, this shouldn't happen {} {}", progress, latestSqlite);
                return true;
            }
            if (progress == latestSqlite) {
                LOGGER.info("sqlite_backfill_progress.last_rowid_processed is equal to the latest sqlite event");
                synchronized (juggleLock) {
                    int makeSure = getLatestSqliteEvent(nyanDatabase);
                    if (makeSure == progress) {
                        // we're sure
                        LOGGER.info("SWITCHING TO POSTGRES");
                        this.writer = new EventWriter.Postgres(postgres);
                        this.backfillFromSqliteThread = null;
                        LOGGER.info("switching to postgres because I trust her");
                        return true;
                    }
                }
                return false;
            }
            List<String> jsonEvents = new ArrayList<>();
            int firstRowidFetched = progress + 1;
            try (Connection sqliteConn = nyanDatabase.database.getConnection();
                 PreparedStatement stmt = sqliteConn.prepareStatement("SELECT json, rowid FROM events_fallback WHERE rowid > ? ORDER BY rowid LIMIT 10000")) {
                stmt.setInt(1, progress);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int rowid = rs.getInt(2);
                        if (rowid != firstRowidFetched + jsonEvents.size()) {
                            LOGGER.warn("sqlite event rowid {} is not equal to expected rowid {}", rowid, firstRowidFetched + jsonEvents.size());
                            return true;
                        }
                        jsonEvents.add(rs.getString(1));
                    }
                }
            }
            Gson gson = new Gson();
            IntStream.range(0, jsonEvents.size()).parallel().forEach(i -> {
                JsonObject event = gson.fromJson(jsonEvents.get(i), JsonObject.class);
                int rowid = firstRowidFetched + i;
                event.addProperty("sqlite_backfill_rowid", rowid);
                jsonEvents.set(i, gson.toJson(event));
            });
            // insert into the events table of postgresConn all of the jsonEvents
            try (PreparedStatement stmt = postgresConn.prepareStatement("INSERT INTO events (event) VALUES (?::jsonb)")) {
                for (String jsonEvent : jsonEvents) {
                    stmt.setString(1, jsonEvent);
                    stmt.execute();
                }
            }
            try (PreparedStatement stmt = postgresConn.prepareStatement("UPDATE sqlite_backfill_progress SET last_rowid_processed = ? WHERE last_rowid_processed = ?")) {
                stmt.setInt(1, progress + jsonEvents.size());
                stmt.setInt(2, progress);
                if (stmt.executeUpdate() != 1) {
                    throw new IllegalStateException("is another nyan plugin modifying the postgres?");
                }
            }
            if (shutdown) {
                return false;
            }
            postgresConn.commit();
            LOGGER.info("backfilled {} events from sqlite to postgres", jsonEvents.size());
            return false;
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private int getLatestSqliteEvent(NyanDatabase database) throws SQLException {
        try (Connection conn = database.database.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COALESCE(MAX(rowid), 0) FROM events_fallback");
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("should be impossible");
            }
            return rs.getInt(1);
        }
    }

    private int getSqliteBackfillProgress(Connection postgresConn) throws SQLException {
        try (PreparedStatement stmt = postgresConn.prepareStatement("SELECT last_rowid_processed FROM sqlite_backfill_progress");
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                LOGGER.fatal("postgres schema not applied?");
                throw new IllegalStateException(); // this will just exit the backfillFromSqliteThread, which is fine
            }
            return rs.getInt(1);
        }
    }

    public void writeEvent(JsonObject event) {
        if (shutdown) {
            return;
        }
        eventDecorator.accept(event);
        synchronized (juggleLock) {
            try {
                writer.writeEvent(event);
            } catch (SQLException e1) {
                if (writer instanceof EventWriter.Postgres) {
                    // postgres is down, but we'd rather not lose the event
                    LOGGER.fatal("Postgres is down, falling back to SQLite", e1);
                    this.writer = new EventWriter.Sqlite(nyanDatabase.database);
                    writeEvent(event);
                } else {
                    LOGGER.fatal("SQLite fail", e1);
                }
            }
        }
    }

    public void shutdown() {
        synchronized (juggleLock) {
            shutdown = true;
            if (postgresReconnectThread != null) {
                postgresReconnectThread.interrupt();
            }
            if (backfillFromSqliteThread != null) {
                backfillFromSqliteThread.interrupt();
            }
        }
    }
}