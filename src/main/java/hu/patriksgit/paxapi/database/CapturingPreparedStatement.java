ackage hu.patriksgit.paxapi.database;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.List;

/**
 * Delegating {@link PreparedStatement} that records bound parameter values (in
 * index order) so {@link DataAccessException} can describe them. Only the setters
 * the helpers actually use are recorded; everything else delegates unchanged.
 * Not thread-safe — used within a single helper call on one thread.
 */
final class CapturingPreparedStatement implements PreparedStatement {

    private final PreparedStatement d;     // delegate
    private final List<Object> captured;

    CapturingPreparedStatement(PreparedStatement delegate, List<Object> captured) {
        this.d = delegate;
        this.captured = captured;
    }

    /** Upper bound on recorded parameter index — guards against a pathological index OOM-ing the list. */
    private static final int MAX_CAPTURE_INDEX = 4096;

    private void capture(int index, Object value) {
        // Defensive: a bad/huge index would otherwise pad the list unboundedly (OOM) or set(-1).
        // The delegate setter runs first (see setters below), so a real driver has already rejected
        // an out-of-range index with a clean SQLException before we get here.
        if (index < 1 || index > MAX_CAPTURE_INDEX) return;
        // pad so the list is dense in parameter order (1-based -> 0-based)
        while (captured.size() < index) captured.add(null);
        captured.set(index - 1, value);
    }

    // --- recorded setters (the ones repositories use) ---
    // Delegate FIRST, then capture: a bad index surfaces as the driver's clean SQLException
    // (wrapped into DataAccessException) instead of a raw IndexOutOfBounds from our padding.
    @Override public void setString(int i, String x) throws SQLException { d.setString(i, x); capture(i, x); }
    @Override public void setInt(int i, int x) throws SQLException { d.setInt(i, x); capture(i, x); }
    @Override public void setLong(int i, long x) throws SQLException { d.setLong(i, x); capture(i, x); }
    @Override public void setBoolean(int i, boolean x) throws SQLException { d.setBoolean(i, x); capture(i, x); }
    @Override public void setDouble(int i, double x) throws SQLException { d.setDouble(i, x); capture(i, x); }
    @Override public void setFloat(int i, float x) throws SQLException { d.setFloat(i, x); capture(i, x); }
    @Override public void setShort(int i, short x) throws SQLException { d.setShort(i, x); capture(i, x); }
    @Override public void setByte(int i, byte x) throws SQLException { d.setByte(i, x); capture(i, x); }
    @Override public void setBytes(int i, byte[] x) throws SQLException { d.setBytes(i, x); capture(i, x); }
    @Override public void setObject(int i, Object x) throws SQLException { d.setObject(i, x); capture(i, x); }
    @Override public void setObject(int i, Object x, int t) throws SQLException { d.setObject(i, x, t); capture(i, x); }
    @Override public void setTimestamp(int i, Timestamp x) throws SQLException { d.setTimestamp(i, x); capture(i, x); }
    @Override public void setDate(int i, Date x) throws SQLException { d.setDate(i, x); capture(i, x); }
    @Override public void setNull(int i, int t) throws SQLException { d.setNull(i, t); capture(i, null); }

