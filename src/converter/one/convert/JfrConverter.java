/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import one.jfr.ClassRef;
import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.MethodRef;
import one.jfr.event.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static one.convert.Frame.*;

public abstract class JfrConverter {
    protected final JfrReader jfr;
    protected final Arguments args;

    public JfrConverter(JfrReader jfr, Arguments args) {
        this.jfr = jfr;
        this.args = args;
    }

    public void convert() throws IOException {
        jfr.stopAtNewChunk = true;
        while (jfr.hasMoreChunks()) {
            convertChunk();
        }
    }

    protected abstract void convertChunk() throws IOException;

    protected void readEvents(Consumer<Event> consumer) throws IOException {
        Class<? extends Event> eventClass =
                args.live ? LiveObject.class :
                        args.alloc ? AllocationSample.class :
                                args.lock ? ContendedLock.class : ExecutionSample.class;

        long threadStates = 0;
        if (args.state != null) {
            for (String state : args.state.toUpperCase().split(",")) {
                threadStates |= 1L << toThreadState(state);
            }
        }

        long startTicks = args.from != 0 ? toTicks(args.from) : Long.MIN_VALUE;
        long endTicks = args.to != 0 ? toTicks(args.to) : Long.MAX_VALUE;

        for (Event event; (event = jfr.readEvent(eventClass)) != null; ) {
            if (event.time >= startTicks && event.time <= endTicks) {
                if (threadStates == 0 || (threadStates & (1L << ((ExecutionSample) event).threadState)) != 0) {
                    consumer.accept(event);
                }
            }
        }
    }

    protected int toThreadState(String name) {
        Map<Integer, String> threadStates = jfr.enums.get("jdk.types.ThreadState");
        if (threadStates != null) {
            for (Map.Entry<Integer, String> entry : threadStates.entrySet()) {
                if (entry.getValue().startsWith(name, 6)) {
                    return entry.getKey();
                }
            }
        }
        throw new IllegalArgumentException("Unknown thread state: " + name);
    }

    // millis can be an absolute timestamp or an offset from the beginning/end of the recording
    protected long toTicks(long millis) {
        long nanos = millis * 1_000_000;
        if (millis < 0) {
            nanos += jfr.endNanos;
        } else if (millis < 1500000000000L) {
            nanos += jfr.startNanos;
        }
        return (long) ((nanos - jfr.chunkStartNanos) * (jfr.ticksPerSec / 1e9)) + jfr.chunkStartTicks;
    }

    public String getMethodName(long methodId, byte methodType, Dictionary<String> cache) {
        String result = cache.get(methodId);
        if (result == null) {
            cache.put(methodId, result = getMethodName(methodId, methodType));
        }
        return result;
    }

    public String getMethodName(long methodId, byte methodType) {
        MethodRef method = jfr.methods.get(methodId);
        if (method == null) {
            return "unknown";
        }

        ClassRef cls = jfr.classes.get(method.cls);
        byte[] className = jfr.symbols.get(cls.name);
        byte[] methodName = jfr.symbols.get(method.name);

        if (className == null || className.length == 0 || isNativeFrame(methodType)) {
            return new String(methodName, StandardCharsets.UTF_8);
        } else {
            String classStr = toJavaClassName(className, 0, args.dot);
            if (methodName == null || methodName.length == 0) {
                return classStr;
            }
            String methodStr = new String(methodName, StandardCharsets.UTF_8);
            return classStr + '.' + methodStr;
        }
    }

    public String getClassName(long classId) {
        ClassRef cls = jfr.classes.get(classId);
        if (cls == null) {
            return "null";
        }
        byte[] className = jfr.symbols.get(cls.name);

        int arrayDepth = 0;
        while (className[arrayDepth] == '[') {
            arrayDepth++;
        }

        String name = toJavaClassName(className, arrayDepth, true);
        while (arrayDepth-- > 0) {
            name = name.concat("[]");
        }
        return name;
    }

    public String getThreadName(int tid) {
        String threadName = jfr.threads.get(tid);
        return threadName == null ? "[tid=" + tid + ']' :
                threadName.startsWith("[tid=") ? threadName : '[' + threadName + " tid=" + tid + ']';
    }

    private String toJavaClassName(byte[] symbol, int start, boolean dotted) {
        int end = symbol.length;
        if (start > 0) {
            switch (symbol[start]) {
                case 'B':
                    return "byte";
                case 'C':
                    return "char";
                case 'S':
                    return "short";
                case 'I':
                    return "int";
                case 'J':
                    return "long";
                case 'Z':
                    return "boolean";
                case 'F':
                    return "float";
                case 'D':
                    return "double";
                case 'L':
                    start++;
                    end--;
            }
        }

        if (args.norm) {
            for (int i = end - 2; i > start; i--) {
                if (symbol[i] == '/' || symbol[i] == '.') {
                    if (symbol[i + 1] >= '0' && symbol[i + 1] <= '9') {
                        end = i;
                        if (i > start + 19 && symbol[i - 19] == '+' && symbol[i - 18] == '0') {
                            // Original JFR transforms lambda names to something like
                            // pkg.ClassName$$Lambda+0x00007f8177090218/543846639
                            end = i - 19;
                        }
                    }
                    break;
                }
            }
        }

        if (args.simple) {
            for (int i = end - 2; i >= start; i--) {
                if (symbol[i] == '/' && (symbol[i + 1] < '0' || symbol[i + 1] > '9')) {
                    start = i + 1;
                    break;
                }
            }
        }

        String s = new String(symbol, start, end - start, StandardCharsets.UTF_8);
        return dotted ? s.replace('/', '.') : s;
    }

    private boolean isNativeFrame(byte methodType) {
        // In JDK Flight Recorder, TYPE_NATIVE denotes Java native methods,
        // while in async-profiler, TYPE_NATIVE is for C methods
        return methodType == TYPE_NATIVE && jfr.getEnumValue("jdk.types.FrameType", TYPE_KERNEL) != null ||
                methodType == TYPE_CPP ||
                methodType == TYPE_KERNEL;
    }
}
