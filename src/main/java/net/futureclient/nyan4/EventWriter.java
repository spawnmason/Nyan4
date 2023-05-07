package net.futureclient.nyan4;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public interface EventWriter {
    Gson GSON = new Gson();

    void writeEvent(JsonObject json) throws SQLException;

    class Postgres implements EventWriter {
        private final DataSource db;

        public Postgres(DataSource db) {
            this.db = db;
        }

        @Override
        public void writeEvent(JsonObject json) throws SQLException {
            try (Connection con = this.db.getConnection()) {
                try (PreparedStatement statement = con.prepareStatement("INSERT INTO events(event) VALUES (?::jsonb)")) {
                    statement.setString(1, GSON.toJson(json));
                    statement.execute();
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
        public void writeEvent(JsonObject json) throws SQLException {
            try (Connection con = this.db.getConnection()) {
                try (PreparedStatement statement = con.prepareStatement("INSERT INTO events_fallback VALUES (?)")) {
                    statement.setString(1, GSON.toJson(json));
                    statement.execute();
                }
            }
        }
    }
}