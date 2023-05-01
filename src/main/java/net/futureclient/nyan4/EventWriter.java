package net.futureclient.nyan4;

import org.apache.commons.dbcp2.BasicDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public interface EventWriter {
    void writeEvent(String json) throws SQLException;

    class Postgres implements EventWriter {
        private final DataSource db;

        public Postgres(DataSource db) {
            this.db = db;
        }

        @Override
        public void writeEvent(String json) throws SQLException {
            try (Connection con = this.db.getConnection()) {
                try (PreparedStatement statement = con.prepareStatement("INSERT INTO events_fallback VALUES (?)")) {
                    statement.setString(1, json);
                }
            }
        }
    }

    class Sqlite implements EventWriter {
        private final DataSource db;
        public Sqlite(DataSource db) {
            this.db = db;
        }

        @Override
        public void writeEvent(String json) throws SQLException {
            try (Connection con = this.db.getConnection()) {
                try (PreparedStatement statement = con.prepareStatement("INSERT INTO events_fallback VALUES (?)")) {
                    statement.setString(1, json);
                }
            }
        }
    }
}
