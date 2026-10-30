package com.team3.dataservice.collection.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.DataTypeException;
import org.dbunit.dataset.datatype.StringDataType;
import org.dbunit.dataset.datatype.TypeCastException;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;

/** DBUnit 2.x otherwise omits PostgreSQL jsonb columns from fixture metadata. */
public class CollectionPostgresDataTypeFactory extends PostgresqlDataTypeFactory {
    @Override
    public DataType createDataType(int sqlType, String sqlTypeName) throws DataTypeException {
        if ("jsonb".equals(sqlTypeName)) {
            return new StringDataType("jsonb", Types.OTHER) {
                @Override
                public void setSqlValue(Object value, int column, PreparedStatement statement)
                        throws SQLException, TypeCastException {
                    statement.setObject(column, typeCast(value), Types.OTHER);
                }
            };
        }
        return super.createDataType(sqlType, sqlTypeName);
    }
}
