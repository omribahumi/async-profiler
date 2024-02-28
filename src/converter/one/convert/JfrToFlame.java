/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.StackTrace;
import one.jfr.event.*;

import java.io.*;

/**
 * Converts .jfr output to HTML Flame Graph.
 */
public class JfrToFlame extends JfrConverter {

    private static final String[] FRAME_SUFFIX = {"_[0]", "_[j]", "_[i]", "", "", "_[k]", "_[1]"};

    public JfrToFlame(JfrReader jfr, Arguments args) {
        super(jfr, args);
    }

    public void dump(OutputStream out) throws IOException {
        PrintStream ps = new PrintStream(new BufferedOutputStream(out, 32768), false, "UTF-8");
        FlameGraph fg = "collapsed".equals(args.output) ? new CollapsedStacks(args, ps) : new FlameGraph(args);

        Class<? extends Event> eventClass =
                args.live ? LiveObject.class :
                        args.alloc ? AllocationSample.class :
                                args.lock ? ContendedLock.class : ExecutionSample.class;

        jfr.stopAtNewChunk = true;
        while (jfr.hasMoreChunks()) {
            convertChunk(fg, eventClass);
        }

        fg.dump(ps);
    }

    public void convertChunk(final FlameGraph fg, Class<? extends Event> eventClass) throws IOException {
        EventAggregator agg = new EventAggregator(args.threads, args.total);

        long threadStates = 0;
        if (args.state != null) {
            for (String state : args.state.split(",")) {
                int key = jfr.getEnumKey("jdk.types.ThreadState", "STATE_" + state.toUpperCase());
                if (key >= 0) threadStates |= 1L << key;
            }
        }

        long startTicks = args.from != 0 ? toTicks(args.from) : Long.MIN_VALUE;
        long endTicks = args.to != 0 ? toTicks(args.to) : Long.MAX_VALUE;

        for (Event event; (event = jfr.readEvent(eventClass)) != null; ) {
            if (event.time >= startTicks && event.time <= endTicks) {
                if (threadStates == 0 || (threadStates & (1L << ((ExecutionSample) event).threadState)) != 0) {
                    agg.collect(event);
                }
            }
        }

        final Dictionary<String> methodNames = new Dictionary<>();
        final Classifier classifier = new Classifier(methodNames);

        final double ticksToNanos = 1e9 / jfr.ticksPerSec;
        final boolean scale = args.total && args.lock && ticksToNanos != 1.0;

        // Don't use lambda for faster startup
        agg.forEach(new EventAggregator.Visitor() {
            @Override
            public void visit(Event event, long value) {
                StackTrace stackTrace = jfr.stackTraces.get(event.stackTraceId);
                if (stackTrace != null) {
                    Arguments args = JfrToFlame.this.args;
                    long[] methods = stackTrace.methods;
                    byte[] types = stackTrace.types;
                    int[] locations = stackTrace.locations;
                    long classId = event.classId();
                    String[] trace = new String[methods.length
                            + (args.threads ? 1 : 0)
                            + (args.classify ? 1 : 0)
                            + (classId != 0 ? 1 : 0)];
                    if (args.threads) {
                        trace[0] = getThreadName(event.tid);
                    }
                    int idx = trace.length;
                    if (classId != 0) {
                        String suffix = (event instanceof AllocationSample)
                                && ((AllocationSample) event).tlabSize == 0 ? "_[k]" : "_[i]";
                        trace[--idx] = getClassName(classId) + suffix;
                    }
                    for (int i = 0; i < methods.length; i++) {
                        String methodName = getMethodName(methods[i], types[i], methodNames);
                        int location;
                        if (args.lines && (location = locations[i] >>> 16) != 0) {
                            methodName += ":" + location;
                        } else if (args.bci && (location = locations[i] & 0xffff) != 0) {
                            methodName += "@" + location;
                        }
                        trace[--idx] = methodName + FRAME_SUFFIX[types[i]];
                    }
                    if (args.classify) {
                        trace[--idx] = classifier.getCategoryName(stackTrace);
                    }
                    fg.addSample(trace, scale ? (long) (value * ticksToNanos) : value);
                }
            }
        });
    }

    // millis can be an absolute timestamp or an offset from the beginning/end of the recording
    private long toTicks(long millis) {
        long nanos = millis * 1_000_000;
        if (millis < 0) {
            nanos += jfr.endNanos;
        } else if (millis < 1500000000000L) {
            nanos += jfr.startNanos;
        }
        return (long) ((nanos - jfr.chunkStartNanos) * (jfr.ticksPerSec / 1e9)) + jfr.chunkStartTicks;
    }

    public static void convert(String input, String output, Arguments args) throws IOException {
        try (JfrReader jfr = new JfrReader(input);
             FileOutputStream out = new FileOutputStream(output)) {
            new JfrToFlame(jfr, args).dump(out);
        }
    }
}
