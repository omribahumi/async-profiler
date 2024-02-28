/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.StackTrace;
import one.jfr.event.ExecutionSample;
import one.proto.Proto;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Convert a JFR file to pprof
 * <p>
 * Protobuf definition: https://github.com/google/pprof/blob/44fc4e887b6b0cfb196973bcdb1fab95f0b3a75b/proto/profile.proto
 */
public class JfrToPprof extends JfrConverter {

    public static class Method {
        final byte[] name;

        public Method(final byte[] name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(name);
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Method && Arrays.equals(name, ((Method) other).name);
        }
    }

    public static final class Location {
        final Method method;
        final int line;

        public Location(final Method method, final int line) {
            this.method = method;
            this.line = line;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Location location = (Location) o;

            if (line != location.line) return false;
            return method.equals(location.method);
        }

        @Override
        public int hashCode() {
            int result = method.hashCode();
            result = 31 * result + line;
            return result;
        }
    }

    // Profile IDs
    public static final int PROFILE_SAMPLE_TYPE = 1;
    public static final int PROFILE_SAMPLE = 2;
    public static final int PROFILE_LOCATION = 4;
    public static final int PROFILE_FUNCTION = 5;
    public static final int PROFILE_STRING_TABLE = 6;
    public static final int PROFILE_TIME_NANOS = 9;
    public static final int PROFILE_DURATION_NANOS = 10;
    public static final int PROFILE_COMMENT = 13;
    public static final int PROFILE_DEFAULT_SAMPLE_TYPE = 14;

    // ValueType IDs
    public static final int VALUETYPE_TYPE = 1;
    public static final int VALUETYPE_UNIT = 2;

    // Sample IDs
    public static final int SAMPLE_LOCATION_ID = 1;
    public static final int SAMPLE_VALUE = 2;

    // Location IDs
    public static final int LOCATION_ID = 1;
    public static final int LOCATION_LINE = 4;

    // Line IDs
    public static final int LINE_FUNCTION_ID = 1;
    public static final int LINE_LINE = 2;

    // Function IDs
    public static final int FUNCTION_ID = 1;
    public static final int FUNCTION_NAME = 2;

    public JfrToPprof(JfrReader jfr, Arguments args) {
        super(jfr, args);
    }

    // `Proto` instances are mutable, careful with reordering
    public void dump(final OutputStream out) throws IOException {
        // Mutable IDs, need to start at 1
        int functionId = 1;
        int locationId = 1;
        int stringId = 1;

        // Used to de-dupe
        final Map<Method, Integer> functions = new HashMap<>();
        final Map<Location, Integer> locations = new HashMap<>();

        final Proto profile = new Proto(200_000)
                .field(PROFILE_TIME_NANOS, jfr.startNanos)
                .field(PROFILE_DURATION_NANOS, jfr.durationNanos())
                .field(PROFILE_DEFAULT_SAMPLE_TYPE, 0L)
                .field(PROFILE_STRING_TABLE, "") // "" needs to be index 0
                .field(PROFILE_STRING_TABLE, "async-profiler")
                .field(PROFILE_COMMENT, stringId++);

        final Proto sampleType = new Proto(100);

        profile.field(PROFILE_STRING_TABLE, "cpu");
        sampleType.field(VALUETYPE_TYPE, stringId++);

        profile.field(PROFILE_STRING_TABLE, "nanoseconds");
        sampleType.field(VALUETYPE_UNIT, stringId++);

        profile.field(PROFILE_SAMPLE_TYPE, sampleType);

        final Dictionary<StackTrace> stackTraces = jfr.stackTraces;
        long previousTime = jfr.startTicks; // Mutate this to keep track of time deltas

        // Iterate over samples
        for (ExecutionSample jfrSample; (jfrSample = jfr.readEvent(ExecutionSample.class)) != null; ) {
            final StackTrace stackTrace = stackTraces.get(jfrSample.stackTraceId);
            final long[] methods = stackTrace.methods;
            final byte[] types = stackTrace.types;

            final long nanosSinceLastSample = (jfrSample.time - previousTime) * 1_000_000_000 / jfr.ticksPerSec;
            final Proto sample = new Proto(1_000).field(SAMPLE_VALUE, nanosSinceLastSample);

            for (int current = 0; current < methods.length; current++) {
                final byte methodType = types[current];
                final long methodIdentifier = methods[current];
                final byte[] methodName = getMethodNameBytes(methodIdentifier, methodType);
                final Method method = new Method(methodName);
                final int line = stackTrace.locations[current] >>> 16;

                final Integer methodId = functions.get(method);
                if (null == methodId) {
                    final int funcId = functionId++;
                    profile.field(PROFILE_STRING_TABLE, methodName);
                    final Proto function = new Proto(16)
                            .field(FUNCTION_ID, funcId)
                            .field(FUNCTION_NAME, stringId++);

                    profile.field(PROFILE_FUNCTION, function);

                    functions.put(method, funcId);
                }
                final Location locKey = new Location(method, line);
                final Integer locaId = locations.get(locKey);
                if (null == locaId) {
                    final int locId = locationId++;
                    final Proto locLine = new Proto(16).field(LINE_FUNCTION_ID, functions.get(method));
                    if (line > 0) {
                        locLine.field(LINE_LINE, line);
                    }

                    final Proto location = new Proto(16)
                            .field(LOCATION_ID, locId)
                            .field(LOCATION_LINE, locLine);

                    profile.field(PROFILE_LOCATION, location);

                    locations.put(locKey, locId);
                }

                sample.field(SAMPLE_LOCATION_ID, locations.get(locKey));
            }

            profile.field(PROFILE_SAMPLE, sample);

            previousTime = jfrSample.time;
        }

        out.write(profile.buffer(), 0, profile.size());
    }

    public static void convert(String input, String output, Arguments args) throws IOException {
        try (JfrReader jfr = new JfrReader(input);
             FileOutputStream out = new FileOutputStream(output)) {
            new JfrToPprof(jfr, args).dump(out);
        }
    }
}
