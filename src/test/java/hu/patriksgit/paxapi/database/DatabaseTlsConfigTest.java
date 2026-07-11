package hu.patriksgit.paxapi.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import hu.patriksgit.paxapi.database.DatabaseConfig.SslMode;

import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTlsConfigTest {

    private static DatabaseConfig.Builder b() {
        return DatabaseConfig.builder()
            .host("localhost").database("db").username("u").password("p")
            .poolSize(4).sslMode(SslMode.DISABLED);
    }

    private static DatabaseConfig base() {
        return b().build();
    }

    @Test void blastRadiusFlagsPinnedOff() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertEquals("false", p.getProperty("autoDeserialize"));
            assertEquals("false", p.getProperty("allowLoadLocalInfile"));
            assertEquals("false", p.getProperty("allowUrlInLocalInfile"));
            assertEquals("false", p.getProperty("allowMultiQueries"));
        }
    }

    @Test void poolHardeningDefaults() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            HikariDataSource h = (HikariDataSource) d.dataSource();
            assertEquals(60_000, h.getLeakDetectionThreshold());
            assertNull(h.getConnectionTestQuery(), "rely on JDBC4 isValid(), no SELECT 1");
        }
    }

    @Test void trustStorePropertiesSetWhenProvided() {
        DatabaseConfig cfg = b().trustStore("file:/ks.jks", "tspw", "PKCS12").build();
        try (Database d = new Database(cfg, LoggerFactory.getLogger("t"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertEquals("file:/ks.jks", p.getProperty("trustCertificateKeyStoreUrl"));
            assertEquals("tspw", p.getProperty("trustCertificateKeyStorePassword"));
            assertEquals("PKCS12", p.getProperty("trustCertificateKeyStoreType"));
        }
    }

    @Test void trustStorePropertiesAbsentByDefault() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            Properties p = ((HikariDataSource) d.dataSource()).getDataSourceProperties();
            assertNull(p.getProperty("trustCertificateKeyStoreUrl"));
        }
    }

    @Test void poolNameDerivedFromDatabaseByDefault() {
        try (Database d = new Database(base(), LoggerFactory.getLogger("t"))) {
            assertEquals("PAxAPI-DB-db", ((HikariDataSource) d.dataSource()).getPoolName());
        }
    }

    @Test void poolNameOverrideUsedVerbatim() {
        try (Database d = new Database(b().poolName("MineAuth").build(), LoggerFactory.getLogger("t"))) {
            assertEquals("MineAuth", ((HikariDataSource) d.dataSource()).getPoolName());
        }
    }

    @Test void poolNameRejectsJmxUnsafeChars() {
        assertThrows(IllegalArgumentException.class,
            () -> new Database(b().poolName("a:b=c").build(), LoggerFactory.getLogger("t")));
    }

    @Test void rejectsRemoteTrustStoreUrl() {
        assertThrows(IllegalArgumentException.class,
            () -> new Database(b().trustStore("https://evil/ca.jks", "p", "JKS").build(), LoggerFactory.getLogger("t")));
        assertThrows(IllegalArgumentException.class,
            () -> new Database(b().trustStore("http://evil/ca.jks", "p", "JKS").build(), LoggerFactory.getLogger("t")));
    }

    /**
     * The guard must be an ALLOWLIST of {@code file:}, not a denylist of specific remote schemes —
     * a denylist of just http(s) lets other URL-Handler-backed remote schemes (jar: over http,
     * ftp:) still trigger a remote fetch of the truststore, defeating the MITM protection this
     * check exists for.
     */
    @Test void rejectsNonFileSchemesThatCanStillFetchRemotely() {
        assertThrows(IllegalArgumentException.class,
            () -> new Database(b().trustStore("jar:http://evil/x.jar!/ca.jks", "p", "JKS").build(), LoggerFactory.getLogger("t")),
            "jar: wrapping a remote http URL must still be rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new Database(b().trustStore("ftp://evil/ca.jks", "p", "JKS").build(), LoggerFactory.getLogger("t")),
            "ftp: is also a remote fetch and must be rejected");
    }
}
