package ru.powerruble.telega;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ParsedTelegramCommand {
    private final String name;
    private final String rest;
    private final List<String> arguments;

    private ParsedTelegramCommand(String name, String rest, List<String> arguments) {
        this.name = name;
        this.rest = rest;
        this.arguments = List.copyOf(arguments);
    }

    static ParsedTelegramCommand parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.startsWith("/") || trimmed.length() == 1) {
            return new ParsedTelegramCommand("", trimmed, List.of());
        }

        int firstSpace = firstWhitespace(trimmed);
        String commandToken = firstSpace < 0 ? trimmed.substring(1) : trimmed.substring(1, firstSpace);
        int mentionIndex = commandToken.indexOf('@');
        String name = (mentionIndex < 0 ? commandToken : commandToken.substring(0, mentionIndex)).toLowerCase(Locale.ROOT);
        String rest = firstSpace < 0 ? "" : trimmed.substring(firstSpace + 1).trim();
        return new ParsedTelegramCommand(name, rest, splitArguments(rest));
    }

    String name() {
        return name;
    }

    String rest() {
        return rest;
    }

    int argumentCount() {
        return arguments.size();
    }

    String argument(int index) {
        return index >= 0 && index < arguments.size() ? arguments.get(index) : "";
    }

    String remainingFrom(int argumentIndex) {
        if (argumentIndex <= 0) {
            return rest;
        }
        if (argumentIndex >= arguments.size()) {
            return "";
        }

        int position = 0;
        for (int index = 0; index < argumentIndex; index++) {
            position = skipWhitespace(rest, position);
            while (position < rest.length() && !Character.isWhitespace(rest.charAt(position))) {
                position++;
            }
        }

        return rest.substring(skipWhitespace(rest, position)).trim();
    }

    private static List<String> splitArguments(String rest) {
        if (rest.isBlank()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (String part : rest.split("\\s+")) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String value, int position) {
        int index = position;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }
}
