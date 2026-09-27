package io.github.kardane.jarvisminecraft.common.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record StyledChatMessage(
    List<Segment> prefix,
    String body
) {
    public StyledChatMessage {
        prefix = List.copyOf(
            Objects.requireNonNull(prefix, "prefix")
        );
        body = Objects.requireNonNull(body, "body");
    }

    public static StyledChatMessage fromLegacyPrefix(
        String configuredPrefix,
        String body
    ) {
        return new StyledChatMessage(
            parseLegacyPrefix(
                Objects.requireNonNull(
                    configuredPrefix,
                    "configuredPrefix"
                )
            ),
            body
        );
    }

    public String plainText() {
        StringBuilder output = new StringBuilder();
        for (Segment segment : prefix) {
            output.append(segment.text());
        }
        output.append(body);
        return output.toString();
    }

    private static List<Segment> parseLegacyPrefix(
        String value
    ) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StyleState style = new StyleState();

        for (int index = 0; index < value.length(); index += 1) {
            char current = value.charAt(index);
            if (
                current != '&'
                    || index + 1 >= value.length()
            ) {
                text.append(current);
                continue;
            }

            char code = Character.toLowerCase(
                value.charAt(index + 1)
            );
            if (!isLegacyCode(code)) {
                text.append(current);
                continue;
            }

            flush(segments, text, style);
            style = style.apply(code);
            index += 1;
        }

        flush(segments, text, style);
        return List.copyOf(segments);
    }

    private static void flush(
        List<Segment> segments,
        StringBuilder text,
        StyleState style
    ) {
        if (text.isEmpty()) {
            return;
        }
        segments.add(
            new Segment(
                text.toString(),
                style.rgb(),
                style.obfuscated(),
                style.bold(),
                style.strikethrough(),
                style.underlined(),
                style.italic()
            )
        );
        text.setLength(0);
    }

    private static boolean isLegacyCode(char code) {
        return "0123456789abcdefklmnor"
            .indexOf(code) >= 0;
    }

    public record Segment(
        String text,
        Integer rgb,
        boolean obfuscated,
        boolean bold,
        boolean strikethrough,
        boolean underlined,
        boolean italic
    ) {
        public Segment {
            Objects.requireNonNull(text, "text");
            if (
                rgb != null
                    && (rgb < 0 || rgb > 0xFFFFFF)
            ) {
                throw new IllegalArgumentException(
                    "rgb must be a 24-bit value."
                );
            }
        }
    }

    private record StyleState(
        Integer rgb,
        boolean obfuscated,
        boolean bold,
        boolean strikethrough,
        boolean underlined,
        boolean italic
    ) {
        private StyleState() {
            this(
                null,
                false,
                false,
                false,
                false,
                false
            );
        }

        StyleState apply(char code) {
            Integer color = legacyColor(code);
            if (color != null) {
                return new StyleState(
                    color,
                    false,
                    false,
                    false,
                    false,
                    false
                );
            }

            return switch (code) {
                case 'k' -> new StyleState(
                    rgb,
                    true,
                    bold,
                    strikethrough,
                    underlined,
                    italic
                );
                case 'l' -> new StyleState(
                    rgb,
                    obfuscated,
                    true,
                    strikethrough,
                    underlined,
                    italic
                );
                case 'm' -> new StyleState(
                    rgb,
                    obfuscated,
                    bold,
                    true,
                    underlined,
                    italic
                );
                case 'n' -> new StyleState(
                    rgb,
                    obfuscated,
                    bold,
                    strikethrough,
                    true,
                    italic
                );
                case 'o' -> new StyleState(
                    rgb,
                    obfuscated,
                    bold,
                    strikethrough,
                    underlined,
                    true
                );
                case 'r' -> new StyleState();
                default -> this;
            };
        }

        private static Integer legacyColor(char code) {
            return switch (code) {
                case '0' -> 0x000000;
                case '1' -> 0x0000AA;
                case '2' -> 0x00AA00;
                case '3' -> 0x00AAAA;
                case '4' -> 0xAA0000;
                case '5' -> 0xAA00AA;
                case '6' -> 0xFFAA00;
                case '7' -> 0xAAAAAA;
                case '8' -> 0x555555;
                case '9' -> 0x5555FF;
                case 'a' -> 0x55FF55;
                case 'b' -> 0x55FFFF;
                case 'c' -> 0xFF5555;
                case 'd' -> 0xFF55FF;
                case 'e' -> 0xFFFF55;
                case 'f' -> 0xFFFFFF;
                default -> null;
            };
        }
    }
}
