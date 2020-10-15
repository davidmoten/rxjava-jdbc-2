package org.davidmoten.rx.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.annotation.RegEx;
import javax.sql.DataSource;

import org.apache.commons.io.IOUtils;
import org.davidmoten.rx.jdbc.annotations.Column;
import org.davidmoten.rx.jdbc.annotations.Index;
import org.davidmoten.rx.jdbc.callable.internal.OutParameterPlaceholder;
import org.davidmoten.rx.jdbc.callable.internal.ParameterPlaceholder;
import org.davidmoten.rx.jdbc.exceptions.AnnotationsNotFoundException;
import org.davidmoten.rx.jdbc.exceptions.AutomappedInterfaceInaccessibleException;
import org.davidmoten.rx.jdbc.exceptions.ColumnIndexOutOfRangeException;
import org.davidmoten.rx.jdbc.exceptions.ColumnNotFoundException;
import org.davidmoten.rx.jdbc.exceptions.MoreColumnsRequestedThanExistException;
import org.davidmoten.rx.jdbc.exceptions.NamedParameterFoundButSqlDoesNotHaveNamesException;
import org.davidmoten.rx.jdbc.exceptions.NamedParameterMissingException;
import org.davidmoten.rx.jdbc.exceptions.ParameterMissingNameException;
import org.davidmoten.rx.jdbc.exceptions.SQLRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.davidmoten.guavamini.annotations.VisibleForTesting;

public enum Util {
    ;

    private static final Logger log = LoggerFactory.getLogger(Util.class);
    public static final int QUERY_TIMEOUT_NOT_SET = -1;

    /**
     * Sets parameters for the {@link PreparedStatement}.
     * 
     * @param ps
     * @param params
     * @throws SQLException
     */
    @VisibleForTesting
    static void setParameters(PreparedStatement ps, List<Parameter> params, boolean namesAllowed) throws SQLException {
        int j = 1;
        for (int i = 0; i < params.size(); i++) {
            Parameter p = params.get(i);
            if (p.hasName() && !namesAllowed) {
                throw new NamedParameterFoundButSqlDoesNotHaveNamesException(
                        "named parameter found but sql does not contain names ps=" + ps);
            }
            Object v = p.value();
            if (p.isCollection()) {
                for (Object o : ((Collection<?>) v)) {
                    setParameter(ps, j, o);
                    j++;
                }
            } else {
                setParameter(ps, j, v);
                j++;
            }
        }
    }

    static void setParameter(PreparedStatement ps, int i, Object o) throws SQLException {
        log.debug("setting parameter {} to {}", i, o);
        try {
            if (o == null)
                ps.setObject(i, null);
            else if (o == Database.NULL_CLOB)
                ps.setNull(i, Types.CLOB);
            else if (o == Database.NULL_BLOB)
                ps.setNull(i, Types.BLOB);
            else {
                Class<?> cls = o.getClass();
                if (Clob.class.isAssignableFrom(cls)) {
                    setClob(ps, i, o, cls);
                } else if (Blob.class.isAssignableFrom(cls)) {
                    setBlob(ps, i, o, cls);
                } else if (Calendar.class.isAssignableFrom(cls)) {
                    Calendar cal = (Calendar) o;
                    Timestamp t = new Timestamp(cal.getTimeInMillis());
                    ps.setTimestamp(i, t);
                } else if (Time.class.isAssignableFrom(cls)) {
                    Calendar cal = Calendar.getInstance();
                    ps.setTime(i, (Time) o, cal);
                } else if (Timestamp.class.isAssignableFrom(cls)) {
                    Calendar cal = Calendar.getInstance();
                    ps.setTimestamp(i, (Timestamp) o, cal);
                } else if (java.sql.Date.class.isAssignableFrom(cls)) {
                    ps.setDate(i, (java.sql.Date) o, Calendar.getInstance());
                } else if (java.util.Date.class.isAssignableFrom(cls)) {
                    Calendar cal = Calendar.getInstance();
                    java.util.Date date = (java.util.Date) o;
                    ps.setTimestamp(i, new Timestamp(date.getTime()), cal);
                } else if (Instant.class.isAssignableFrom(cls)) {
                    Calendar cal = Calendar.getInstance();
                    Instant instant = (Instant) o;
                    ps.setTimestamp(i, new Timestamp(instant.toEpochMilli()), cal);
                } else if (ZonedDateTime.class.isAssignableFrom(cls)) {
                    Calendar cal = Calendar.getInstance();
                    ZonedDateTime d = (ZonedDateTime) o;
                    ps.setTimestamp(i, new Timestamp(d.toInstant().toEpochMilli()), cal);
                } else
                    ps.setObject(i, o);
            }
        } catch (SQLException e) {
            log.debug("{} when setting ps.setObject({},{})", e.getMessage(), i, o);
            throw e;
        }
    }

