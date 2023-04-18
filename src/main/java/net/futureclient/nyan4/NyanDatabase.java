package net.futureclient.nyan4;

import net.futureclient.headless.db.Database;
import net.futureclient.headless.db.DatabaseUtils;
import org.apache.commons.dbcp2.BasicDataSource;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class NyanDatabase {
    private static final BasicDataSource database;

    static {
        database = Database.connect(Paths.get("nyan.db"));
        try {
            Database.applySchema(database, DatabaseUtils.getSchemaFromResource(NyanDatabase.class.getClassLoader(), "nyan_schema.sql"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void saveData(long timestamp, long rng_seed) {
        if (timestamp < 0 || (rng_seed != -1 && rng_seed != (rng_seed & ((1L << 48) - 1)))) {
            throw new IllegalStateException();
        }
        // this won't happen all that often, but if it did, this should get its own thread that leaves the preparedstatement open i guess
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO rng_seeds_raw(received_at, rng_seed) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                stmt.setLong(1, timestamp);
                stmt.setLong(2, rng_seed);
                stmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }
}
