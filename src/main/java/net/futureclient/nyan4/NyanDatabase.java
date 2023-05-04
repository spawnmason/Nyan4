package net.futureclient.nyan4;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.futureclient.headless.db.Database;
import net.futureclient.headless.db.DatabaseUtils;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.futureclient.nyan4.Woodland.WOODLAND_BOUNDS;

public class NyanDatabase {
    private static final Logger LOGGER = LogManager.getLogger("NyanDatabase");
    public final BasicDataSource database;

    {
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

    public boolean saveData(long timestamp, long rng_seed) {
        if (timestamp < 0 || (rng_seed != -1 && rng_seed != (rng_seed & ((1L << 48) - 1)))) {
            throw new IllegalStateException();
        }
        // this won't happen all that often, but if it did, this should get its own thread that leaves the preparedstatement open i guess
        try (Connection connection = database.getConnection()) {
            boolean processed = false;
            if (rng_seed != -1) {
                try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM rng_seeds_processed WHERE rng_seed = ?")) {
                    stmt.setLong(1, rng_seed);
                    try (ResultSet rs = stmt.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) > 0) {
                            processed = true;
                        }
                    }
                }
            }
            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO rng_seeds_raw(received_at, rng_seed, processed) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                stmt.setLong(1, timestamp);
                stmt.setLong(2, rng_seed);
                stmt.setBoolean(3, processed);
                stmt.execute();
            }
            return processed || rng_seed == -1;
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static class ProcessedSeed {
        private final long rng_seed;
        private final int steps;
        private final int x;
        private final int z;

        public ProcessedSeed(long rng_seed, int steps, int x, int z) {
            this.rng_seed = rng_seed;
            this.steps = steps;
            this.x = x;
            this.z = z;
        }
    }

    public void saveProcessedRngSeeds(List<ProcessedSeed> seeds) {
        seeds = new ArrayList<>(seeds); // slave passes in an unmodifiable list sigh
        seeds.removeIf(seed -> {
            if (Woodland.stepRng(seed.steps, Woodland.woodlandMansionSeed(seed.x, seed.z) ^ 0x5DEECE66DL) != seed.rng_seed || seed.x < -WOODLAND_BOUNDS || seed.x > WOODLAND_BOUNDS || seed.z < -WOODLAND_BOUNDS || seed.z > WOODLAND_BOUNDS || seed.steps < 0 || seed.steps > 2750000) {
                LOGGER.warn("Bad RNG data! " + seed.rng_seed + " " + seed.steps + " " + seed.x + " " + seed.z);
                return true;
            }
            return false;
        });
        if (seeds.isEmpty()) {
            return;
        }
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO rng_seeds_processed(rng_seed, steps_back, woodland_x, woodland_z) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                for (ProcessedSeed seed : seeds) {
                    stmt.setLong(1, seed.rng_seed);
                    stmt.setInt(2, seed.steps);
                    stmt.setInt(3, seed.x);
                    stmt.setInt(4, seed.z);
                    stmt.execute();
                }
            }
            try (PreparedStatement stmt = connection.prepareStatement("UPDATE rng_seeds_raw SET processed = TRUE WHERE NOT processed AND rng_seed = ?")) {
                for (ProcessedSeed seed : seeds) {
                    stmt.setLong(1, seed.rng_seed);
                    stmt.executeUpdate();
                }
            }
            LOGGER.info("nyan database saved " + seeds.size() + " seeds");
            connection.commit();
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public LongSet getSomeRngsToBeProcessed() {
        try (Connection connection = database.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT rng_seed FROM rng_seeds_raw WHERE NOT processed LIMIT 10000");
             ResultSet rs = stmt.executeQuery()) {
            // this is fast because it uses the rng_seeds_raw_not_yet_processed partial covering index
            LongSet ret = new LongOpenHashSet();
            while (rs.next()) {
                ret.add(rs.getLong(1));
            }
            return ret;
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }
}
