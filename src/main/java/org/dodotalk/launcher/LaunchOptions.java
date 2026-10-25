package org.dodotalk.launcher;

/**
 * Command-line options for the DodoTalk launcher.
 * <p>
 * The command line is intentionally small and backward compatible:
 * <ul>
 *   <li>{@code dodotalk <image>} boots the image directly.</li>
 *   <li>{@code dodotalk -image <image>} is the explicit form.</li>
 *   <li>{@code dodotalk --help} prints usage.</li>
 *   <li>{@code dodotalk} without arguments opens the image chooser.</li>
 * </ul>
 */
public final class LaunchOptions {

    private final String imagePath;
    private final boolean help;

    private LaunchOptions(String imagePath, boolean help) {
        this.imagePath = imagePath;
        this.help = help;
    }

    public String imagePath() {
        return imagePath;
    }

    public boolean help() {
        return help;
    }

    /**
     * Parses the command line.
     *
     * @throws IllegalArgumentException if an unknown option or an incomplete
     *                                  {@code -image} option is supplied
     */
    public static LaunchOptions parse(String[] args) {
        if (args == null || args.length == 0) {
            return new LaunchOptions(null, false);
        }
        if (args.length == 1) {
            String only = args[0];
            if ("--help".equals(only) || "-h".equals(only)) {
                return new LaunchOptions(null, true);
            }
            if ("-image".equals(only)) {
                throw new IllegalArgumentException("-image requires an image path");
            }
            if (only.startsWith("-")) {
                throw new IllegalArgumentException("Unknown option: " + only);
            }
            return new LaunchOptions(requireText(only), false);
        }
        if (args.length == 2 && "-image".equals(args[0])) {
            return new LaunchOptions(requireText(args[1]), false);
        }
        throw new IllegalArgumentException("Unknown arguments: " + String.join(" ", args));
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Image path must not be empty");
        }
        return value;
    }

    public static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: dodotalk [IMAGE]",
                "       dodotalk -image IMAGE",
                "       dodotalk --help",
                "",
                "IMAGE must be a gzip-compressed Squeak image (*.image.gz).",
                "With no arguments, a file chooser is shown.",
                "Raw .image files are not supported yet.");
    }
}
