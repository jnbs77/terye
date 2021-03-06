package ch.x42.terye.value;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.GregorianCalendar;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;

import org.apache.hadoop.hbase.util.Bytes;

public class ValueFactoryImpl implements ValueFactory {

    private ValueImpl createNewValue(Object value, int type) {
        try {
            return new ValueImpl(value, type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public Binary createBinary(InputStream stream) throws RepositoryException {
        try {
            return new BinaryImpl(stream);
        } catch (IOException e) {
            throw new RepositoryException("Couldn't create binary", e);
        }
    }

    @Override
    public Value createValue(BigDecimal value) {
        return createNewValue(value, PropertyType.DECIMAL);
    }

    @Override
    public Value createValue(Binary value) {
        return createNewValue(value, PropertyType.BINARY);
    }

    @Override
    public Value createValue(boolean value) {
        return createNewValue(value, PropertyType.BOOLEAN);
    }

    @Override
    public Value createValue(Calendar value) {
        return createNewValue((Long) value.getTime().getTime(),
                PropertyType.DATE);
    }

    @Override
    public Value createValue(double value) {
        return createNewValue(value, PropertyType.DOUBLE);
    }

    @Override
    public Value createValue(InputStream value) {
        try {
            return createNewValue(createBinary(value), PropertyType.BINARY);
        } catch (RepositoryException e) {
            throw new RuntimeException("Couldn't create binary value", e);
        }
    }

    @Override
    public Value createValue(long value) {
        return createNewValue(value, PropertyType.LONG);
    }

    @Override
    public Value createValue(Node value, boolean weak)
            throws RepositoryException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Value createValue(Node value) throws RepositoryException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Value createValue(String value, int type)
            throws ValueFormatException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Value createValue(String value) {
        return createNewValue(value, PropertyType.STRING);
    }

    public ValueImpl createValue(int type, byte[] bytes) {
        Value value = null;
        switch (type) {
            case PropertyType.BINARY:
                value = createValue(new BinaryImpl(bytes));
                break;
            case PropertyType.BOOLEAN:
                value = createValue(Bytes.toBoolean(bytes));
                break;
            case PropertyType.DATE:
                Calendar calendar = new GregorianCalendar();
                calendar.setTimeInMillis(Bytes.toLong(bytes));
                value = createValue(calendar);
                break;
            case PropertyType.LONG:
                value = createValue(Bytes.toLong(bytes));
                break;
            case PropertyType.DECIMAL:
                value = createValue(Bytes.toBigDecimal(bytes));
                break;
            case PropertyType.DOUBLE:
                value = createValue(Bytes.toDouble(bytes));
                break;
            case PropertyType.STRING:
                value = createValue(Bytes.toString(bytes));
                break;
        }
        return (ValueImpl) value;
    }

}
