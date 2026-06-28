package hu.patriksgit.paxapi.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import hu.patriksgit.paxapi.database.DatabaseConfig.SslMode;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseCustomizerTest {

    private static DatabaseConfig cfg() {
        return DatabaseConfig.builder()
            .host("localhost").database("db").username("u").password("p")
            .poolSize(4).sslMode(SslMode.VERIFY_IDENTITY).build();
    }

    @Test void customizerIsApplied() {
        try (Database d = new Database(cfg(), LoggerFactory.getLogger("t"),
                hc -> hc.setMaximumPoolSize(20))) {
            assertEquals(20, ((HikariDataSource) d.dataSource()).getMaximumPoolSize());
        }
    }

    @Test void hardeningWinsOverCustomizer() {
        try (Database d = new Database(cfg(), LoggerFactory.getLogger("t"), hc -> {
            hc.addDataSourceProperty("allowLoadLocalInfile", "true");      // try to weaken
            hc.setJdbcUrl("jdbc:mysql://x/y?sslMode=DISABLED");            // try to downgrade TLS
        })) {
            HikariDataSource h = (HikariDataSource) d.dataSource();
            Properties p = h.getDataSourceProperties();
            assertEquals("false", p.getProperty("allowLoadLocalInfile"), "blast-radius pin must win");
            assertTrue(h.getJdbcUrl().contains("sslMode=VERIFY_IDENTITY"), "configured sslMode must win");
            assertTrue(h.getJdbcUrl().contains("useAffectedRows=false"), "hardened URL must win");
        }
    }

    @Test void twoArgConstructorStillWorks() {
        try (Database d = new Database(cfg(), LoggerFactory.getLogger("t"))) {
            assertNotNull(d.dataSource());
        }
    }
}