    /**
     * Sets a blob parameter for the prepared statement.
     * 
     * @param ps
     * @param i
     * @param o
     * @param cls
     * @throws SQLException
     */
    private static void setBlob(PreparedStatement ps, int i, Object o, Class<?> cls) throws SQLException {
        // if (o instanceof Blob) {
        ps.setBlob(i, (Blob) o);
        // } else {
        // final InputStream is;
        // if (o instanceof byte[]) {
        // is = new ByteArrayInputStream((byte[]) o);
        // } else if (o instanceof InputStream)
        // is = (InputStream) o;
        // else
        // throw new RuntimeException("cannot insert parameter of type " + cls + " into
        // blob column " + i);
        // Blob c = ps.getConnection().createBlob();
        // OutputStream os = c.setBinaryStream(1);
        // copy(is, os);
        // ps.setBlob(i, c);
        // }
    }

    /**
     * Sets the clob parameter for the prepared statement.
     * 
     * @param ps
     * @param i
     * @param o
     * @param cls
     * @throws SQLException
     */
    private static void setClob(PreparedStatement ps, int i, Object o, Class<?> cls) throws SQLException {
        // if (o instanceof Clob) {
        ps.setClob(i, (Clob) o);
        // } else {
        // final Reader r;
        // if (o instanceof String)
        // r = new StringReader((String) o);
        // else if (o instanceof Reader)
        // r = (Reader) o;
        // else
        // throw new RuntimeException("cannot insert parameter of type " + cls + " into
        // clob column " + i);
        // Clob c = ps.getConnection().createClob();
        // Writer w = c.setCharacterStream(1);
        // copy(r, w);
        // ps.setClob(i, c);
        // }
    }

    static void setNamedParameters(PreparedStatement ps, List<Parameter> parameters, List<String> names)
            throws SQLException {
        Map<String, Parameter> map = createMap(parameters);
        List<Parameter> list = new ArrayList<Parameter>();
        for (String name : names) {
            if (!map.containsKey(name))
                throw new NamedParameterMissingException("named parameter is missing for '" + name + "'");
            Parameter p = map.get(name);
            list.add(p);
        }
        Util.setParameters(ps, list, true);
    }

    @VisibleForTesting
    static Map<String, Parameter> createMap(List<Parameter> parameters) {
        Map<String, Parameter> map = new HashMap<String, Parameter>();
        for (Parameter p : parameters) {
            if (p.hasName()) {
                map.put(p.name(), p);
            } else {
                throw new ParameterMissingNameException(
                        "named parameters were expected but this parameter did not have a name: " + p);
            }
        }
        return map;
    }

    static PreparedStatement convertAndSetParameters(PreparedStatement ps, List<Object> parameters, List<String> names)
            throws SQLException {
        return setParameters(ps, toParameters(parameters), names);
    }

    static PreparedStatement setParameters(PreparedStatement ps, List<Parameter> parameters, List<String> names)
            throws SQLException {
        if (names.isEmpty()) {
            Util.setParameters(ps, parameters, false);
        } else {
            Util.setNamedParameters(ps, parameters, names);
        }
        return ps;
    }

    static List<Parameter> toParameters(List<Object> parameters) {
        return parameters.stream().map(o -> {
            if (o instanceof Parameter) {
                return (Parameter) o;
            } else {
                return new Parameter(o);
            }
        }).collect(Collectors.toList());
    }

    static void incrementCounter(Connection connection) {
        if (connection instanceof TransactedConnection) {
            TransactedConnection c = (TransactedConnection) connection;
            c.incrementCounter();
        }
    }

    public static void closeSilently(AutoCloseable c) {
        if (c != null) {
            try {
                log.debug("closing {}", c);
                c.close();
            } catch (Exception e) {
                log.debug("ignored exception {}, {}, {}", e.getMessage(), e.getClass(), e);
            }
        }
    }

    static void closePreparedStatementAndConnection(PreparedStatement ps) {
        Connection con = null;
        try {
            con = ps.getConnection();
        } catch (SQLException e) {
            log.warn(e.getMessage(), e);
        }
        closeSilently(ps);
        closeSilently(con);
    }

    static void closePreparedStatementAndConnection(NamedPreparedStatement ps) {
        closePreparedStatementAndConnection(ps.ps);
    }

