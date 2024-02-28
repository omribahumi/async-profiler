/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import one.jfr.ClassRef;
import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.MethodRef;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static one.convert.FlameGraph.*;

public class JfrConverter {
    protected static final byte[] UNKNOWN_NAME = "[unknown]".getBytes();

    protected final JfrReader jfr;
    protected final Arguments args;

    public JfrConverter(JfrReader jfr, Arguments args) {
        this.jfr = jfr;
        this.args = args;
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

    public byte[] getMethodNameBytes(long methodId, byte methodType) {
        MethodRef method = jfr.methods.get(methodId);
        if (method == null) {
            return UNKNOWN_NAME;
        }

        ClassRef cls = jfr.classes.get(method.cls);
        byte[] className = jfr.symbols.get(cls.name);
        byte[] methodName = jfr.symbols.get(method.name);

        if (className == null || className.length == 0 || isNativeFrame(methodType)) {
            return methodName;
        } else if (methodName == null || methodName.length == 0) {
            return className;
        } else {
            byte[] fullName = Arrays.copyOf(className, className.length + 1 + methodName.length);
            fullName[className.length] = '.';
            System.arraycopy(methodName, 0, fullName, className.length + 1, methodName.length);
            return fullName;
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
        // In JDK Flight Recorder, FRAME_NATIVE denotes Java native methods,
        // while in async-profiler, FRAME_NATIVE is for C methods
        return methodType == FRAME_NATIVE && jfr.getEnumValue("jdk.types.FrameType", FRAME_KERNEL) != null ||
                methodType == FRAME_CPP ||
                methodType == FRAME_KERNEL;
    }
}
