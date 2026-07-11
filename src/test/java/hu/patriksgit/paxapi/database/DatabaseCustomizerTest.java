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

    @Test void socketAndConnectTimeoutsAreSetByDefault() {
        // A stalled DB must not block the caller thread forever — see DatabaseQueryTimeoutTest
        // for the statement-level timeout; these are the socket-level bound underneath it.
        try (Database d = new Database(cfg(), LoggerFactory.getLogger("t"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertNotNull(p.getProperty("socketTimeout"), "socketTimeout must have a default");
            assertNotNull(p.getProperty("connectTimeout"), "connectTimeout must have a default");
            assertTrue(Integer.parseInt(p.getProperty("socketTimeout")) > 0);
        }
    }

    @Test void customizerCanOverrideTheDefaultSocketTimeout() {
        // Non-critical setting (unlike the security pins) — a caller with different needs can override it.
        try (Database d = new Database(cfg(), LoggerFactory.getLogger("t"),
                hc -> hc.addDataSourceProperty("socketTimeout", "5000"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertEquals("5000", p.getProperty("socketTimeout"));
        }
    }
}