    static void closeCallableStatementAndConnection(NamedCallableStatement stmt) {
        closePreparedStatementAndConnection(stmt.stmt);
    }

    static NamedPreparedStatement prepare(Connection con, String sql, int queryTimeoutSec) throws SQLException {
        return prepare(con, 0, sql, queryTimeoutSec);
    }

    static NamedPreparedStatement prepare(Connection con, int fetchSize, String sql, int queryTimeoutSec) throws SQLException {
        // TODO can we parse SqlInfo through because already calculated by
        // builder?
        SqlInfo info = SqlInfo.parse(sql);
        log.debug("preparing statement: {}", sql);
        return prepare(con, fetchSize, info, queryTimeoutSec);
    }

    static PreparedStatement prepare(Connection connection, int fetchSize, String sql, List<Parameter> parameters, int queryTimeoutSec)
            throws SQLException {
        // should only get here when parameters contains a collection
        SqlInfo info = SqlInfo.parse(sql, parameters);
        log.debug("preparing statement: {}", info.sql());
        return createPreparedStatement(connection, fetchSize, info, queryTimeoutSec);
    }

    private static NamedPreparedStatement prepare(Connection con, int fetchSize, SqlInfo info, int queryTimeoutSec) throws SQLException {
        PreparedStatement ps = createPreparedStatement(con, fetchSize, info, queryTimeoutSec);
        return new NamedPreparedStatement(ps, info.names());
    }

    private static PreparedStatement createPreparedStatement(Connection con, int fetchSize, SqlInfo info, int queryTimeoutSec)
            throws SQLException {
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement(info.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            if (fetchSize > 0) {
                ps.setFetchSize(fetchSize);
            }

            if (queryTimeoutSec != QUERY_TIMEOUT_NOT_SET) {
                ps.setQueryTimeout(queryTimeoutSec);
            }
        } catch (RuntimeException | SQLException e) {
            if (ps != null) {
                ps.close();
            }
            throw e;
        }
        return ps;
    }

    static NamedCallableStatement prepareCall(Connection con, String sql,
            List<ParameterPlaceholder> parameterPlaceholders) throws SQLException {
        return prepareCall(con, 0, sql, parameterPlaceholders);
    }

