/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

public class FlameGraph implements Comparator<Frame> {
    private static final Frame[] EMPTY_FRAME_ARRAY = {};

    private final Arguments args;
    private final Index<String> cpool = new Index<>(String.class, "");
    private final Frame root = new Frame(getFrameKey("", Frame.TYPE_NATIVE));
    private int[] order;
    private int depth;
    private int lastLevel;
    private long lastX;
    private long lastTotal;
    private long mintotal;

    public FlameGraph(Arguments args) {
        this.args = args;
    }

    public void parse(Reader in) throws IOException {
        try (BufferedReader br = new BufferedReader(in)) {
            for (String line; (line = br.readLine()) != null; ) {
                int space = line.lastIndexOf(' ');
                if (space <= 0) continue;

                String[] trace = line.substring(0, space).split(";");
                long ticks = Long.parseLong(line.substring(space + 1));
                addSample(trace, ticks);
            }
        }
    }

    public void addSample(String[] trace, long ticks) {
        if (excludeTrace(trace)) {
            return;
        }

        Frame frame = root;
        if (args.reverse) {
            for (int i = trace.length; --i >= args.skip; ) {
                frame = addChild(frame, trace[i], ticks);
            }
        } else {
            for (int i = args.skip; i < trace.length; i++) {
                frame = addChild(frame, trace[i], ticks);
            }
        }
        frame.total += ticks;
        frame.self += ticks;

        depth = Math.max(depth, trace.length);
    }

    public void dump(PrintStream out) {
        mintotal = (long) (root.total * args.minwidth / 100);
        int depth = mintotal > 1 ? root.depth(mintotal) : this.depth + 1;

        String tail = getResource("/flame.html");

        tail = printTill(out, tail, "/*height:*/300");
        out.print(Math.min(depth * 16, 32767));

        tail = printTill(out, tail, "/*title:*/");
        out.print(args.title);

        tail = printTill(out, tail, "/*reverse:*/false");
        out.print(args.reverse);

        tail = printTill(out, tail, "/*depth:*/0");
        out.print(depth);

        tail = printTill(out, tail, "/*cpool:*/");
        printCpool(out);

        tail = printTill(out, tail, "/*frames:*/");
        printFrame(out, root, 0, 0);

        tail = printTill(out, tail, "/*highlight:*/");
        out.print(args.highlight != null ? "'" + escape(args.highlight) + "'" : "");

        out.print(tail);
    }

    private String printTill(PrintStream out, String data, String till) {
        int index = data.indexOf(till);
        out.print(data.substring(0, index));
        return data.substring(index + till.length());
    }

    private void printCpool(PrintStream out) {
        String[] strings = cpool.keySet().toArray(new String[0]);
        Arrays.sort(strings);
        out.print("'all'");

        order = new int[strings.length];
        String s = "";
        for (int i = 1; i < strings.length; i++) {
            int prefixLen = Math.min(getCommonPrefix(s, s = strings[i]), 95);
            out.print(",\n'" + escape((char) (prefixLen + ' ') + s.substring(prefixLen)) + "'");
            order[cpool.get(s)] = i;
        }

        // cpool is not used beyond this point
        cpool.clear();
    }

    private void printFrame(PrintStream out, Frame frame, int level, long x) {
        int nameAndType = order[frame.getTitleIndex()] << 3 | frame.getType();
        boolean hasExtraTypes = (frame.inlined | frame.c1 | frame.interpreted) != 0 &&
                frame.inlined < frame.total && frame.interpreted < frame.total;

        char func = 'f';
        if (level == lastLevel + 1 && x == lastX) {
            func = 'u';
        } else if (level == lastLevel && x == lastX + lastTotal) {
            func = 'n';
        }

        StringBuilder sb = new StringBuilder(24).append(func).append('(').append(nameAndType);
        if (func == 'f') {
            sb.append(',').append(level).append(',').append(x - lastX);
        }
        if (frame.total != lastTotal || hasExtraTypes) {
            sb.append(',').append(frame.total);
            if (hasExtraTypes) {
                sb.append(',').append(frame.inlined).append(',').append(frame.c1).append(',').append(frame.interpreted);
            }
        }
        sb.append(')');
        out.println(sb.toString());

        lastLevel = level;
        lastX = x;
        lastTotal = frame.total;

        Frame[] children = frame.values().toArray(EMPTY_FRAME_ARRAY);
        Arrays.sort(children, this);

        x += frame.self;
        for (Frame child : children) {
            if (child.total >= mintotal) {
                printFrame(out, child, level + 1, x);
            }
            x += child.total;
        }
    }

