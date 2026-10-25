package org.dodotalk.uilts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqueakLoggerTest {

    @Test
    void leavesPrintableTextUntouched() {
        assertEquals("hello 世界", SqueakLogger.sanitize("hello 世界"));
    }

    @Test
    void escapesEscapeCharacter() {
        String sanitized = SqueakLogger.sanitize("\u001b[31mred\u001b[0m");
        assertFalse(sanitized.contains("\u001b"));
        assertEquals("\\u001b[31mred\\u001b[0m", sanitized);
    }

    @Test
    void escapesNewlinesAndCarriageReturns() {
        assertEquals("a\\nb\\rc", SqueakLogger.sanitize("a\nb\rc"));
    }

    @Test
    void preservesTabs() {
        assertEquals("a\tb", SqueakLogger.sanitize("a\tb"));
    }

    @Test
    void truncatesLongMessages() {
        String sanitized = SqueakLogger.sanitize("x".repeat(400));
        assertEquals(303, sanitized.length());
        assertTrue(sanitized.endsWith("..."));
    }

    @Test
    void handlesNull() {
        assertEquals("null", SqueakLogger.sanitize(null));
    }
}
