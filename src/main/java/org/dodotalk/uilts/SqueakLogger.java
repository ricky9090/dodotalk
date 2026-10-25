package org.dodotalk.uilts;

import org.dodotalk.SqueakConfig;

import java.io.PrintWriter;
import java.io.StringWriter;

public class SqueakLogger {

    public static final int V = 1;
    public static final int D = 2;
    public static final int I = 3;
    public static final int W = 4;
    public static final int E = 5;

    public static final String LOG_BLOCK_HEADER = " => +----------";
    public static final String LOG_BLOCK_FOOTER = "    +----------";
    public static final String LOG_BLOCK_PREFIX = "    | ";

    public static void logBlockContent(int level, String msg) {
        String msgInBlock = LOG_BLOCK_PREFIX + msg;
        switch (level) {
            case E:
                log_E(msgInBlock);
                break;
            case W:
                log_W(msgInBlock);
                break;
            case I:
                log_I(msgInBlock);
                break;
            case D:
                log_D(msgInBlock);
                break;
            default:
                log_V(msgInBlock);
        }
    }

    public static void log_V(String msg) {
        if (SqueakConfig.ENABLE_LOGGING) {
            System.out.println("[V]" + sanitize(msg));
        }
    }

    public static void log_D(String msg) {
        if (SqueakConfig.ENABLE_LOGGING && SqueakConfig.ENABLE_DEBUG_LOGGING) {
            System.out.println("[D]" + sanitize(msg));
        }
    }

    public static void log_I(String msg) {
        if (SqueakConfig.ENABLE_LOGGING) {
            System.out.println("[I]" + sanitize(msg));
        }
    }

    public static void log_W(String msg) {
        if (SqueakConfig.ENABLE_LOGGING) {
            System.out.println("[W]" + sanitize(msg));
        }
    }

    public static void log_W(String msg, Throwable t) {
        if (SqueakConfig.ENABLE_LOGGING) {
            System.out.println("[W]" + sanitize(msg));
            printThrowable(t);
        }
    }

    public static void log_E(String msg) {
        if (SqueakConfig.ENABLE_LOGGING) {
            System.err.println("[E]" + sanitize(msg));
        }
    }

    public static void log_E(String msg, Throwable t) {
        if (SqueakConfig.ENABLE_LOGGING) {
            System.err.println("[E]" + sanitize(msg));
            printThrowable(t);
        }
    }

    private static void printThrowable(Throwable t) {
        if (t == null) {
            return;
        }

        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        for (String line : trace.toString().split("\\R")) {
            System.err.println(sanitize(line));
        }
    }

    /**
     * Make a log message safe for console output: escape dangerous terminal
     * control characters (especially ESC, which Gradle's Jansi tries to parse
     * as an ANSI sequence) and cap very long messages.
     * Package-private for regression tests.
     */
    static String sanitize(String msg) {
        if (msg == null) {
            return "null";
        }

        int max = Math.min(msg.length(), SqueakConfig.LOGGING_MAX_LEN);
        StringBuilder result = new StringBuilder(max + 8);
        for (int i = 0; i < max; i++) {
            char c = msg.charAt(i);
            switch (c) {
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append(c);
                    break;
                default:
                    if (Character.isISOControl(c)) {
                        result.append("\\u");
                        result.append(HEX[(c >> 12) & 0xF]);
                        result.append(HEX[(c >> 8) & 0xF]);
                        result.append(HEX[(c >> 4) & 0xF]);
                        result.append(HEX[c & 0xF]);
                    } else {
                        result.append(c);
                    }
                    break;
            }
        }

        if (msg.length() > max) {
            result.append("...");
        }
        return result.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