    // TODO is fetchSize required for callablestatement
    static NamedCallableStatement prepareCall(Connection con, int fetchSize, String sql,
            List<ParameterPlaceholder> parameterPlaceholders) throws SQLException {
        // TODO can we parse SqlInfo through because already calculated by
        // builder?
        SqlInfo s = SqlInfo.parse(sql);
        log.debug("preparing statement: {}", sql);
        CallableStatement ps = null;
        try {
            ps = con.prepareCall(s.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            for (int i = 0; i < parameterPlaceholders.size(); i++) {
                ParameterPlaceholder p = parameterPlaceholders.get(i);
                if (p instanceof OutParameterPlaceholder) {
                    ps.registerOutParameter(i + 1, ((OutParameterPlaceholder) p).type().value());
                }
            }
            if (fetchSize > 0) {
                ps.setFetchSize(fetchSize);
            }
            return new NamedCallableStatement(ps, s.names());
        } catch (RuntimeException | SQLException e) {
            if (ps != null) {
                ps.close();
            }
            throw e;
        }
    }

    static NamedPreparedStatement prepareReturnGeneratedKeys(Connection con, String sql) throws SQLException {
        SqlInfo s = SqlInfo.parse(sql);
        return new NamedPreparedStatement(con.prepareStatement(s.sql(), Statement.RETURN_GENERATED_KEYS), s.names());
    }

    @VisibleForTesting
    static int countQuestionMarkParameters(String sql) {
        // was originally using regular expressions, but they didn't work well
        // for ignoring parameter-like strings inside quotes.
        int count = 0;
        int length = sql.length();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < length; i++) {
            char c = sql.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                }
            } else {
                if (c == '\'') {
                    inSingleQuote = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                } else if (c == '?') {
                    count++;
                }
            }
        }
        return count;
    }

    public static void commit(PreparedStatement ps) throws SQLException {
        Connection c = ps.getConnection();
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }

    public static void rollback(PreparedStatement ps) throws SQLException {
        Connection c = ps.getConnection();
        if (!c.getAutoCommit()) {
            c.rollback();
        }
    }

    // auto-mapping

    /**
     * Converts from java.sql Types to common java types like java.util.Date and
     * numeric types.
     * 
     * @param o
     *            object to map to new object of type {@code cls}
     * @param cls
     *            object return type
     * @return object of type {@code cls}
     */
    public static Object autoMap(Object o, Class<?> cls) {
        if (o == null)
            return o;
        else if (cls.isAssignableFrom(o.getClass())) {
            return o;
        } else {
            if (o instanceof java.sql.Date) {
                java.sql.Date d = (java.sql.Date) o;
                if (cls.isAssignableFrom(Long.class))
                    return d.getTime();
                else if (cls.isAssignableFrom(BigInteger.class))
                    return BigInteger.valueOf(d.getTime());
                else if (cls.isAssignableFrom(Instant.class))
                    return Instant.ofEpochMilli(d.getTime());
                else
                    return o;
            } else if (o instanceof Timestamp) {
                Timestamp t = (Timestamp) o;
                if (cls.isAssignableFrom(Long.class))
                    return t.getTime();
                else if (cls.isAssignableFrom(BigInteger.class))
                    return BigInteger.valueOf(t.getTime());
                else if (cls.isAssignableFrom(Instant.class))
                    return t.toInstant();
                else
                    return o;
            } else if (o instanceof Time) {
                Time t = (Time) o;
                if (cls.isAssignableFrom(Long.class))
                    return t.getTime();
                else if (cls.isAssignableFrom(BigInteger.class))
                    return BigInteger.valueOf(t.getTime());
                else if (cls.isAssignableFrom(Instant.class))
                    return t.toInstant();
                else
                    return o;
            } else if (o instanceof Blob && cls.isAssignableFrom(byte[].class)) {
                return toBytes((Blob) o);
            } else if (o instanceof Clob && cls.isAssignableFrom(String.class)) {
                return toString((Clob) o);
            } else if (o instanceof BigInteger && cls.isAssignableFrom(Long.class)) {
                return ((BigInteger) o).longValue();
            } else if (o instanceof BigInteger && cls.isAssignableFrom(Integer.class)) {
                return ((BigInteger) o).intValue();
            } else if (o instanceof BigInteger && cls.isAssignableFrom(Double.class)) {
                return ((BigInteger) o).doubleValue();
            } else if (o instanceof BigInteger && cls.isAssignableFrom(Float.class)) {
                return ((BigInteger) o).floatValue();
            } else if (o instanceof BigInteger && cls.isAssignableFrom(Short.class)) {
                return ((BigInteger) o).shortValue();
            } else if (o instanceof BigInteger && cls.isAssignableFrom(BigDecimal.class)) {
                return new BigDecimal((BigInteger) o);
            } else if (o instanceof BigDecimal && cls.isAssignableFrom(Double.class)) {
                return ((BigDecimal) o).doubleValue();
            } else if (o instanceof BigDecimal && cls.isAssignableFrom(Integer.class)) {
                return ((BigDecimal) o).toBigInteger().intValue();
            } else if (o instanceof BigDecimal && cls.isAssignableFrom(Float.class)) {
                return ((BigDecimal) o).floatValue();
            } else if (o instanceof BigDecimal && cls.isAssignableFrom(Short.class)) {
                return ((BigDecimal) o).toBigInteger().shortValue();
            } else if (o instanceof BigDecimal && cls.isAssignableFrom(Long.class)) {
                return ((BigDecimal) o).toBigInteger().longValue();
            } else if (o instanceof BigDecimal && cls.isAssignableFrom(BigInteger.class)) {
                return ((BigDecimal) o).toBigInteger();
            } else if ((o instanceof Short || o instanceof Integer || o instanceof Long)
                    && cls.isAssignableFrom(BigInteger.class)) {
                return new BigInteger(o.toString());
            } else if (o instanceof Number && cls.isAssignableFrom(BigDecimal.class)) {
                return new BigDecimal(o.toString());
            } else if (o instanceof Number && cls.isAssignableFrom(Short.class))
                return ((Number) o).shortValue();
            else if (o instanceof Number && cls.isAssignableFrom(Integer.class))
                return ((Number) o).intValue();
            else if (o instanceof Number && cls.isAssignableFrom(Integer.class))
                return ((Number) o).intValue();
            else if (o instanceof Number && cls.isAssignableFrom(Long.class))
                return ((Number) o).longValue();
            else if (o instanceof Number && cls.isAssignableFrom(Float.class))
                return ((Number) o).floatValue();
            else if (o instanceof Number && cls.isAssignableFrom(Double.class))
                return ((Number) o).doubleValue();
            else
                return o;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T mapObject(final ResultSet rs, Class<T> cls, int i) {
        return (T) autoMap(getObject(rs, cls, i), cls);
    }

    @SuppressWarnings("unchecked")
    public static <T> T mapObject(final CallableStatement cs, Class<T> cls, int i, Type type) {
        return (T) autoMap(getObject(cs, cls, i, type), cls);
    }

    private static <T> Object getObject(final ResultSet rs, Class<T> cls, int i) {
        try {
            int colCount = rs.getMetaData().getColumnCount();
            if (i > colCount) {
                throw new MoreColumnsRequestedThanExistException(
                        "only " + colCount + " columns exist in ResultSet and column " + i + " was requested");
            }
            if (rs.getObject(i) == null) {
                return null;
            }
            final int type = rs.getMetaData().getColumnType(i);
            // TODO java.util.Calendar support
            // TODO XMLGregorian Calendar support
            if (type == Types.DATE)
                return rs.getDate(i, Calendar.getInstance());
            else if (type == Types.TIME)
                return rs.getTime(i, Calendar.getInstance());
            else if (type == Types.TIMESTAMP)
                return rs.getTimestamp(i, Calendar.getInstance());
            else if (type == Types.CLOB && cls.equals(String.class)) {
                return toString(rs.getClob(i));
            } else if (type == Types.CLOB && Reader.class.isAssignableFrom(cls)) {
                Clob c = rs.getClob(i);
                Reader r = c.getCharacterStream();
                return createFreeOnCloseReader(c, r);
            } else if (type == Types.BLOB && cls.equals(byte[].class)) {
                return toBytes(rs.getBlob(i));
            } else if (type == Types.BLOB && InputStream.class.isAssignableFrom(cls)) {
                final Blob b = rs.getBlob(i);
                final InputStream is = rs.getBlob(i).getBinaryStream();
                return createFreeOnCloseInputStream(b, is);
            } else
                return rs.getObject(i);
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    private static <T> Object getObject(final CallableStatement cs, Class<T> cls, int i, Type typ) {
        try {
            if (cs.getObject(i) == null) {
                return null;
            }
            final int type = typ.value();
            // TODO java.util.Calendar support
            // TODO XMLGregorian Calendar support
            if (type == Types.DATE)
                return cs.getDate(i, Calendar.getInstance());
            else if (type == Types.TIME)
                return cs.getTime(i, Calendar.getInstance());
            else if (type == Types.TIMESTAMP)
                return cs.getTimestamp(i, Calendar.getInstance());
            else if (type == Types.CLOB && cls.equals(String.class)) {
                return toString(cs.getClob(i));
            } else if (type == Types.CLOB && Reader.class.isAssignableFrom(cls)) {
                Clob c = cs.getClob(i);
                Reader r = c.getCharacterStream();
                return createFreeOnCloseReader(c, r);
            } else if (type == Types.BLOB && cls.equals(byte[].class)) {
                return toBytes(cs.getBlob(i));
            } else if (type == Types.BLOB && InputStream.class.isAssignableFrom(cls)) {
                final Blob b = cs.getBlob(i);
                final InputStream is = cs.getBlob(i).getBinaryStream();
                return createFreeOnCloseInputStream(b, is);
            } else
                return cs.getObject(i);
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    /**
     * Returns the bytes of a {@link Blob} and frees the blob resource.
     * 
     * @param b
     *            blob
     * @return
     */
    @VisibleForTesting
    static byte[] toBytes(Blob b) {
        try {
            InputStream is = b.getBinaryStream();
            byte[] result = IOUtils.toByteArray(is);
            is.close();
            b.free();
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }

    }

    /**
     * Returns the String of a {@link Clob} and frees the clob resource.
     * 
     * @param c
     * @return
     */
    @VisibleForTesting
    static String toString(Clob c) {
        try {
            Reader reader = c.getCharacterStream();
            String result = IOUtils.toString(reader);
            reader.close();
            c.free();
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    /**
     * Automatically frees the blob (<code>blob.free()</code>) once the blob
     * {@link InputStream} is closed.
     * 
     * @param blob
     * @param is
     * @return
     */
    private static InputStream createFreeOnCloseInputStream(final Blob blob, final InputStream is) {
        return new InputStream() {

            @Override
            public int read() throws IOException {
                return is.read();
            }

            @Override
            public void close() throws IOException {
                try {
                    is.close();
                } finally {
                    try {
                        blob.free();
                    } catch (SQLException e) {
                        log.debug(e.getMessage());
                    }
                }
            }
        };
    }

    /**
     * Automatically frees the clob (<code>Clob.free()</code>) once the clob Reader
     * is closed.
     * 
     * @param clob
     * @param reader
     * @return
     */
    private static Reader createFreeOnCloseReader(final Clob clob, final Reader reader) {
        return new Reader() {

            @Override
            public void close() throws IOException {
                try {
                    reader.close();
                } finally {
                    try {
                        clob.free();
                    } catch (SQLException e) {
                        log.debug(e.getMessage());
                    }
                }
            }

            @Override
            public int read(char[] cbuf, int off, int len) throws IOException {
                return reader.read(cbuf, off, len);
            }
        };
    }

    /**
     * Returns a function that converts the ResultSet column values into parameters
     * to the constructor (with number of parameters equals the number of columns)
     * of type <code>cls</code> then returns an instance of type <code>cls</code>.
     * See {@link SelectBuilder#autoMap(Class)}.
     * 
     * @param cls
     * @return
     */
    static <T> ResultSetMapper<T> autoMap(Class<T> cls) {
        return new ResultSetMapper<T>() {

            ProxyService<T> proxyService;
            private ResultSet rs;

            @Override
            public T apply(ResultSet rs) {

                if (!cls.isInterface()) {
                    return autoMapClass(rs, cls);
                }

                // access to this method will be serialized
                // only create a new ProxyService when the ResultSet changes
                // for example with the second run of a PreparedStatement
                if (rs != this.rs) {
                    this.rs = rs;
                    proxyService = new ProxyService<T>(rs, cls);
                }

                return autoMap(rs, cls, proxyService);
            }
        };
    }

    private static <T> T autoMapClass(ResultSet rs, Class<T> cls) {
        try {
            int n = rs.getMetaData().getColumnCount();
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                if (n == c.getParameterTypes().length) {
                    return autoMap(rs, (Constructor<T>) c);
                }
            }
            throw new RuntimeException("constructor with number of parameters=" + n + "  not found in " + cls);
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    /**
     * Converts the ResultSet column values into parameters to the given
     * constructor (with number of parameters equals the number of columns) of
     * type <code>T</code> then returns an instance of type <code>T</code>.
     *
     * @param rs
     *            the result set row
     * @param c
     *            constructor to use for instantiation
     * @return automapped instance
     */
    private static <T> T autoMap(ResultSet rs, Constructor<T> c) {
        Class<?>[] types = c.getParameterTypes();
        List<Object> list = new ArrayList<Object>();
        for (int i = 0; i < types.length; i++) {
            list.add(autoMap(getObject(rs, types[i], i + 1), types[i]));
        }
        try {
            return newInstance(c, list);
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "problem with parameters=" + getTypeInfo(list) + ", rs types=" + getRowInfo(rs)
                            + ". Be sure not to use primitives in a constructor when calling autoMap().",
                    e);
        }
    }

    /**
     * Creates a new instance of type T, by invoking the provided constructor with the parameters
     * @param c
     *            constructor to use
     * @param parameters
     *            constructor parameters
     * @return new instance of type T
     */
    @SuppressWarnings("unchecked")
    private static <T> T newInstance(Constructor<?> c, List<Object> parameters) {
        try {
            return (T) c.newInstance(parameters.toArray());
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts the ResultSet column values into parameters to the constructor (with
     * number of parameters equals the number of columns) of type <code>T</code>
     * then returns an instance of type <code>T</code>. See See
     * {@link SelectBuilder#autoMap(Class)}.
     * 
     * @param rs
     * @param cls
     *            the class of the resultant instance
     * @param proxyService
     * @return an automapped instance
     */
    static <T> T autoMap(ResultSet rs, Class<T> cls, ProxyService<T> proxyService) {
        return proxyService.newInstance();
    }

    interface Col {
        Class<?> returnType();
    }

    static class NamedCol implements Col {
        final String name;
        private final Class<?> returnType;

        public NamedCol(String name, Class<?> returnType) {
            this.name = name;
            this.returnType = returnType;
        }

        @Override
        public Class<?> returnType() {
            return returnType;
        }
    }

    static class IndexedCol implements Col {
        final int index;
        private final Class<?> returnType;

        public IndexedCol(int index, Class<?> returnType) {
            this.index = index;
            this.returnType = returnType;
        }

        @Override
        public Class<?> returnType() {
            return returnType;
        }

    }

    private static class ProxyService<T> {

        private final Map<String, Integer> colIndexes;
        private final Map<String, Col> methodCols;
        private final Class<T> cls;
        private final ResultSet rs;

        public ProxyService(ResultSet rs, Class<T> cls) {
            this(rs, collectColIndexes(rs), getMethodCols(cls), cls);
        }

        public ProxyService(ResultSet rs, Map<String, Integer> colIndexes, Map<String, Col> methodCols, Class<T> cls) {
            this.rs = rs;
            this.colIndexes = colIndexes;
            this.methodCols = methodCols;
            this.cls = cls;
        }

        private Map<String, Object> values() {
            Map<String, Object> values = new HashMap<String, Object>();
            // calculate values for all the interface methods and put them in a
            // map
            for (Method m : cls.getMethods()) {
                String methodName = m.getName();
                Col column = methodCols.get(methodName);
                if (column != null) {
                    Integer index;
                    if (column instanceof NamedCol) {
                        String name = ((NamedCol) column).name;
                        index = colIndexes.get(name.toUpperCase());
                        if (index == null) {
                            throw new ColumnNotFoundException("query column names do not include '" + name
                                    + "' which is a named column in the automapped interface " + cls.getName());
                        }
                    } else {
                        IndexedCol col = ((IndexedCol) column);
                        index = col.index;
                        if (index < 1) {
                            throw new ColumnIndexOutOfRangeException(
                                    "value for Index annotation (on autoMapped interface " + cls.getName()
                                            + ") must be > 0");
                        } else {
                            int count = getColumnCount(rs);
                            if (index > count) {
                                throw new ColumnIndexOutOfRangeException("value " + index
                                        + " for Index annotation (on autoMapped interface " + cls.getName()
                                        + ") must be between 1 and the number of columns in the result set (" + count
                                        + ")");
                            }
                        }
                    }
                    Object value = autoMap(getObject(rs, column.returnType(), index), column.returnType());
                    values.put(methodName, value);
                }
            }
            if (values.isEmpty()) {
                throw new AnnotationsNotFoundException(
                        "Did you forget to add @Column or @Index annotations to " + cls.getName() + "?");
            }
            return values;
        }

        @SuppressWarnings("unchecked")
        public T newInstance() {
            return (T) Proxy.newProxyInstance(cls.getClassLoader(), new Class[] { cls },
                    new ProxyInstance<T>(cls, values()));
        }

    }

    private static int getColumnCount(ResultSet rs) {
        try {
            return rs.getMetaData().getColumnCount();
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    private static final class ProxyInstance<T> implements java.lang.reflect.InvocationHandler {

        private static boolean JAVA_9 = false;

        private static final String METHOD_TO_STRING = "toString";

        private final Class<T> cls;
        private final Map<String, Object> values;

        ProxyInstance(Class<T> cls, Map<String, Object> values) {
            this.cls = cls;
            this.values = values;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (METHOD_TO_STRING.equals(method.getName()) && isEmpty(args)) {
                return Util.toString(cls.getSimpleName(), values);
            } else if ("equals".equals(method.getName()) && args != null && args.length == 1) {
                if (args[0] == null) {
                    return false;
                } else if (args[0] instanceof Proxy) {
                    ProxyInstance<?> handler = (ProxyInstance<?>) Proxy.getInvocationHandler(args[0]);
                    if (!handler.cls.equals(cls)) {
                        // is a proxied object for a different interface!
                        return false;
                    } else {
                        return handler.values.equals(values);
                    }
                } else {
                    return false;
                }
            } else if (isHashCode(method, args)) {
                return values.hashCode();
            } else if (values.containsKey(method.getName()) && isEmpty(args)) {
                return values.get(method.getName());
            } else if (method.isDefault()) {
                final Class<?> declaringClass = method.getDeclaringClass();
                if (!Modifier.isPublic(declaringClass.getModifiers())) {
                    throw new AutomappedInterfaceInaccessibleException(
                            "An automapped interface must be public for you to call default methods on that interface");
                }
                // TODO java 9 support (fix IllegalAccessException)
                if (JAVA_9) {
                    MethodType methodType = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
                    return MethodHandles.lookup() //
                            .findSpecial( //
                                    declaringClass, //
                                    method.getName(), //
                                    methodType, //
                                    declaringClass) //
                            .bindTo(proxy) //
                            .invoke(args);
                } else {
                    Constructor<MethodHandles.Lookup> constructor = //
                            MethodHandles.Lookup.class //
                                    .getDeclaredConstructor(Class.class, int.class);
                    constructor.setAccessible(true);
                    return constructor //
                            .newInstance(declaringClass, MethodHandles.Lookup.PRIVATE)
                            .unreflectSpecial(method, declaringClass) //
                            .bindTo(proxy) //
                            .invokeWithArguments(args);
                }
            } else {
                throw new RuntimeException("unexpected");
            }
        }

    }

    @VisibleForTesting
    static boolean isHashCode(Method method, Object[] args) {
        return "hashCode".equals(method.getName()) && isEmpty(args);
    }

    private static boolean isEmpty(Object[] args) {
        return args == null || args.length == 0;
    }

    private static String toString(String clsSimpleName, Map<String, Object> values) {
        StringBuilder s = new StringBuilder();
        s.append(clsSimpleName);
        s.append("[");
        boolean first = true;
        for (Entry<String, Object> entry : new TreeMap<String, Object>(values).entrySet()) {
            if (!first) {
                s.append(", ");
            }
            s.append(entry.getKey());
            s.append("=");
            s.append(entry.getValue());
            first = false;
        }
        s.append("]");
        return s.toString();
    }

    private static Map<String, Col> getMethodCols(Class<?> cls) {
        Map<String, Col> methodCols = new HashMap<String, Col>();
        for (Method method : cls.getMethods()) {
            String name = method.getName();
            Column column = method.getAnnotation(Column.class);
            if (column != null) {
                checkHasNoParameters(method);
                // TODO check method has a mappable return type
                String col = column.value();
                if (col.equals(Column.NOT_SPECIFIED))
                    col = Util.camelCaseToUnderscore(name);
                methodCols.put(name, new NamedCol(col, method.getReturnType()));
            } else {
                Index index = method.getAnnotation(Index.class);
                if (index != null) {
                    // TODO check method has a mappable return type
                    checkHasNoParameters(method);
                    methodCols.put(name, new IndexedCol(index.value(), method.getReturnType()));
                }
            }
        }
        return methodCols;
    }

    private static void checkHasNoParameters(Method method) {
        if (method.getParameterTypes().length > 0) {
            throw new RuntimeException("mapped interface method cannot have parameters");
        }
    }

    private static Map<String, Integer> collectColIndexes(ResultSet rs) {
        HashMap<String, Integer> map = new HashMap<String, Integer>();
        try {
            ResultSetMetaData metadata = rs.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                map.put(metadata.getColumnName(i).toUpperCase(), i);
            }
            return map;
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    static String camelCaseToUnderscore(String camelCased) {
        // guava has best solution for this with CaseFormat class
        // but don't want to add dependency just for this method
        final @RegEx String regex = "([a-z])([A-Z]+)";
        final String replacement = "$1_$2";
        return camelCased.replaceAll(regex, replacement);
    }

    /**
     * Returns debugging info about the types of a list of objects.
     *
     * @param list
     * @return
     */
    private static String getTypeInfo(List<Object> list) {

        StringBuilder s = new StringBuilder();
        for (Object o : list) {
            if (s.length() > 0)
                s.append(", ");
            if (o == null)
                s.append("null");
            else {
                s.append(o.getClass().getName());
                s.append("=");
                s.append(o);
            }
        }
        return s.toString();
    }

    private static String getRowInfo(ResultSet rs) {
        StringBuilder s = new StringBuilder();
        try {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String name = md.getColumnName(i);
                String type = md.getColumnClassName(i);
                if (s.length() > 0)
                    s.append(", ");
                s.append(name);
                s.append("=");
                s.append(type);
            }
        } catch (SQLException e1) {
            throw new SQLRuntimeException(e1);
        }
        return s.toString();
    }

    public static ConnectionProvider connectionProvider(String url, Properties properties) {
        return new ConnectionProvider() {

            @Override
            public Connection get() {
                try {
                    return DriverManager.getConnection(url, properties);
                } catch (SQLException e) {
                    throw new SQLRuntimeException(e);
                }
            }

            @Override
            public void close() {
                //
            }
        };
    }

    static Connection toTransactedConnection(AtomicReference<Connection> connection, Connection c) throws SQLException {
        if (c instanceof TransactedConnection) {
            connection.set(c);
            return c;
        } else {
            c.setAutoCommit(false);
            log.debug("creating new TransactedConnection");
            TransactedConnection c2 = new TransactedConnection(c);
            connection.set(c2);
            return c2;
        }
    }

    public static ConnectionProvider connectionProvider(DataSource dataSource) {
        return new ConnectionProvider() {

            @Override
            public Connection get() {
                return getConnection(dataSource);
            }

            @Override
            public void close() {
                // do nothing
            }
        };
    }

    @VisibleForTesting
    static Connection getConnection(DataSource ds) {
        try {
            return ds.getConnection();
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    public static boolean hasCollection(List<Parameter> params) {
        return params.stream().anyMatch(x -> x.isCollection());
    }

}
