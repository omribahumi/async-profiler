/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.StackTrace;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.proto.Proto;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Converts .jfr output to <a href="https://github.com/google/pprof">pprof</a>.
 */
public class JfrToPprof extends JfrConverter {
    private final Proto profile = new Proto(100000);
    private final Index<String> strings = new Index<>(String.class, "");
    private final Index<String> functions = new Index<>(String.class, "");
    private final Index<Long> locations = new Index<>(Long.class, 0L);

    private Dictionary<String> methodNames;
    private Classifier classifier;
    private double ticksToNanos;
    private long lastTicks;

    public JfrToPprof(JfrReader jfr, Arguments args) {
        super(jfr, args);

        String type = args.alloc || args.live ? "allocations" : args.lock ? "locks" : "cpu";
        profile.field(1, valueType(type, "nanoseconds"))
                .field(13, strings.index("async-profiler"));
        lastTicks = jfr.startTicks;
    }

    @Override
    protected void convertChunk() throws IOException {
        ArrayList<Event> list = new ArrayList<>();
        readEvents(list::add);
        Collections.sort(list);

        methodNames = new Dictionary<>();
        classifier = new Classifier(methodNames);
        ticksToNanos = 1e9 / jfr.ticksPerSec;

        Proto s = new Proto(100);
        for (Event event : list) {
            profile.field(2, sample(s, event));
            s.reset();
        }
    }

    public void dump(OutputStream out) throws IOException {
        Long[] locations = this.locations.keys();
        for (int i = 1; i < locations.length; i++) {
            profile.field(4, location(i, locations[i]));
        }

        String[] functions = this.functions.keys();
        for (int i = 1; i < functions.length; i++) {
            profile.field(5, function(i, functions[i]));
        }

        String[] strings = this.strings.keys();
        for (String string : strings) {
            profile.field(6, string);
        }

        profile.field(9, jfr.startNanos)
                .field(10, jfr.durationNanos());

        out.write(profile.buffer(), 0, profile.size());
    }

    private Proto sample(Proto s, Event event) {
        StackTrace stackTrace = jfr.stackTraces.get(event.stackTraceId);
        if (stackTrace != null) {
            long[] methods = stackTrace.methods;
            byte[] types = stackTrace.types;
            int[] lines = stackTrace.locations;
            for (int i = 0; i < methods.length; i++) {
                String methodName = getMethodName(methods[i], types[i], methodNames);
                int function = functions.index(methodName);
                s.field(1, locations.index((long) function << 16 | lines[i] >>> 16));
            }
        }

        s.field(2, (long) ((event.time - lastTicks) * ticksToNanos));
        lastTicks = event.time;

        long classId = event.classId();
        if (classId != 0) {
            s.field(3, label("class", getClassName(classId)));
            if (event instanceof AllocationSample) {
                s.field(3, label("allocation_size", event.value(), "bytes"));
            } else if (event instanceof ContendedLock) {
                s.field(3, label("duration", event.value(), "nanoseconds"));
            }
        }

        if (args.threads && event.tid != 0) {
            s.field(3, label("thread", getThreadName(event.tid)));
        }
        if (args.classify && stackTrace != null) {
            s.field(3, label("category", classifier.getCategory(stackTrace).title));
        }

        return s;
    }

    private Proto valueType(String type, String unit) {
        return new Proto(16)
                .field(1, strings.index(type))
                .field(2, strings.index(unit));
    }

    private Proto label(String key, String str) {
        return new Proto(16)
                .field(1, strings.index(key))
                .field(2, strings.index(str));
    }

    private Proto label(String key, long num, String unit) {
        return new Proto(16)
                .field(1, strings.index(key))
                .field(3, num)
                .field(4, strings.index(unit));
    }

    private Proto location(int id, long location) {
        return new Proto(16)
                .field(1, id)
                .field(4, line((int) (location >>> 16), (int) location & 0xffff));
    }

    private Proto line(int functionId, int line) {
        return new Proto(16)
                .field(1, functionId)
                .field(2, line);
    }

    private Proto function(int id, String name) {
        return new Proto(16)
                .field(1, id)
                .field(2, strings.index(name));
    }

    public static void convert(String input, String output, Arguments args) throws IOException {
        JfrToPprof converter;
        try (JfrReader jfr = new JfrReader(input)) {
            converter = new JfrToPprof(jfr, args);
            converter.convert();
        }
        try (FileOutputStream out = new FileOutputStream(output)) {
            converter.dump(out);
        }
    }
}
