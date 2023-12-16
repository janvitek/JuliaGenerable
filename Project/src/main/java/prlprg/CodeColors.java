package prlprg;

class CodeColors {
    // TODO: Change colors for the light mode (I, Jan, dont' use it)

    // ANSI escape codes for text colors in light mode
    static String lightRed = "\u001B[31m";
    static String lightGreen = "\u001B[32m";
    static String lightYellow = "\u001B[33m";
    // ANSI escape codes for text colors in dark mode
    static String darkRed = "\u001B[91m";
    static String ResetText = "\u001B[0m";
    static String darkGreen = "\u001B[92m";
    static String darkYellow = "\u001B[93m";
    static String BLUE = "\u001B[34m";
    static String MAGENTA = "\u001B[35m";
    static String CYAN = "\u001B[36m";
    static String LIGHT_WHITE = "\u001B[37m";
    static String BRIGHT_YELLOW = "\u001B[93m";
    static String BRIGHT_MAGENTA = "\u001B[95m";
    static String BRIGHT_CYAN = "\u001B[96m";

    // Default mode (light mode)
    static enum Mode {
        LIGHT, DARK, NONE
    }

    static Mode mode = Mode.LIGHT;

    // Helper method to get the appropriate ANSI escape code based on the current mode
    static String getTextColor(String color) {
        return switch (mode) {
            case LIGHT ->
                ""; // not implemented
            case DARK ->
                switch (color) {
                    case "LightRed" ->
                        lightRed;
                    case "LightGreen" ->
                        lightGreen;
                    case "LightYellow" ->
                        lightYellow;
                    case "Red" ->
                        darkRed;
                    case "Green" ->
                        darkGreen;
                    case "Yellow" ->
                        darkYellow;
                    case "reset" ->
                        ResetText;
                    case "Blue" ->
                        BLUE;
                    case "Magenta" ->
                        MAGENTA;
                    case "Cyan" ->
                        CYAN;
                    case "LightWhite" ->
                        LIGHT_WHITE;
                    case "BrightYellow" ->
                        BRIGHT_YELLOW;
                    case "BrightMagenta" ->
                        BRIGHT_MAGENTA;
                    case "BrightCyan" ->
                        BRIGHT_CYAN;
                    default ->
                        "";
                };
            default ->
                "";
        };
    }

    static String comment(String s) {
        return color(s, "LightWhite");
    }

    static String variable(String s) {
        return color(s, "Cyan");
    }

    static String abstractType(String s) {
        return color(s, "Green");
    }

    static String exists(String s) {
        return color(s, "Red");
    }

    static String tuple(String s) {
        return color(s, "Yellow");
    }

    static String union(String s) {
        return color(s, "Cyan");
    }

    static String color(String s, String color) {
        return getTextColor(color) + s + getTextColor("reset");
    }
}
