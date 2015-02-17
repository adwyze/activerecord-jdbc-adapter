/*
 * The MIT License
 *
 * Copyright 2015 Karol Bucek.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package arjdbc.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.jruby.RubyFloat;

import org.jruby.RubyHash;
import org.jruby.RubyString;
import org.jruby.javasupport.JavaUtil;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

import org.postgresql.PGConnection;
import org.postgresql.PGStatement;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc4.Jdbc4Array;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGobject;

/**
 * Official JDBC driver internals.
 *
 * @author kares
 */
final class PGDriverImplementation implements DriverImplementation {

    public Connection newConnection(final Connection connection) throws SQLException {
        final PGConnection pgConnection;
        if ( connection instanceof PGConnection ) {
            pgConnection = (PGConnection) connection;
        }
        else {
            pgConnection = connection.unwrap(PGConnection.class);
        }
        pgConnection.addDataType("daterange", DateRangeType.class);
        pgConnection.addDataType("tsrange",   TsRangeType.class);
        pgConnection.addDataType("tstzrange", TstzRangeType.class);
        pgConnection.addDataType("int4range", Int4RangeType.class);
        pgConnection.addDataType("int8range", Int8RangeType.class);
        pgConnection.addDataType("numrange",  NumRangeType.class);
        return connection;
    }

    public IRubyObject objectToRuby(final ThreadContext context,
        final ResultSet resultSet, final int column) throws SQLException {

        final Object object = resultSet.getObject(column);

        if ( object == null ) return context.nil;

        final Class<?> objectClass = object.getClass();
        if ( objectClass == UUID.class ) {
            return RubyString.newString( context.runtime, object.toString() );
        }

        if ( objectClass == PGInterval.class ) {
            return RubyString.newString( context.runtime, formatInterval(object) );
        }

        if ( object instanceof PGobject ) {
            // PG 9.2 JSON type will be returned here as well
            return RubyString.newString( context.runtime, object.toString() );
        }

        if ( object instanceof Map ) { // hstore
            if ( PostgreSQLRubyJdbcConnection.rawHstoreType == Boolean.TRUE ) {
                return RubyString.newString( context.runtime, resultSet.getString(column) );
            }
            // by default we avoid double parsing by driver and than column :
            final RubyHash rubyObject = RubyHash.newHash(context.runtime);
            rubyObject.putAll((Map) object); // converts keys/values to ruby
            return rubyObject;
        }

        return JavaUtil.convertJavaToRuby(context.runtime, object);
    }

    // NOTE: do not use PG classes in the API so that loading is delayed ! still?
    static String formatInterval(final Object object) {
        final PGInterval interval = (PGInterval) object;
        if ( PostgreSQLRubyJdbcConnection.rawIntervalType ) return interval.getValue();

        final StringBuilder str = new StringBuilder(32);

        final int years = interval.getYears();
        if ( years != 0 ) str.append(years).append(" years ");
        final int months = interval.getMonths();
        if ( months != 0 ) str.append(months).append(" months ");
        final int days = interval.getDays();
        if ( days != 0 ) str.append(days).append(" days ");
        final int hours = interval.getHours();
        final int mins = interval.getMinutes();
        final int secs = (int) interval.getSeconds();
        if ( hours != 0 || mins != 0 || secs != 0 ) { // xx:yy:zz if not all 00
            if ( hours < 10 ) str.append('0');
            str.append(hours).append(':');
            if ( mins < 10 ) str.append('0');
            str.append(mins).append(':');
            if ( secs < 10 ) str.append('0');
            str.append(secs);
        }
        else {
            if ( str.length() > 1 ) str.deleteCharAt( str.length() - 1 ); // " " at the end
        }

        return str.toString();
    }

