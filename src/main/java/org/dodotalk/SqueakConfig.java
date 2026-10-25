package org.dodotalk;

/**
 * Global config for DodoTalk
 */
public class SqueakConfig {

    /**
     * Master switch for normal logging. Disable with -Dsqueak.logging=false.
     */
    public static boolean ENABLE_LOGGING =
            Boolean.parseBoolean(System.getProperty("squeak.logging", "true"));

    /**
     * Verbose diagnostic logging (including object dumps). Enable with
     * -Dsqueak.debug=true. Disabled by default to keep normal runs quiet.
     */
    public static boolean ENABLE_DEBUG_LOGGING =
            Boolean.parseBoolean(System.getProperty("squeak.debug", "false"));

    /**
     * Dump every object in the image during startup. Enable only when debugging
     * image loading with -Dsqueak.dumpObjects=true.
     */
    public static boolean ENABLE_OBJECT_DUMP =
            Boolean.parseBoolean(System.getProperty("squeak.dumpObjects", "false"));

    /**
     * Log failed primitives at debug level. Disable with
     * -Dsqueak.primitiveFailures=false.
     */
    public static final boolean PRIMITIVE_FAILED_LOGGING =
            Boolean.parseBoolean(System.getProperty("squeak.primitiveFailures", "true"));

    /**
     * Collect primitive/timer/input/frame diagnostics and print them when the
     * JVM exits. Enable with -Dsqueak.stats=true (Gradle: -PsqueakStats=true).
     */
    public static final boolean ENABLE_RUNTIME_STATS =
            Boolean.parseBoolean(System.getProperty("squeak.stats", "false"));

    public static final int LOGGING_MAX_LEN = 300;
}