    private boolean excludeTrace(String[] trace) {
        Pattern include = args.include;
        Pattern exclude = args.exclude;
        if (include == null && exclude == null) {
            return false;
        }

        for (String frame : trace) {
            if (exclude != null && exclude.matcher(frame).matches()) {
                return true;
            }
            if (include != null && include.matcher(frame).matches()) {
                if (exclude == null) return false;
                include = null;
            }
        }

        return include != null;
    }

    private int getFrameKey(String title, byte type) {
        return cpool.get(title) | type << Frame.TYPE_SHIFT;
    }

    private Frame getChild(Frame frame, String title, byte type) {
        int key = getFrameKey(title, type);
        Frame child = frame.get(key);
        if (child == null) {
            frame.put(key, child = new Frame(key));
        }
        return child;
    }

    private Frame addChild(Frame frame, String title, long ticks) {
        frame.total += ticks;

        Frame child;
        if (title.endsWith("_[j]")) {
            child = getChild(frame, stripSuffix(title), Frame.TYPE_JIT_COMPILED);
        } else if (title.endsWith("_[i]")) {
            (child = getChild(frame, stripSuffix(title), Frame.TYPE_JIT_COMPILED)).inlined += ticks;
        } else if (title.endsWith("_[k]")) {
            child = getChild(frame, stripSuffix(title), Frame.TYPE_KERNEL);
        } else if (title.endsWith("_[1]")) {
            (child = getChild(frame, stripSuffix(title), Frame.TYPE_JIT_COMPILED)).c1 += ticks;
        } else if (title.endsWith("_[0]")) {
            (child = getChild(frame, stripSuffix(title), Frame.TYPE_JIT_COMPILED)).interpreted += ticks;
        } else if (title.contains("::") || title.startsWith("-[") || title.startsWith("+[")) {
            child = getChild(frame, title, Frame.TYPE_CPP);
        } else if (title.indexOf('/') > 0 && title.charAt(0) != '['
                || title.indexOf('.') > 0 && Character.isUpperCase(title.charAt(0))) {
            child = getChild(frame, title, Frame.TYPE_JIT_COMPILED);
        } else {
            child = getChild(frame, title, Frame.TYPE_NATIVE);
        }
        return child;
    }

    static int getCommonPrefix(String a, String b) {
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) != b.charAt(i) || a.charAt(i) > 127) {
                return i;
            }
        }
        return length;
    }

    static String stripSuffix(String title) {
        return title.substring(0, title.length() - 4);
    }

    static String escape(String s) {
        if (s.indexOf('\\') >= 0) s = s.replace("\\", "\\\\");
        if (s.indexOf('\'') >= 0) s = s.replace("'", "\\'");
        return s;
    }

    private static String getResource(String name) {
        try (InputStream stream = FlameGraph.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("No resource found");
            }

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int length; (length = stream.read(buffer)) != -1; ) {
                result.write(buffer, 0, length);
            }
            return result.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("Can't load resource with name " + name);
        }
    }

    @Override
    public int compare(Frame f1, Frame f2) {
        return order[f1.getTitleIndex()] - order[f2.getTitleIndex()];
    }

    public static void convert(String input, String output, Arguments args) throws IOException {
        try (InputStreamReader in = new InputStreamReader(new FileInputStream(input), StandardCharsets.UTF_8);
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(output), 32768);
             PrintStream out = new PrintStream(bos, false, "UTF-8")) {
            FlameGraph fg = new FlameGraph(args);
            fg.parse(in);
            fg.dump(out);
        }
    }
}
