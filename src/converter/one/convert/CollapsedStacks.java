/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.convert;

import java.io.PrintStream;

public class CollapsedStacks extends FlameGraph {
    private final StringBuilder sb = new StringBuilder();
    private final PrintStream out;

    public CollapsedStacks(Arguments args, PrintStream out) {
        super(args);
        this.out = out;
    }

    @Override
    public void addSample(String[] trace, long ticks) {
        for (String s : trace) {
            sb.append(s).append(';');
        }
        if (sb.length() > 0) sb.setCharAt(sb.length() - 1, ' ');
        sb.append(ticks);

        out.println(sb.toString());
        sb.setLength(0);
    }

    @Override
    public void dump(PrintStream out) {
        // Everything has already been printed in addSample()
    }
}
