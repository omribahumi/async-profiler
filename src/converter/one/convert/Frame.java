package one.convert;

import java.util.HashMap;

public class Frame extends HashMap<Integer, Frame> {
    public static final byte TYPE_INTERPRETED = 0;
    public static final byte TYPE_JIT_COMPILED = 1;
    public static final byte TYPE_INLINED = 2;
    public static final byte TYPE_NATIVE = 3;
    public static final byte TYPE_CPP = 4;
    public static final byte TYPE_KERNEL = 5;
    public static final byte TYPE_C1_COMPILED = 6;

    static final int TYPE_SHIFT = 28;

    final int key;
    long total;
    long self;
    long inlined, c1, interpreted;

    Frame(int key) {
        this.key = key;
    }

    int getTitleIndex() {
        return key & ((1 << TYPE_SHIFT) - 1);
    }

    byte getType() {
        if (inlined * 3 >= total) {
            return TYPE_INLINED;
        } else if (c1 * 2 >= total) {
            return TYPE_C1_COMPILED;
        } else if (interpreted * 2 >= total) {
            return TYPE_INTERPRETED;
        } else {
            return (byte) (key >>> TYPE_SHIFT);
        }
    }

    int depth(long cutoff) {
        int depth = 0;
        if (size() > 0) {
            for (Frame child : values()) {
                if (child.total >= cutoff) {
                    depth = Math.max(depth, child.depth(cutoff));
                }
            }
        }
        return depth + 1;
    }
}
