package io.github.kardane.jarvisminecraft.common.chat;

import java.util.List;
import java.util.Objects;

public final class InvocationMatcher {
    public boolean matches(String message, List<String> wakeWords) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(wakeWords, "wakeWords");

        for (String wakeWord : wakeWords) {
            if (matchesWakeWord(message, wakeWord)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesWakeWord(String message, String wakeWord) {
        if (
            wakeWord == null
                || wakeWord.isBlank()
                || message.length() < wakeWord.length()
                || !message.regionMatches(
                    true,
                    0,
                    wakeWord,
                    0,
                    wakeWord.length()
                )
        ) {
            return false;
        }

        if (message.length() == wakeWord.length()) {
            return true;
        }

        return isBoundary(message.charAt(wakeWord.length()));
    }

    private boolean isBoundary(char value) {
        return Character.isWhitespace(value)
            || value == ','
            || value == ':'
            || value == ';'
            || value == '.'
            || value == '!'
            || value == '?'
            || value == '。'
            || value == '！'
            || value == '？';
    }
}
