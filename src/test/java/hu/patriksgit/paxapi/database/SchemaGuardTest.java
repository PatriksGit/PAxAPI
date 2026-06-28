ackage hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import hu.patriksgit.paxapi.database.DatabaseConfig.SslMode;
import static org.junit.jupiter.api.Assertions.*;

class SchemaGuardTest {

    private Database db() {
        return new Database(
            DatabaseConfig.builder()
                .host("localhost").database("db").username("u").password("p")
                .poolSize(1).sslMode(SslMode.DISABLED).build(),
            LoggerFactory.getLogger("t"));
    }

    @Test void ensureColumnRejectsUnsafeTable() {
        try (Database d = db()) {
            assertThrows(IllegalArgumentException.class,
                () -> d.ensureColumn(null, "players; DROP TABLE x", "c", "INT"));
        }
    }

    @Test void ensureIndexRejectsUnsafeColumns() {
        try (Database d = db()) {
            assertThrows(IllegalArgumentException.class,
                () -> d.ensureIndex(null, "players", "idx", "(c); DROP TABLE x"));
        }
    }
}
