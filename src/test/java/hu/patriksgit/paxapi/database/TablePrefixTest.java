package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TablePrefixTest {

    @Test void emptyPrefixIsAllowed() {
        assertEquals("", TablePrefix.validate("", 20));
        assertEquals("", TablePrefix.validate(null, 20));
    }

    @Test void trimsWhitespace() {
        assertEquals("paxauth_", TablePrefix.validate("  paxauth_  ", 20));
    }

    @Test void validPrefixPasses() {
        assertEquals("paxauth_", TablePrefix.validate("paxauth_", 20));
        assertEquals("PaxStaff123", TablePrefix.validate("PaxStaff123", 20));
    }

    @Test void rejectsDisallowedCharacters() {
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate("pax-auth_", 20));
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate("pax.auth", 20));
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate("pax auth", 20));
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate("pax'; DROP TABLE x; --", 20));
    }

    @Test void rejectsWhenCombinedLengthExceedsMysqlLimit() {
        // 64-char limit: a 50-char prefix + a 20-char base table name (70) must be rejected.
        String prefix = "p".repeat(50);
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate(prefix, 20));
    }

    @Test void allowsExactlyAtMysqlLimit() {
        String prefix = "p".repeat(44);
        assertEquals(prefix, TablePrefix.validate(prefix, 20)); // 44 + 20 == 64, must pass
    }

    @Test void rejectsOneOverMysqlLimit() {
        String prefix = "p".repeat(45);
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate(prefix, 20)); // 45 + 20 == 65
    }

    @Test void resolveConcatenatesPrefixAndBaseName() {
        assertEquals("paxauth_accounts", TablePrefix.resolve("paxauth_", "accounts"));
        assertEquals("accounts", TablePrefix.resolve("", "accounts"));
    }

    @Test void resolveRejectsNulls() {
        assertThrows(NullPointerException.class, () -> TablePrefix.resolve(null, "accounts"));
        assertThrows(NullPointerException.class, () -> TablePrefix.resolve("paxauth_", null));
    }

    @Test void rejectsNegativeLongestBaseTableNameLength() {
        // A negative length must not be allowed to offset the prefix length and silently
        // bypass the 64-char MySQL identifier check.
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate("paxauth_", -1));
        assertThrows(IllegalArgumentException.class, () -> TablePrefix.validate("p".repeat(60), -1000));
    }
}
