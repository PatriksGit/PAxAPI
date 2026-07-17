package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link SchemaPrefixGuard} against an in-memory H2 (MySQL mode), same seam as
 * {@link DatabaseHelpersIT}. Run with: mvn -Pit verify.
 */
class SchemaPrefixGuardIT {

    private org.h2.jdbcx.JdbcDataSource h2;

    @BeforeEach void setUp() throws Exception {
        h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:schemaprefixguard;MODE=MySQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        try (Connection c = h2.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + SchemaPrefixGuard.META_TABLE);
            st.execute("DROP TABLE IF EXISTS accounts");
        }
    }

    @Test void firstStartRegistersPrefixAndPasses() throws Exception {
        try (Connection c = h2.getConnection()) {
            assertDoesNotThrow(() ->
                SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", Set.of()));
        }
    }

    @Test void secondStartWithMatchingPrefixPasses() throws Exception {
        try (Connection c = h2.getConnection()) {
            SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", Set.of());
            assertDoesNotThrow(() ->
                SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", Set.of()));
        }
    }

    @Test void mismatchedPrefixOnSecondStartThrows() throws Exception {
        try (Connection c = h2.getConnection()) {
            SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", Set.of());
            SQLException e = assertThrows(SQLException.class, () ->
                SchemaPrefixGuard.ensureConsistency(c, "paxauth", "wrong_", Set.of()));
            assertTrue(e.getMessage().contains("paxauth_"));
            assertTrue(e.getMessage().contains("wrong_"));
        }
    }

    @Test void independentComponentsTrackSeparatePrefixes() throws Exception {
        try (Connection c = h2.getConnection()) {
            SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", Set.of());
            assertDoesNotThrow(() ->
                SchemaPrefixGuard.ensureConsistency(c, "paxstaff", "paxstaff_", Set.of()));
        }
    }

    @Test void legacyTableCollisionWithNonEmptyPrefixThrows() throws Exception {
        try (Connection c = h2.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE accounts (id INT PRIMARY KEY)");
            SQLException e = assertThrows(SQLException.class, () ->
                SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", Set.of("accounts")));
            assertTrue(e.getMessage().contains("accounts"));
        }
    }

    @Test void legacyTableWithEmptyPrefixDoesNotThrow() throws Exception {
        try (Connection c = h2.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE accounts (id INT PRIMARY KEY)");
            // Empty prefix IS the legacy layout, so no collision to guard against.
            assertDoesNotThrow(() ->
                SchemaPrefixGuard.ensureConsistency(c, "paxauth", "", Set.of("accounts")));
        }
    }

    @Test void noLegacyTableWithNonEmptyPrefixDoesNotThrow() throws Exception {
        try (Connection c = h2.getConnection()) {
            // legacyUnprefixedTableNames lists a table that doesn't actually exist yet — fine.
            assertDoesNotThrow(() ->
                SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", Set.of("accounts")));
        }
    }

    @Test void nullElementInLegacyTableNamesThrowsCleanlyInsteadOfNpe() throws Exception {
        // A null element must fail fast with a clear IllegalArgumentException, not an
        // undocumented NullPointerException that a caller catching only SQLException would miss.
        java.util.Set<String> withNull = new java.util.HashSet<>();
        withNull.add("accounts");
        withNull.add(null);
        try (Connection c = h2.getConnection()) {
            assertThrows(IllegalArgumentException.class, () ->
                SchemaPrefixGuard.ensureConsistency(c, "paxauth", "paxauth_", withNull));
        }
    }
}
