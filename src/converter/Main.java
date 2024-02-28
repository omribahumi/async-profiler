/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

import one.convert.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class Main {

    public static void main(String[] argv) throws Exception {
        Arguments args = new Arguments(argv);
        if (args.files.isEmpty()) {
            usage();
            return;
        }

        if (args.files.size() == 1) {
            args.files.add(".");
        }

        int fileCount = args.files.size() - 1;
        String lastFile = args.files.get(fileCount);
        boolean isDirectory = new File(lastFile).isDirectory();

        if (args.output == null) {
            int ext;
            if (!isDirectory && (ext = lastFile.lastIndexOf('.')) > 0) {
                args.output = lastFile.substring(ext + 1);
            } else {
                args.output = "html";
            }
        }

        for (int i = 0; i < fileCount; i++) {
            String input = args.files.get(i);
            String output = isDirectory ? new File(lastFile, replaceExt(input, args.output)).getPath() : lastFile;
            if (isJfr(input)) {
                if ("pprof".equals(args.output)) {
                    JfrToPprof.convert(input, output, args);
                } else {
                    JfrToFlame.convert(input, output, args);
                }
            } else {
                FlameGraph.convert(input, output, args);
            }
        }
    }

    private static String replaceExt(String fileName, String ext) {
        int dot = fileName.lastIndexOf('.', fileName.lastIndexOf(File.separatorChar) + 1);
        return dot >= 0 ? fileName.substring(0, dot + 1) + ext : fileName + '.' + ext;
    }

    private static boolean isJfr(String fileName) throws IOException {
        if (fileName.endsWith(".jfr")) {
            return true;
        } else if (fileName.endsWith(".collapsed") || fileName.endsWith(".txt") || fileName.endsWith(".csv")) {
            return false;
        }
        byte[] buf = new byte[4];
        try (FileInputStream fis = new FileInputStream(fileName)) {
            return fis.read(buf) == 4 && buf[0] == 'F' && buf[1] == 'L' && buf[2] == 'R' && buf[3] == 0;
        }
    }

    private static void usage() {
        String launcher;
        if ("SUN_STANDARD".equals(System.getProperty("sun.java.launcher"))) {
            launcher = "java -jar jfrconv.jar";
        } else {
            launcher = System.getProperty("sun.java.command");
        }

        System.out.println("Usage: " + launcher + " [options] <input> <output>");
        System.out.println();
        System.out.println("Supported conversions:");
        System.out.println("  collapsed -> html (Flame Graph)");
        System.out.println("  jfr       -> html, collapsed, pprof");
        System.out.println();
        System.out.println("Flame Graph options:");
        System.out.println("  --title TITLE");
        System.out.println("  --minwidth PERCENT");
        System.out.println("  --skip FRAMES");
        System.out.println("  --reverse");
        System.out.println("  --include PATTERN");
        System.out.println("  --exclude PATTERN");
        System.out.println("  --highlight PATTERN");
        System.out.println();
        System.out.println("JFR options:");
        System.out.println("  --alloc       Allocation profile");
        System.out.println("  --live        Live object profile");
        System.out.println("  --lock        Lock contention profile");
        System.out.println("  --threads     Split stack traces by threads");
        System.out.println("  --state LIST  Filter samples by thread states: RUNNABLE, SLEEPING, etc.");
        System.out.println("  --classify    Classify samples into predefined categories");
        System.out.println("  --total       Accumulate total value (time, bytes, etc.)");
        System.out.println("  --lines       Show line numbers");
        System.out.println("  --bci         Show bytecode indices");
        System.out.println("  --simple      Simple class names instead of FQN");
        System.out.println("  --norm        Normalize names of hidden classes / lambdas");
        System.out.println("  --dot         Dotted class names");
        System.out.println("  --from TIME   Start time in ms (absolute or relative)");
        System.out.println("  --to TIME     End time in ms (absolute or relative)");
    }
}
