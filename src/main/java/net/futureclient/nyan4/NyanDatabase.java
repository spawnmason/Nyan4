package net.futureclient.nyan4;

import net.futureclient.headless.db.Database;
import net.futureclient.headless.db.DatabaseUtils;
import org.apache.commons.dbcp2.BasicDataSource;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

public class NyanDatabase {
    private static final BasicDataSource database;

    static {
        database = Database.connect(Paths.get("nyan.db"));
        database.setConnectionInitSqls(Arrays.asList(
                "PRAGMA busy_timeout = 30000"
        ));
        try {
            Database.applySchema(database, DatabaseUtils.getSchemaFromResource(NyanDatabase.class.getClassLoader(), "nyan_schema.sql"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean saveData(long timestamp, long rng_seed) {
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
            if (rng_seed == -1) {
                return true;
            }
            try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM rng_seeds_processed WHERE rng_seed = ?")) {
                stmt.setLong(1, rng_seed);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static void saveProcessedRngSeed(long rng_seed, int steps, int x, int z) {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO rng_seeds_processed(rng_seed, steps_back, woodland_x, woodland_z) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                stmt.setLong(1, rng_seed);
                stmt.setInt(2, steps);
                stmt.setInt(3, x);
                stmt.setInt(4, z);
                stmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }
}
