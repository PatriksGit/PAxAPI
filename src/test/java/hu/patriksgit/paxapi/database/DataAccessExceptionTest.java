package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DataAccessExceptionTest {

    private static final String SQL = "UPDATE players SET pw=? WHERE name=?";
    private final SQLException cause = new SQLException("dup", "23000", 1062);

    @Test void alwaysIncludesSqlAndCause() {
        DataAccessException e = DataAccessException.wrap(SQL, List.of("hash", "Steve"), false, cause);
        assertTrue(e.getMessage().contains(SQL), "SQL must be in the message");
        assertSame(cause, e.getCause());
    }

    @Test void typesOnlyWhenDebugOff() {
        DataAccessException e = DataAccessException.wrap(SQL, List.of("hash", "Steve"), false, cause);
        assertTrue(e.getMessage().contains("String"), "param TYPES present");
        assertFalse(e.getMessage().contains("hash"), "param VALUES must NOT leak when debug off");
        assertFalse(e.getMessage().contains("Steve"));
    }

    @Test void valuesWhenDebugOn() {
        DataAccessException e = DataAccessException.wrap(SQL, List.of("hash", "Steve"), true, cause);
        assertTrue(e.getMessage().contains("hash"), "param VALUES present when debug on");
        assertTrue(e.getMessage().contains("Steve"));
    }

    @Test void handlesNullParamList() {
        DataAccessException e = DataAccessException.wrap(SQL, null, false, cause);
        assertTrue(e.getMessage().contains(SQL));
    }

    @Test void nullValueRendersAsNullType() {
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(null);
        DataAccessException e = DataAccessException.wrap(SQL, params, false, cause);
        assertTrue(e.getMessage().contains("null"));
    }

    @Test void debugValueTruncatedAndLengthMarked() {
        String big = "x".repeat(200);
        DataAccessException e = DataAccessException.wrap(SQL, java.util.List.of(big), true, cause);
        assertFalse(e.getMessage().contains(big), "full long value must not appear");
        assertTrue(e.getMessage().contains("(200)"), "original length marker present");
    }

    @Test void debugValueControlCharsStripped() {
        DataAccessException e = DataAccessException.wrap(SQL, java.util.List.of("a\nb\tc"), true, cause);
        String m = e.getMessage();
        assertFalse(m.contains("\n"), "newline stripped from value");
        assertFalse(m.contains("\t"), "tab stripped from value");
    }

    @Test void sqlControlCharsStripped() {
        String multi = "SELECT *\nFROM players\tWHERE x=?";
        DataAccessException e = DataAccessException.wrap(multi, null, false, cause);
        String m = e.getMessage();
        assertFalse(m.contains("\n"), "newline stripped from SQL");
        assertFalse(m.contains("\t"), "tab stripped from SQL");
        assertTrue(m.contains("SELECT *"), "SQL still readable");
    }

    @Test void causeMessageControlCharsStrippedWhenDebugOn() {
        // MySQL echoes user data in error messages (e.g. 1062 "Duplicate entry '<value>'
        // for key '<key>'") — a newline in <value> would forge a log line via the cause.
        // Only reachable in debug mode now (see causeMessageOmittedWhenDebugOff) since the
        // driver's own message can contain PII independent of any bound-parameter debug flag.
        SQLException dirty = new SQLException("Duplicate entry 'a\nb' for key 'x'", "23000", 1062);
        DataAccessException e = DataAccessException.wrap(SQL, null, true, dirty);
        String m = e.getMessage();
        assertFalse(m.contains("\n"), "cause message must be control-stripped");
        assertTrue(m.contains("Duplicate entry"), "cause text still present");
    }

    @Test void causeMessageOmittedWhenDebugOff() {
        // The class's own contract (see class Javadoc) is that PII only appears in debug mode.
        // A raw MySQL SQLException routinely inlines actual row data into its OWN message
        // (e.g. an email/IP in a duplicate-key error) independent of debugParams — that text
        // must not leak into getMessage() by default just because a query failed.
        SQLException dirty = new SQLException("Duplicate entry 'user@example.com' for key 'idx_email'", "23000", 1062);
        DataAccessException e = DataAccessException.wrap(SQL, null, false, dirty);
        String m = e.getMessage();
        assertFalse(m.contains("user@example.com"), "driver message PII must not leak when debug off");
        assertFalse(m.contains("Duplicate entry"), "driver message text must not leak when debug off");
        assertTrue(m.contains("SQLException"), "exception type still present for debuggability");
        assertTrue(m.contains("23000"), "SQLState still present for debuggability");
        assertTrue(m.contains("1062"), "error code still present for debuggability");
    }

    @Test void unicodeLineSeparatorsStripped() {
        // NEL (), LS ( ), PS ( ) are not \n but some viewers/parsers break lines on them.
        DataAccessException e = DataAccessException.wrap(SQL, java.util.List.of("ab c d"), true, cause);
        String m = e.getMessage();
        assertFalse(m.contains(""), "NEL stripped");
        assertFalse(m.contains(" "), "LS stripped");
        assertFalse(m.contains(" "), "PS stripped");
    }

    @Test void byteArrayNeverDumpedEvenInDebug() {
        DataAccessException e = DataAccessException.wrap(SQL, java.util.List.of(new byte[]{1, 2, 3, 4}), true, cause);
        assertTrue(e.getMessage().contains("byte[4]"), "byte[] rendered content-free as byte[N]");
        assertFalse(e.getMessage().contains("[B@"), "no array identity hash leaked");
    }

    @Test void fromBodyMessageControlCharsStripped() {
        DataAccessException e = DataAccessException.fromBody("Batch failed: INSERT\nINTO t", new RuntimeException("x"));
        assertFalse(e.getMessage().contains("\n"), "fromBody message must be control-stripped");
        assertTrue(e.getMessage().contains("Batch failed"));
    }
}
