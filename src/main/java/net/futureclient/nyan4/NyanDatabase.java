package net.futureclient.nyan4;

import net.futureclient.headless.db.Database;
import net.futureclient.headless.db.DatabaseUtils;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Arrays;

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
}
