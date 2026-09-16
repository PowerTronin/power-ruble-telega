package ru.powerruble.telega;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ParsedTelegramCommandTest {
    @Test
    void parsesCommandMentionAndKeepsCommentTail() {
        ParsedTelegramCommand command = ParsedTelegramCommand.parse("/pay@PowerRubleBot Steve 25 dinner tonight");

        assertEquals("pay", command.name());
        assertEquals(4, command.argumentCount());
        assertEquals("Steve", command.argument(0));
        assertEquals("25", command.argument(1));
        assertEquals("dinner tonight", command.remainingFrom(2));
    }

    @Test
    void parsesStartPayloadAsRest() {
        ParsedTelegramCommand command = ParsedTelegramCommand.parse("/start LINKCODE");

        assertEquals("start", command.name());
        assertEquals("LINKCODE", command.rest());
        assertEquals("", command.remainingFrom(1));
    }

    @Test
    void nonCommandHasEmptyName() {
        ParsedTelegramCommand command = ParsedTelegramCommand.parse("hello");

        assertEquals("", command.name());
        assertEquals("hello", command.rest());
    }
}
