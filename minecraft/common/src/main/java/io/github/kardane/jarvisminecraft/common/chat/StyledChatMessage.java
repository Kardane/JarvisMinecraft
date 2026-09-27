package io.github.kardane.jarvisminecraft.common.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record StyledChatMessage(
    List<Segment> prefix,
    String body,
    List<HoverSegment> suffix
) {
    public StyledChatMessage(
        List<Segment> prefix,
        String body
    ) {
        this(prefix, body, List.of());
    }

    public StyledChatMessage {
        prefix = List.copyOf(
            Objects.requireNonNull(prefix, "prefix")
        );
        body = Objects.requireNonNull(body, "body");
        suffix = List.copyOf(
            Objects.requireNonNull(suffix, "suffix")
        );
    }

    public static StyledChatMessage fromConfiguredPrefix(
        String configuredPrefix,
        String body
    ) {
        return new StyledChatMessage(
            parseConfiguredPrefix(
                Objects.requireNonNull(
                    configuredPrefix,
                    "configuredPrefix"
                )
            ),
            body
        );
    }

    public static StyledChatMessage fromLegacyPrefix(
        String configuredPrefix,
        String body
    ) {
        return fromConfiguredPrefix(
            configuredPrefix,
            body
        );
    }

    public StyledChatMessage withHoverSuffix(
        String text,
        String hoverText
    ) {
        List<HoverSegment> next =
            new ArrayList<>(suffix);
        next.add(
            new HoverSegment(
                Objects.requireNonNull(text, "text"),
                Objects.requireNonNull(
                    hoverText,
                    "hoverText"
                )
            )
        );
        return new StyledChatMessage(
            prefix,
            body,
            next
        );
    }

    public String plainText() {
        StringBuilder output = new StringBuilder();
        for (Segment segment : prefix) {
            output.append(segment.text());
        }
        output.append(body);
        for (HoverSegment segment : suffix) {
            output.append(segment.text());
        }
        return output.toString();
    }

    private static List<Segment> parseConfiguredPrefix(
        String value
    ) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StyleState style = new StyleState();

        for (int index = 0; index < value.length(); index += 1) {
            if (isHexColorAt(value, index)) {
                flush(segments, text, style);
                int rgb = Integer.parseInt(
                    value.substring(index + 2, index + 8),
                    16
                );
                style = style.applyRgb(rgb);
                index += 8;
                continue;
            }

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

    private static boolean isHexColorAt(
        String value,
        int index
    ) {
        if (
            index + 8 >= value.length()
                || value.charAt(index) != '<'
                || value.charAt(index + 1) != '#'
                || value.charAt(index + 8) != '>'
        ) {
            return false;
        }
        for (int cursor = index + 2; cursor < index + 8; cursor += 1) {
            if (Character.digit(value.charAt(cursor), 16) < 0) {
                return false;
            }
        }
        return true;
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

    public record HoverSegment(
        String text,
        String hoverText
    ) {
        public HoverSegment {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(
                hoverText,
                "hoverText"
            );
            if (text.isEmpty()) {
                throw new IllegalArgumentException(
                    "Hover segment text must not be empty."
                );
            }
            if (hoverText.isBlank()) {
                throw new IllegalArgumentException(
                    "Hover text must not be blank."
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

        StyleState applyRgb(int color) {
            return new StyleState(
                color,
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
                return applyRgb(color);
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
