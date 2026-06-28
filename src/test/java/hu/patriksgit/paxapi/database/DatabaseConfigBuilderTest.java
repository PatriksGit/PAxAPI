package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.Test;
import hu.patriksgit.paxapi.database.DatabaseConfig.SslMode;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigBuilderTest {

    @Test void buildsFullConfig() {
        DatabaseConfig c = DatabaseConfig.builder()
            .host("h").port(3307).database("d").username("u").password("p")
            .poolSize(8).connectionTimeoutMs(5000).idleTimeoutMs(1000).maxLifetimeMs(2000)
            .sslMode(SslMode.VERIFY_IDENTITY).debugParams(true)
            .trustStore("file:/ks.jks", "tspw", "JKS").poolName("MyPlugin")
            .build();
        assertEquals("h", c.host());
        assertEquals(3307, c.port());
        assertEquals("d", c.database());
        assertEquals("u", c.username());
        assertEquals("p", c.password());
        assertEquals(8, c.poolSize());
        assertEquals(5000, c.connectionTimeoutMs());
        assertEquals(1000, c.idleTimeoutMs());
        assertEquals(2000, c.maxLifetimeMs());
        assertEquals(SslMode.VERIFY_IDENTITY, c.sslMode());
        assertTrue(c.debugParams());
        assertEquals("file:/ks.jks", c.trustStoreUrl());
        assertEquals("tspw", c.trustStorePassword());
        assertEquals("JKS", c.trustStoreType());
        assertEquals("MyPlugin", c.poolName());
    }

    @Test void appliesDefaults() {
        DatabaseConfig c = DatabaseConfig.builder()
            .host("h").database("d").username("u").password("p").sslMode(SslMode.DISABLED)
            .build();
        assertEquals(3306, c.port());
        assertEquals(10, c.poolSize());
        assertEquals(0, c.connectionTimeoutMs());
        assertEquals(0, c.idleTimeoutMs());
        assertEquals(0, c.maxLifetimeMs());
        assertFalse(c.debugParams());
        assertNull(c.trustStoreUrl());
        assertNull(c.trustStorePassword());
        assertNull(c.trustStoreType());
        assertNull(c.poolName());
    }

    @Test void requiresMandatoryFields() {
        assertThrows(NullPointerException.class, () -> DatabaseConfig.builder()
            .host("h").database("d").username("u").sslMode(SslMode.DISABLED).build()); // no password
        assertThrows(NullPointerException.class, () -> DatabaseConfig.builder()
            .host("h").database("d").username("u").password("p").build());             // no sslMode
    }

    @Test void poolSizeStillClamped() {
        DatabaseConfig c = DatabaseConfig.builder()
            .host("h").database("d").username("u").password("p").sslMode(SslMode.DISABLED)
            .poolSize(0).build();
        assertEquals(1, c.poolSize());
    }
}
