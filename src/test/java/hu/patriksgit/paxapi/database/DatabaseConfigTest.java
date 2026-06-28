package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import hu.patriksgit.paxapi.database.DatabaseConfig.SslMode;

class DatabaseConfigTest {

    @Test void parseCanonicalNames() {
        assertEquals(SslMode.DISABLED, SslMode.parse("DISABLED"));
        assertEquals(SslMode.REQUIRED, SslMode.parse("required"));
        assertEquals(SslMode.VERIFY_CA, SslMode.parse("verify_ca"));
        assertEquals(SslMode.VERIFY_IDENTITY, SslMode.parse("VERIFY-IDENTITY")); // hyphen normalized
    }

    @Test void parseLegacyBooleans() {
        assertEquals(SslMode.DISABLED, SslMode.parse("false"));
        assertEquals(SslMode.DISABLED, SslMode.parse("OFF"));
        assertEquals(SslMode.VERIFY_CA, SslMode.parse("true"));
        assertEquals(SslMode.VERIFY_CA, SslMode.parse("yes"));
    }

    @Test void parseFailsClosedOnGarbage() {
        assertThrows(IllegalArgumentException.class, () -> SslMode.parse("vrify_ca"));
        assertThrows(IllegalArgumentException.class, () -> SslMode.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SslMode.parse(null));
    }

    @Test void toStringRedactsSecrets() {
        DatabaseConfig c = DatabaseConfig.builder()
            .host("h").database("db").username("admin").password("s3cr3t")
            .sslMode(SslMode.DISABLED).poolSize(4)
            .trustStore("file:/ks.jks", "tspw", "JKS")
            .build();
        String s = c.toString();
        assertFalse(s.contains("s3cr3t"), "DB password must not appear");
        assertFalse(s.contains("tspw"), "truststore password must not appear");
        assertTrue(s.contains("password=***"), "DB password redacted");
        assertTrue(s.contains("trustStorePassword=***"), "truststore password redacted");
        assertTrue(s.contains("admin"), "username stays visible for debugging");
        assertTrue(s.contains("db"));
    }
}
