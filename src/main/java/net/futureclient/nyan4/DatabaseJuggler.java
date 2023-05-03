package net.futureclient.nyan4;

import com.google.gson.JsonObject;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.Optional;

// TODO: handle random postgres failures
// TODO: query all events from the fallback table when transitioning from sqlite
public class DatabaseJuggler {
    private static final Logger LOGGER = LogManager.getLogger("DatabaseJuggler");
    private volatile EventWriter writer;
    private final Thread postgresReconnectThread;

    public DatabaseJuggler() {
        Optional<BasicDataSource> postgres = NyanPostgres.tryConnect();
        if (postgres.isPresent()) {
            this.writer = new EventWriter.Postgres(postgres.get());
            this.postgresReconnectThread = null;
        } else {
            // sobbing
            System.out.println("Can't connect to Postgres, falling back to SQLite");
            this.writer = new EventWriter.Sqlite(NyanDatabase.database);
            this.postgresReconnectThread = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(5000);
                        Optional<BasicDataSource> db = NyanPostgres.tryConnect();
                        if (db.isPresent()) {
                            this.writer = new EventWriter.Postgres(db.get());
                            return;
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }, "Patiently waiting for postgres to come back :(");
            this.postgresReconnectThread.setDaemon(true);
            this.postgresReconnectThread.start();
        }
    }

    public void writeEvent(JsonObject event) {
        try {
            writer.writeEvent(event);
        } catch (SQLException e1) {
            // postgres is down, but we'd rather not lose the event
            LOGGER.fatal("Postgres is down, falling back to SQLite", e1);
            try {
                new EventWriter.Sqlite(NyanDatabase.database).writeEvent(event);
            } catch (SQLException e2) {
                LOGGER.fatal("SQLite is down too??", e2);
            }
        }
    }

    public void shutdown() {
        postgresReconnectThread.interrupt();
    }
}
