/*
 * Copyright 2009-2011 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.asterix.dataflow.data.nontagged.serde;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.AInterval;
import edu.uci.ics.asterix.om.base.AMutableInterval;
import edu.uci.ics.asterix.om.base.temporal.ADateAndTimeParser;
import edu.uci.ics.asterix.om.base.temporal.GregorianCalendarSystem;
import edu.uci.ics.asterix.om.base.temporal.StringCharSequenceAccessor;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;

public class AIntervalSerializerDeserializer implements ISerializerDeserializer<AInterval> {

    private static final long serialVersionUID = 1L;

    public static final AIntervalSerializerDeserializer INSTANCE = new AIntervalSerializerDeserializer();
    @SuppressWarnings("unchecked")
    private static final ISerializerDeserializer<AInterval> intervalSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.AINTERVAL);

    private static final String errorMessage = "This can not be an instance of interval";

    private AIntervalSerializerDeserializer() {
    }

    @Override
    public AInterval deserialize(DataInput in) throws HyracksDataException {
        try {
            return new AInterval(in.readLong(), in.readLong(), in.readByte());
        } catch (IOException e) {
            throw new HyracksDataException(e);
        }

    }

    @Override
    public void serialize(AInterval instance, DataOutput out) throws HyracksDataException {
        try {
            out.writeLong(instance.getIntervalStart());
            out.writeLong(instance.getIntervalEnd());
            out.writeByte(instance.getIntervalType());
        } catch (IOException e) {
            throw new HyracksDataException(e);
        }

    }

    public static long getIntervalStart(byte[] data, int offset) {
        return AInt64SerializerDeserializer.getLong(data, offset);
    }

    public static long getIntervalEnd(byte[] data, int offset) {
        return AInt64SerializerDeserializer.getLong(data, offset + 8);
    }

    public static byte getIntervalTimeType(byte[] data, int offset) {
        return data[offset + 8 * 2];
    }

    public static void parseDatetime(String interval, DataOutput out) throws HyracksDataException {
        AMutableInterval aInterval = new AMutableInterval(0l, 0l, (byte) 0);

        long chrononTimeInMsStart = 0;
        long chrononTimeInMsEnd = 0;
        try {

            StringCharSequenceAccessor charAccessor = new StringCharSequenceAccessor();

            //Interval Start
            charAccessor.reset(interval, 0);

            // +1 if it is negative (-)
            short timeOffset = (short) ((charAccessor.getCharAt(0) == '-') ? 1 : 0);

            if (charAccessor.getCharAt(timeOffset + 10) != 'T' && charAccessor.getCharAt(timeOffset + 8) != 'T') {
                throw new AlgebricksException(errorMessage + ": missing T");
            }

            // if extended form 11, else 9
            timeOffset += (charAccessor.getCharAt(timeOffset + 13) == ':') ? (short) (11) : (short) (9);

            chrononTimeInMsStart = ADateAndTimeParser.parseDatePart(charAccessor, false);

            charAccessor.reset(interval, timeOffset);

            chrononTimeInMsStart += ADateAndTimeParser.parseTimePart(charAccessor);

            //Interval End
            charAccessor.reset(interval, 0);

            // Find the comma in the expression
            int commaSkipIndex = 0;
            int length = charAccessor.getLength();
            for (; length > commaSkipIndex && charAccessor.getCharAt(commaSkipIndex) != ','; commaSkipIndex++) {
            }

            if (length <= commaSkipIndex) {
                throw new AlgebricksException(errorMessage + ": comma is missing");
            }
            commaSkipIndex++;

            // Skip any possible spaces (' ')
            for (; length > commaSkipIndex && charAccessor.getCharAt(commaSkipIndex) != ' '; commaSkipIndex++) {
            }

            if (length <= commaSkipIndex) {
                // TODO: Maybe remove this error and combine with the previous one
                throw new AlgebricksException(errorMessage + ": error while skipping spaces");
            }
            charAccessor.reset(interval, commaSkipIndex);

            // +1 if it is negative (-)
            timeOffset = (short) ((charAccessor.getCharAt(0) == '-') ? 1 : 0);

            if (charAccessor.getCharAt(timeOffset + 10) != 'T' && charAccessor.getCharAt(timeOffset + 8) != 'T') {
                throw new AlgebricksException(errorMessage + ": missing T");
            }

            // if extended form 11, else 9
            timeOffset += (charAccessor.getCharAt(timeOffset + 13) == ':') ? (short) (11) : (short) (9);

            chrononTimeInMsEnd = ADateAndTimeParser.parseDatePart(charAccessor, false);

            charAccessor.reset(interval, commaSkipIndex + timeOffset);

            chrononTimeInMsEnd += ADateAndTimeParser.parseTimePart(charAccessor);

        } catch (Exception e) {
            throw new HyracksDataException(e);
        }

        aInterval.setValue(chrononTimeInMsStart, chrononTimeInMsEnd, ATypeTag.DATETIME.serialize());

        intervalSerde.serialize(aInterval, out);
    }

    public static void parseTime(String interval, DataOutput out) throws HyracksDataException {
        AMutableInterval aInterval = new AMutableInterval(0l, 0l, (byte) 0);

        long chrononTimeInMsStart = 0;
        long chrononTimeInMsEnd = 0;
        try {

            StringCharSequenceAccessor charAccessor = new StringCharSequenceAccessor();

            //Interval Start
            charAccessor.reset(interval, 0);
            chrononTimeInMsStart = ADateAndTimeParser.parseTimePart(charAccessor);

            if (chrononTimeInMsStart < 0) {
                chrononTimeInMsStart += GregorianCalendarSystem.CHRONON_OF_DAY;
            }

            //Interval End
            int commaSkipIndex = 0;
            int length = charAccessor.getLength();
            for (; length > commaSkipIndex && charAccessor.getCharAt(commaSkipIndex) != ','; commaSkipIndex++) {
            }

            if (length <= commaSkipIndex) {
                throw new AlgebricksException(errorMessage + ": comma is missing");
            }
            commaSkipIndex++;

            // Skip any possible spaces (' ')
            for (; length > commaSkipIndex && charAccessor.getCharAt(commaSkipIndex) != ' '; commaSkipIndex++) {
            }

            if (length <= commaSkipIndex) {
                // TODO: Maybe remove this error and combine with the previous one
                throw new AlgebricksException(errorMessage + ": error while skipping spaces");
            }
            charAccessor.reset(interval, commaSkipIndex);
            chrononTimeInMsEnd = ADateAndTimeParser.parseTimePart(charAccessor);

            if (chrononTimeInMsEnd < 0) {
                chrononTimeInMsEnd += GregorianCalendarSystem.CHRONON_OF_DAY;
            }

        } catch (Exception e) {
            throw new HyracksDataException(e);
        }

        aInterval.setValue(chrononTimeInMsStart, chrononTimeInMsEnd, ATypeTag.TIME.serialize());
        intervalSerde.serialize(aInterval, out);
    }

    public static void parseDate(String interval, DataOutput out) throws HyracksDataException {
        AMutableInterval aInterval = new AMutableInterval(0l, 0l, (byte) 0);

        long chrononTimeInMsStart = 0;
        long chrononTimeInMsEnd = 0;
        short tempStart = 0;
        short tempEnd = 0;
        try {
            StringCharSequenceAccessor charAccessor = new StringCharSequenceAccessor();

            //Interval Start
            charAccessor.reset(interval, 0);

            chrononTimeInMsStart = ADateAndTimeParser.parseDatePart(charAccessor, true);

            if (chrononTimeInMsStart < 0 && chrononTimeInMsStart % GregorianCalendarSystem.CHRONON_OF_DAY != 0) {
                tempStart = 1;
            }

            //Interval End
            int commaSkipIndex = 0;
            int length = charAccessor.getLength();
            for (; length > commaSkipIndex && charAccessor.getCharAt(commaSkipIndex) != ','; commaSkipIndex++) {
            }

            if (length <= commaSkipIndex) {
                throw new AlgebricksException(errorMessage + ": comma is missing");
            }
            commaSkipIndex++;

            // Skip any possible spaces (' ')
            for (; length > commaSkipIndex && charAccessor.getCharAt(commaSkipIndex) != ' '; commaSkipIndex++) {
            }

            if (length <= commaSkipIndex) {
                // TODO: Maybe remove this error and combine with the previous one
                throw new AlgebricksException(errorMessage + ": error while skipping spaces");
            }
            charAccessor.reset(interval, commaSkipIndex);

            chrononTimeInMsEnd = ADateAndTimeParser.parseDatePart(charAccessor, true);

            if (chrononTimeInMsEnd < 0 && chrononTimeInMsEnd % GregorianCalendarSystem.CHRONON_OF_DAY != 0) {
                tempEnd = 1;
            }

        } catch (Exception e) {
            throw new HyracksDataException(e);
        }

        aInterval.setValue((chrononTimeInMsStart / GregorianCalendarSystem.CHRONON_OF_DAY) - tempStart,
                (chrononTimeInMsEnd / GregorianCalendarSystem.CHRONON_OF_DAY) - tempEnd, ATypeTag.DATE.serialize());

        intervalSerde.serialize(aInterval, out);
    }

}
