package org.davidmoten.rx.jdbc;

import static org.davidmoten.rx.jdbc.fixtures.Fixtures.listOf;
import static org.davidmoten.rx.jdbc.fixtures.Fixtures.mockPersonResultSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.sql.*;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.davidmoten.rx.jdbc.exceptions.SQLRuntimeException;
import org.davidmoten.rx.jdbc.fixtures.Person;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.mockito.Mockito;

import com.github.davidmoten.guavamini.Lists;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class UtilTest {

    @Test
    public void testIsHashCode() throws NoSuchMethodException, SecurityException {
        Method method = Object.class.getMethod("hashCode");
        assertTrue(Util.isHashCode(method, new Object[] {}));
    }

    @Test
    public void testNotHashCodeIfHasArgs() throws NoSuchMethodException, SecurityException {
        Method method = Object.class.getMethod("hashCode");
        assertFalse(Util.isHashCode(method, new Object[] { 12 }));
    }

    @Test
    public void testNotHashCodeIfMethodNameWrong() throws NoSuchMethodException, SecurityException {
        Method method = Object.class.getMethod("equals", Object.class);
        assertFalse(Util.isHashCode(method, new Object[] {}));
    }

    @Test
    public void testDoubleQuote() {
        String sql = "select \"FRED\" from tbl where name=?";
        assertEquals(1, Util.countQuestionMarkParameters(sql));
    }

    @Test
    public void testAutomapDateToLong() {
        assertEquals(100L, (long) Util.autoMap(new java.sql.Date(100), Long.class));
    }

    @Test
    public void testAutomapDateToBigInteger() {
        assertEquals(100L, ((BigInteger) Util.autoMap(new java.sql.Date(100), BigInteger.class)).longValue());
    }

    @Test
    public void testAutomapDateToInstant() {
        assertEquals(100L, ((Instant) Util.autoMap(new java.sql.Date(100), Instant.class)).toEpochMilli());
    }

    @Test
    public void testAutomapDateToString() {
        assertEquals(100L, ((java.sql.Date) Util.autoMap(new java.sql.Date(100), String.class)).getTime());
    }

    @Test(expected = RuntimeException.class)
    public void testClassAutoMap() throws SQLException {
        ResultSetMapper<Person> mapper = Util.autoMap(Person.class);

        ResultSet mockedResultSet = mockPersonResultSet(listOf("John", "Doe"));
        Person mappedPerson = mapper.apply(mockedResultSet);

        assertEquals(mappedPerson, new Person("John", "Doe"));

        mockedResultSet = mockPersonResultSet(listOf("John", null));
        mappedPerson = mapper.apply(mockedResultSet);

        assertEquals(mappedPerson, new Person("John", null));

        mockedResultSet = mockPersonResultSet(listOf("John", "Doe", "Test"));
        mapper.apply(mockedResultSet);
    }

    @Test
    public void testConnectionProviderFromDataSource() throws SQLException {
        DataSource d = mock(DataSource.class);
        Connection c = mock(Connection.class);
        ConnectionProvider cp = Util.connectionProvider(d);
        when(d.getConnection()).thenReturn(c);
        assertTrue(c == cp.get());
        cp.close();
    }

    @Test(expected = SQLRuntimeException.class)
    public void testGetConnectionFromDataSourceWhenThrows() throws SQLException {
        DataSource d = mock(DataSource.class);
        when(d.getConnection()).thenThrow(SQLException.class);
        Util.getConnection(d);
    }

    @Test(expected = SQLException.class)
    public void testPsThrowsInSetParameters() throws SQLException {
        boolean namesAllowed = true;
        List<Parameter> list = Lists.newArrayList(Parameter.create("name", "FRED"));
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        try {
            Mockito.doThrow(new SQLException("boo")) //
                    .when(ps) //
                    .setObject(Mockito.anyInt(), Mockito.any());
            Util.setParameters(ps, list, namesAllowed);
        } finally {
            Mockito.verify(ps, Mockito.atMost(1)).setObject(Mockito.anyInt(), Mockito.any());
        }
    }

    @Test(expected = RuntimeException.class)
    public void testToBytesThrowsIOException() throws IOException, SQLException {
        Blob blob = Mockito.mock(Blob.class);
        InputStream is = Mockito.mock(InputStream.class);
        Mockito.when(blob.getBinaryStream()).thenReturn(is);
        Mockito.when(is.read()).thenThrow(IOException.class);
        Mockito.when(is.read(Mockito.any())).thenThrow(IOException.class);
        Mockito.when(is.read(Mockito.any(), Mockito.anyInt(), Mockito.anyInt())).thenThrow(IOException.class);
        Util.toBytes(blob);
    }

    @Test(expected = SQLRuntimeException.class)
    public void testToBytesThrowsSqlException() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        Mockito.when(blob.getBinaryStream()) //
                .thenThrow(new SQLException("boo"));
        Util.toBytes(blob);
    }

    @Test(expected = RuntimeException.class)
    public void testToStringThrowsIOException() throws IOException, SQLException {
        Clob clob = Mockito.mock(Clob.class);
        Reader r = Mockito.mock(Reader.class);
        Mockito.when(clob.getCharacterStream()).thenReturn(r);
        Mockito.when(r.read()).thenThrow(IOException.class);
        Mockito.when(r.read((char[]) Mockito.any())).thenThrow(IOException.class);
        Mockito.when(r.read(Mockito.any(), Mockito.anyInt(), Mockito.anyInt())).thenThrow(IOException.class);
        Util.toString(clob);
    }

    @Test(expected = SQLRuntimeException.class)
    public void testToStringThrowsSqlException() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        Mockito.when(clob.getCharacterStream()) //
                .thenThrow(new SQLException("boo"));
        Util.toString(clob);
    }
}