    // --- pure delegation for everything else ---
    @Override public ResultSet executeQuery() throws SQLException { return d.executeQuery(); }
    @Override public int executeUpdate() throws SQLException { return d.executeUpdate(); }
    @Override public boolean execute() throws SQLException { return d.execute(); }
    @Override public void addBatch() throws SQLException { d.addBatch(); }
    @Override public int[] executeBatch() throws SQLException { return d.executeBatch(); }
    @Override public void clearParameters() throws SQLException { d.clearParameters(); }
    @Override public ResultSet executeQuery(String sql) throws SQLException { return d.executeQuery(sql); }
    @Override public int executeUpdate(String sql) throws SQLException { return d.executeUpdate(sql); }
    @Override public boolean execute(String sql) throws SQLException { return d.execute(sql); }
    @Override public void close() throws SQLException { d.close(); }
    @Override public void setBigDecimal(int i, BigDecimal x) throws SQLException { d.setBigDecimal(i, x); }
    @Override public void setTime(int i, Time x) throws SQLException { d.setTime(i, x); }
    @Override public void setObject(int i, Object x, int t, int s) throws SQLException { d.setObject(i, x, t, s); }
    @Override public ResultSetMetaData getMetaData() throws SQLException { return d.getMetaData(); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { return d.getParameterMetaData(); }
    @Override public void setMaxFieldSize(int max) throws SQLException { d.setMaxFieldSize(max); }
    @Override public int getMaxFieldSize() throws SQLException { return d.getMaxFieldSize(); }
    @Override public void setMaxRows(int max) throws SQLException { d.setMaxRows(max); }
    @Override public int getMaxRows() throws SQLException { return d.getMaxRows(); }
    @Override public void setEscapeProcessing(boolean e) throws SQLException { d.setEscapeProcessing(e); }
    @Override public void setQueryTimeout(int s) throws SQLException { d.setQueryTimeout(s); }
    @Override public int getQueryTimeout() throws SQLException { return d.getQueryTimeout(); }
    @Override public void cancel() throws SQLException { d.cancel(); }
    @Override public SQLWarning getWarnings() throws SQLException { return d.getWarnings(); }
    @Override public void clearWarnings() throws SQLException { d.clearWarnings(); }
    @Override public void setCursorName(String n) throws SQLException { d.setCursorName(n); }
    @Override public ResultSet getResultSet() throws SQLException { return d.getResultSet(); }
    @Override public int getUpdateCount() throws SQLException { return d.getUpdateCount(); }
    @Override public boolean getMoreResults() throws SQLException { return d.getMoreResults(); }
    @Override public void setFetchDirection(int dir) throws SQLException { d.setFetchDirection(dir); }
    @Override public int getFetchDirection() throws SQLException { return d.getFetchDirection(); }
    @Override public void setFetchSize(int rows) throws SQLException { d.setFetchSize(rows); }
    @Override public int getFetchSize() throws SQLException { return d.getFetchSize(); }
    @Override public int getResultSetConcurrency() throws SQLException { return d.getResultSetConcurrency(); }
    @Override public int getResultSetType() throws SQLException { return d.getResultSetType(); }
    @Override public void addBatch(String sql) throws SQLException { d.addBatch(sql); }
    @Override public void clearBatch() throws SQLException { d.clearBatch(); }
    @Override public Connection getConnection() throws SQLException { return d.getConnection(); }
    @Override public boolean getMoreResults(int c) throws SQLException { return d.getMoreResults(c); }
    @Override public ResultSet getGeneratedKeys() throws SQLException { return d.getGeneratedKeys(); }
    @Override public int executeUpdate(String sql, int ag) throws SQLException { return d.executeUpdate(sql, ag); }
    @Override public int executeUpdate(String sql, int[] ci) throws SQLException { return d.executeUpdate(sql, ci); }
    @Override public int executeUpdate(String sql, String[] cn) throws SQLException { return d.executeUpdate(sql, cn); }
    @Override public boolean execute(String sql, int ag) throws SQLException { return d.execute(sql, ag); }
    @Override public boolean execute(String sql, int[] ci) throws SQLException { return d.execute(sql, ci); }
    @Override public boolean execute(String sql, String[] cn) throws SQLException { return d.execute(sql, cn); }
    @Override public int getResultSetHoldability() throws SQLException { return d.getResultSetHoldability(); }
    @Override public boolean isClosed() throws SQLException { return d.isClosed(); }
    @Override public void setPoolable(boolean p) throws SQLException { d.setPoolable(p); }
    @Override public boolean isPoolable() throws SQLException { return d.isPoolable(); }
    @Override public void closeOnCompletion() throws SQLException { d.closeOnCompletion(); }
    @Override public boolean isCloseOnCompletion() throws SQLException { return d.isCloseOnCompletion(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return d.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return d.isWrapperFor(iface); }
    @Override public void setAsciiStream(int i, InputStream x, int l) throws SQLException { d.setAsciiStream(i, x, l); }
    @Override public void setBinaryStream(int i, InputStream x, int l) throws SQLException { d.setBinaryStream(i, x, l); }
    @Override public void setCharacterStream(int i, Reader r, int l) throws SQLException { d.setCharacterStream(i, r, l); }
    @Override public void setAsciiStream(int i, InputStream x, long l) throws SQLException { d.setAsciiStream(i, x, l); }
    @Override public void setBinaryStream(int i, InputStream x, long l) throws SQLException { d.setBinaryStream(i, x, l); }
    @Override public void setCharacterStream(int i, Reader r, long l) throws SQLException { d.setCharacterStream(i, r, l); }
    @Override public void setAsciiStream(int i, InputStream x) throws SQLException { d.setAsciiStream(i, x); }
    @Override public void setBinaryStream(int i, InputStream x) throws SQLException { d.setBinaryStream(i, x); }
    @Override public void setCharacterStream(int i, Reader r) throws SQLException { d.setCharacterStream(i, r); }
    @Override public void setNCharacterStream(int i, Reader r) throws SQLException { d.setNCharacterStream(i, r); }
    @Override public void setNCharacterStream(int i, Reader r, long l) throws SQLException { d.setNCharacterStream(i, r, l); }
    @Override public void setClob(int i, Reader r) throws SQLException { d.setClob(i, r); }
    @Override public void setClob(int i, Reader r, long l) throws SQLException { d.setClob(i, r, l); }
    @Override public void setClob(int i, Clob x) throws SQLException { d.setClob(i, x); }
    @Override public void setBlob(int i, InputStream s) throws SQLException { d.setBlob(i, s); }
    @Override public void setBlob(int i, InputStream s, long l) throws SQLException { d.setBlob(i, s, l); }
    @Override public void setBlob(int i, Blob x) throws SQLException { d.setBlob(i, x); }
    @Override public void setNClob(int i, Reader r) throws SQLException { d.setNClob(i, r); }
    @Override public void setNClob(int i, Reader r, long l) throws SQLException { d.setNClob(i, r, l); }
    @Override public void setNClob(int i, NClob x) throws SQLException { d.setNClob(i, x); }
    @Override public void setNString(int i, String v) throws SQLException { d.setNString(i, v); }
    @Override public void setArray(int i, Array x) throws SQLException { d.setArray(i, x); }
    @Override public void setRef(int i, Ref x) throws SQLException { d.setRef(i, x); }
    @Override public void setURL(int i, java.net.URL x) throws SQLException { d.setURL(i, x); }
    @Override public void setRowId(int i, RowId x) throws SQLException { d.setRowId(i, x); }
    @Override public void setSQLXML(int i, SQLXML x) throws SQLException { d.setSQLXML(i, x); }
    @Override public void setUnicodeStream(int i, InputStream x, int l) throws SQLException { d.setUnicodeStream(i, x, l); }
    @Override public void setNull(int i, int t, String tn) throws SQLException { d.setNull(i, t, tn); capture(i, null); }
    @Override public void setTimestamp(int i, Timestamp x, java.util.Calendar c) throws SQLException { d.setTimestamp(i, x, c); capture(i, x); }
    @Override public void setTime(int i, Time x, java.util.Calendar c) throws SQLException { d.setTime(i, x, c); }
    @Override public void setDate(int i, Date x, java.util.Calendar c) throws SQLException { d.setDate(i, x, c); capture(i, x); }
    @Override public void setObject(int i, Object x, SQLType t, int s) throws SQLException { d.setObject(i, x, t, s); capture(i, x); }
    @Override public void setObject(int i, Object x, SQLType t) throws SQLException { d.setObject(i, x, t); capture(i, x); }
}