    public boolean setStringParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject column, final int type) throws SQLException {
        final RubyString sqlType;
        if ( column != null && ! column.isNil() ) {
            sqlType = (RubyString) column.callMethod(context, "sql_type");
        }
        else {
            sqlType = null;
        }

        if ( value.isNil() ) {
            if ( PostgreSQLRubyJdbcConnection.rawArrayType == Boolean.TRUE ) { // array's type is :string
                if ( sqlType != null && arrayLike(sqlType) ) {
                    statement.setNull(index, Types.ARRAY); return true;
                }
                statement.setNull(index, type); return true;
            }
            statement.setNull(index, Types.VARCHAR);
        }
        else {
            final String valueStr = value.asString().toString();
            if ( sqlType != null ) {
                if ( PostgreSQLRubyJdbcConnection.rawArrayType == Boolean.TRUE && arrayLike(sqlType) ) {
                    final int oid = PostgreSQLRubyJdbcConnection.oid(context, column);
                    Jdbc4Array valueArr = new Jdbc4Array(connection.unwrap(BaseConnection.class), oid, valueStr);
                    statement.setArray(index, valueArr); return true;
                }
                if ( sqlType.getByteList().startsWith( INTERVAL ) ) {
                    statement.setObject( index, new PGInterval( valueStr ) ); return true;
                }
            }
            statement.setString( index, valueStr );
        }

        return true;
    }

    private static final ByteList INTERVAL =
        new ByteList( new byte[] { 'i','n','t','e','r','v','a','l' }, false );

    private static final ByteList ARRAY_END = new ByteList( new byte[] { '[',']' }, false );

    private static boolean arrayLike(final RubyString sqlType) {
        return sqlType.getByteList().endsWith( ARRAY_END );
    }

    // to handle infinity timestamp values
    public boolean setTimestampParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject column, final int type) throws SQLException {

        if ( value instanceof RubyFloat ) {
            final double doubleValue = ( (RubyFloat) value ).getValue();
            if ( Double.isInfinite(doubleValue) ) {
                final Timestamp timestamp;
                if ( doubleValue < 0 ) {
                    timestamp = new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
                }
                else {
                    timestamp = new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
                }
                statement.setTimestamp( index, timestamp );
                return true;
            }
        }

        return false;
    }

    public boolean setObjectParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, Object value,
        final IRubyObject column, final int type) throws SQLException {

        final String columnType = column.callMethod(context, "type").asJavaString();

        if ( columnType == (Object) "uuid" ) {
            setUUIDParameter(statement, index, value);
            return true;
        }

        if ( columnType == (Object) "json" ) {
            setJsonParameter(context, statement, index, value, column);
            return true;
        }

        if ( columnType == (Object) "tsvector" ) {
            setTsVectorParameter(statement, index, value);
            return true;
        }

        if ( columnType == (Object) "cidr" || columnType == (Object) "inet"
                || columnType == (Object) "macaddr" ) {
            setAddressParameter(context, statement, index, value, column, columnType);
            return true;
        }

        if ( columnType != null && columnType.endsWith("range") ) {
            setRangeParameter(context, statement, index, value, column, columnType);
            return true;
        }

        return false;
    }

    static void setUUIDParameter(
        final PreparedStatement statement, final int index,
        Object value) throws SQLException {

        if ( value instanceof IRubyObject ) {
            final IRubyObject rubyValue = (IRubyObject) value;
            if ( rubyValue.isNil() ) {
                statement.setNull(index, Types.OTHER); return;
            }
        }
        else if ( value == null ) {
            statement.setNull(index, Types.OTHER); return;
        }

        final Object uuid = UUID.fromString( value.toString() );
        statement.setObject(index, uuid);
    }

    static void setAddressParameter(final ThreadContext context,
        final PreparedStatement statement, final int index,
        Object value, final IRubyObject column,
        final String columnType) throws SQLException {

        if ( value instanceof IRubyObject ) {
            final IRubyObject rubyValue = (IRubyObject) value;
            if ( rubyValue.isNil() ) {
                statement.setNull(index, Types.OTHER); return;
            }
            value = column.getMetaClass().callMethod(context, "cidr_to_string", rubyValue);
        }
        else if ( value == null ) {
            statement.setNull(index, Types.OTHER); return;
        }

        final PGobject pgAddress = new PGobject();
        pgAddress.setType(columnType);
        pgAddress.setValue(value.toString());
        statement.setObject(index, pgAddress);
    }

    static void setJsonParameter(final ThreadContext context,
        final PreparedStatement statement, final int index,
        Object value, final IRubyObject column) throws SQLException {

        if ( value instanceof IRubyObject ) {
            final IRubyObject rubyValue = (IRubyObject) value;
            if ( rubyValue.isNil() ) {
                statement.setNull(index, Types.OTHER); return;
            }
            value = column.getMetaClass().callMethod(context, "json_to_string", rubyValue);
        }
        else if ( value == null ) {
            statement.setNull(index, Types.OTHER); return;
        }

        final PGobject pgJson = new PGobject();
        pgJson.setType("json");
        pgJson.setValue(value.toString());
        statement.setObject(index, pgJson);
    }

    static void setTsVectorParameter(
        final PreparedStatement statement, final int index,
        Object value) throws SQLException {

        if ( value instanceof IRubyObject ) {
            final IRubyObject rubyValue = (IRubyObject) value;
            if ( rubyValue.isNil() ) {
                statement.setNull(index, Types.OTHER); return;
            }
        }
        else if ( value == null ) {
            statement.setNull(index, Types.OTHER); return;
        }

        final PGobject pgTsVector = new PGobject();
        pgTsVector.setType("tsvector");
        pgTsVector.setValue(value.toString());
        statement.setObject(index, pgTsVector);
    }

    static void setRangeParameter(final ThreadContext context,
        final PreparedStatement statement, final int index,
        final Object value, final IRubyObject column,
        final String columnType) throws SQLException {

        final String rangeValue;

        if ( value instanceof IRubyObject ) {
            final IRubyObject rubyValue = (IRubyObject) value;
            if ( rubyValue.isNil() ) {
                statement.setNull(index, Types.OTHER); return;
            }
            rangeValue = column.getMetaClass().callMethod(context, "range_to_string", rubyValue).toString();
        }
        else {
            if ( value == null ) {
                statement.setNull(index, Types.OTHER); return;
            }
            rangeValue = value.toString();
        }

        final Object pgRange;
        if ( columnType == (Object) "daterange" ) {
            pgRange = new DateRangeType(rangeValue);
        }
        else if ( columnType == (Object) "tsrange" ) {
            pgRange = new TsRangeType(rangeValue);
        }
        else if ( columnType == (Object) "tstzrange" ) {
            pgRange = new TstzRangeType(rangeValue);
        }
        else if ( columnType == (Object) "int4range" ) {
            pgRange = new Int4RangeType(rangeValue);
        }
        else if ( columnType == (Object) "int8range" ) {
            pgRange = new Int8RangeType(rangeValue);
        }
        else { // if ( columnType == (Object) "numrange" )
            pgRange = new NumRangeType(rangeValue);
        }
        statement.setObject(index, pgRange);
    }

    // NOTE: without these custom registered Postgre (driver) types
    // ... we can not set range parameters in prepared statements !

    public static final class DateRangeType extends PGobject {

        private static final long serialVersionUID = -5378414736244196691L;

        public DateRangeType() {
            setType("daterange");
        }

        public DateRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static final class TsRangeType extends PGobject {

        private static final long serialVersionUID = -2991390995527988409L;

        public TsRangeType() {
            setType("tsrange");
        }

        public TsRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static final class TstzRangeType extends PGobject {

        private static final long serialVersionUID = 6492535255861743334L;

        public TstzRangeType() {
            setType("tstzrange");
        }

        public TstzRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static final class Int4RangeType extends PGobject {

        private static final long serialVersionUID = 4490562039665289763L;

        public Int4RangeType() {
            setType("int4range");
        }

        public Int4RangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static final class Int8RangeType extends PGobject {

        private static final long serialVersionUID = -1458706215346897102L;

        public Int8RangeType() {
            setType("int8range");
        }

        public Int8RangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static final class NumRangeType extends PGobject {

        private static final long serialVersionUID = 5892509252900362510L;

        public NumRangeType() {
            setType("numrange");
        }

        public NumRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

}
