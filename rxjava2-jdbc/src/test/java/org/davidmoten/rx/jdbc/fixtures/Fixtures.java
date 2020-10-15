package org.davidmoten.rx.jdbc.fixtures;

import com.github.davidmoten.guavamini.Lists;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class Fixtures {
    public static List<String> listOf(String ... params) {
        return Lists.newArrayList(params);
    }

    public static ResultSet mockPersonResultSet(List<String> parameterValues) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData rsMeta = mock(ResultSetMetaData.class);

        when(rsMeta.getColumnCount()).thenReturn(parameterValues.size());

        for(int i = 0; i < parameterValues.size(); i++) {
            when(rsMeta.getColumnType(i)).thenReturn(Types.VARCHAR);
        }

        when(rs.getMetaData()).thenReturn(rsMeta);

        for(int i = 0; i < parameterValues.size(); i++) {
            when(rs.getObject(i + 1)).thenReturn(parameterValues.get(i));
        }

        return rs;
    }
}
