package edu.uci.ics.asterix.runtime.evaluators.constructors;

import java.io.DataOutput;
import java.io.IOException;

import edu.uci.ics.asterix.common.functions.FunctionConstants;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.ADate;
import edu.uci.ics.asterix.om.base.AMutableDate;
import edu.uci.ics.asterix.om.base.ANull;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.base.IEvaluator;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.base.IEvaluatorFactory;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IDataOutputProvider;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class ADateConstructorDescriptor extends AbstractScalarFunctionDynamicDescriptor {

    private static final long serialVersionUID = 1L;
    public final static FunctionIdentifier FID = new FunctionIdentifier(FunctionConstants.ASTERIX_NS, "date", 1, false);
    private final static byte SER_STRING_TYPE_TAG = ATypeTag.STRING.serialize();
    private final static byte SER_NULL_TYPE_TAG = ATypeTag.NULL.serialize();

    @Override
    public IEvaluatorFactory createEvaluatorFactory(final IEvaluatorFactory[] args) {
        return new IEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public IEvaluator createEvaluator(final IDataOutputProvider output) throws AlgebricksException {
                return new IEvaluator() {

                    private DataOutput out = output.getDataOutput();

                    private ArrayBackedValueStorage outInput = new ArrayBackedValueStorage();
                    private IEvaluator eval = args[0].createEvaluator(outInput);
                    private int offset;
                    private short year, month, day, hour, minute, value;
                    private byte timezonePart = 0;
                    private boolean positive = true;
                    private String errorMessage = "This can not be an instance of date";
                    private AMutableDate aDate = new AMutableDate(0, 0, 0, 0);
                    @SuppressWarnings("unchecked")
                    private ISerializerDeserializer<ADate> dateSerde = AqlSerializerDeserializerProvider.INSTANCE
                            .getSerializerDeserializer(BuiltinType.ADATE);
                    @SuppressWarnings("unchecked")
                    private ISerializerDeserializer<ANull> nullSerde = AqlSerializerDeserializerProvider.INSTANCE
                            .getSerializerDeserializer(BuiltinType.ANULL);

                    @Override
                    public void evaluate(IFrameTupleReference tuple) throws AlgebricksException {

                        try {
                            outInput.reset();
                            eval.evaluate(tuple);
                            byte[] serString = outInput.getBytes();
                            if (serString[0] == SER_STRING_TYPE_TAG) {
                                offset = 3;

                                if (serString[offset] == '-') {
                                    offset++;
                                    positive = false;
                                }

                                if (serString[offset + 4] != '-' || serString[offset + 7] != '-')
                                    throw new AlgebricksException(errorMessage);

                                year = getValue(serString, offset, 4);
                                month = getValue(serString, offset + 5, 2);
                                day = getValue(serString, offset + 8, 2);

                                if (year < 0 || year > 9999 || month < 0 || month > 12 || day < 0 || day > 31)
                                    throw new AlgebricksException(errorMessage);

                                offset += 10;

                                if (outInput.getLength() > offset) {
                                    if (serString[offset] == 'Z')
                                        timezonePart = 0;
                                    else {
                                        if ((serString[offset] != '+' && serString[offset] != '-')
                                                || (serString[offset + 3] != ':'))
                                            throw new AlgebricksException(errorMessage);

                                        hour = getValue(serString, offset + 1, 2);
                                        minute = getValue(serString, offset + 4, 2);

                                        if (hour < 0 || hour > 24 || (hour == 24 && minute != 0)
                                                || (minute != 0 && minute != 15 && minute != 30 && minute != 45))
                                            throw new AlgebricksException(errorMessage);

                                        if (serString[offset] == '-')
                                            timezonePart = (byte) -((hour * 4) + minute / 15);
                                        else
                                            timezonePart = (byte) ((hour * 4) + minute / 15);
                                    }

                                }

                                if (!positive)
                                    year *= -1;

                                aDate.setValue(year, month, day, timezonePart);
                                dateSerde.serialize(aDate, out);
                            } else if (serString[0] == SER_NULL_TYPE_TAG)
                                nullSerde.serialize(ANull.NULL, out);
                            else
                                throw new AlgebricksException(errorMessage);
                        } catch (IOException e1) {
                            throw new AlgebricksException(errorMessage);
                        }
                    }

                    private short getValue(byte[] b, int offset, int numberOfDigits) throws AlgebricksException {
                        value = 0;
                        for (int i = 0; i < numberOfDigits; i++) {
                            if ((b[offset] >= '0' && b[offset] <= '9'))
                                value = (short) (value * 10 + b[offset++] - '0');
                            else
                                throw new AlgebricksException(errorMessage);

                        }
                        return value;
                    }
                };
            }
        };
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return FID;
    }

}