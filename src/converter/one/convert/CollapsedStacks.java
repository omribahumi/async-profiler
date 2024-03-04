/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import java.io.PrintStream;

public class CollapsedStacks extends FlameGraph {
    private static final String[] FRAME_SUFFIX = {"_[0]", "_[j]", "_[i]", "", "", "_[k]", "_[1]"};

    private final StringBuilder sb = new StringBuilder();
    private final PrintStream out;

    public CollapsedStacks(Arguments args, PrintStream out) {
        super(args);
        this.out = out;
    }

    @Override
    public void addSample(CallStack stack, long ticks) {
        for (int i = 0; i < stack.size; i++) {
            sb.append(stack.names[i]).append(FRAME_SUFFIX[stack.types[i]]).append(';');
        }
        if (sb.length() > 0) sb.setCharAt(sb.length() - 1, ' ');
        sb.append(ticks);

        out.println(sb);
        sb.setLength(0);
    }

    @Override
    public void dump(PrintStream out) {
        // Everything has already been printed in addSample()
    }
}
