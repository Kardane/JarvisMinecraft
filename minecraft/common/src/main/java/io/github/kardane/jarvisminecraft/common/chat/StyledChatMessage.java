package io.github.kardane.jarvisminecraft.common.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
            parseConfiguredText(
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

    public List<Segment> bodySegments() {
        return parseModelBody(body);
    }

    public String plainBody() {
        StringBuilder output = new StringBuilder();
        for (Segment segment : bodySegments()) {
            output.append(segment.text());
        }
        return output.toString();
    }

    public static String plainModelText(String value) {
        return new StyledChatMessage(
            List.of(),
            Objects.requireNonNull(value, "value")
        ).plainBody();
    }

    public StyledChatMessage withHoverSuffix(
        String text,
        String hoverText
    ) {
        return withHoverSuffixSegments(
            List.of(
                new Segment(
                    Objects.requireNonNull(text, "text"),
                    null,
                    false,
                    false,
                    false,
                    false,
                    false
                )
            ),
            hoverText
        );
    }

    public StyledChatMessage withConfiguredHoverSuffix(
        String configuredText,
        String hoverText
    ) {
        return withHoverSuffixSegments(
            parseConfiguredText(
                Objects.requireNonNull(
                    configuredText,
                    "configuredText"
                )
            ),
            hoverText
        );
    }

    private StyledChatMessage withHoverSuffixSegments(
        List<Segment> segments,
        String hoverText
    ) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                "Hover suffix must contain visible text."
            );
        }
        List<HoverSegment> next =
            new ArrayList<>(suffix);
        next.add(
            new HoverSegment(
                segments,
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
        output.append(plainBody());
        for (HoverSegment segment : suffix) {
            for (Segment part : segment.segments()) {
                output.append(part.text());
            }
        }
        return output.toString();
    }

    private static List<Segment> parseConfiguredText(
        String value
    ) {
        return parseFormatting(value, false);
    }

    private static List<Segment> parseModelBody(
        String value
    ) {
        return parseFormatting(
            normalizeModelMarkdown(value),
            true
        );
    }

    private static List<Segment> parseFormatting(
        String value,
        boolean modelBody
    ) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StyleState style = new StyleState();
        Deque<StyleState> styleStack =
            new ArrayDeque<>();

        for (int index = 0; index < value.length(); index += 1) {
            if (
                modelBody
                    && value.charAt(index) == '`'
            ) {
                int close = value.indexOf(
                    '`',
                    index + 1
                );
                if (close > index) {
                    flush(segments, text, style);
                    addLiteral(
                        segments,
                        value.substring(index + 1, close),
                        style
                    );
                    index = close;
                    continue;
                }
            }

            if (
                modelBody
                    && index + 1 < value.length()
                    && value.charAt(index) == '*'
                    && value.charAt(index + 1) == '*'
            ) {
                int close = value.indexOf(
                    "**",
                    index + 2
                );
                if (close >= 0) {
                    flush(segments, text, style);
                    addBoldLiteral(
                        segments,
                        value.substring(index + 2, close),
                        style
                    );
                    index = close + 1;
                    continue;
                }
            }

            int closingColorLength =
                closingColorTagLengthAt(
                    value,
                    index
                );
            if (
                modelBody
                    && closingColorLength > 0
            ) {
                flush(segments, text, style);
                style = styleStack.isEmpty()
                    ? new StyleState()
                    : styleStack.pop();
                index += closingColorLength - 1;
                continue;
            }

            int resetTagLength =
                resetTagLengthAt(
                    value,
                    index
                );
            if (
                modelBody
                    && resetTagLength > 0
            ) {
                flush(segments, text, style);
                style = new StyleState();
                styleStack.clear();
                index += resetTagLength - 1;
                continue;
            }

            if (isHexColorAt(value, index)) {
                flush(segments, text, style);
                int rgb = Integer.parseInt(
                    value.substring(index + 2, index + 8),
                    16
                );
                if (modelBody) {
                    styleStack.push(style);
                }
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

            char rawCode = value.charAt(index + 1);
            char code = Character.toLowerCase(rawCode);
            if (modelBody) {
                if (
                    rawCode != code
                        || !isConfiguredCode(code)
                ) {
                    text.append(current);
                    continue;
                }
                if (!isModelCode(code)) {
                    index += 1;
                    continue;
                }
            } else if (!isConfiguredCode(code)) {
                text.append(current);
                continue;
            }

            flush(segments, text, style);
            style = style.apply(code);
            if (code == 'r') {
                styleStack.clear();
            }
            index += 1;
        }

        flush(segments, text, style);
        return List.copyOf(segments);
    }

    private static String normalizeModelMarkdown(
        String value
    ) {
        String[] lines = value.split("\\R", -1);
        StringBuilder output = new StringBuilder();

        for (int index = 0; index < lines.length; index += 1) {
            String line = lines[index];
            String trimmed = line.stripLeading();

            if (
                trimmed.startsWith(
                    "\u0060\u0060\u0060"
                )
            ) {
                continue;
            }

            int headingLength =
                headingPrefixLength(trimmed);
            if (headingLength > 0) {
                line = trimmed.substring(headingLength);
            } else if (trimmed.startsWith("> ")) {
                line = trimmed.substring(2);
            } else if (
                trimmed.startsWith("- ")
                    || trimmed.startsWith("* ")
                    || trimmed.startsWith("+ ")
            ) {
                line = "• " + trimmed.substring(2);
            }

            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(line);
        }
        return output.toString();
    }

    private static int headingPrefixLength(
        String value
    ) {
        int hashes = 0;
        while (
            hashes < value.length()
                && hashes < 6
                && value.charAt(hashes) == '#'
        ) {
            hashes += 1;
        }
        if (
            hashes > 0
                && hashes < value.length()
                && value.charAt(hashes) == ' '
        ) {
            return hashes + 1;
        }
        return 0;
    }

    private static int closingColorTagLengthAt(
        String value,
        int index
    ) {
        if (
            !value.startsWith("</#", index)
        ) {
            if (
                value.startsWith(
                    "</color>",
                    index
                )
            ) {
                return "</color>".length();
            }
            return 0;
        }

        int close = value.indexOf(
            '>',
            index + 3
        );
        if (
            close < 0
                || close - index > 10
        ) {
            return 0;
        }

        int hexLength = close - (index + 3);
        if (hexLength != 6) {
            return 0;
        }
        for (
            int cursor = index + 3;
            cursor < close;
            cursor += 1
        ) {
            if (
                Character.digit(
                    value.charAt(cursor),
                    16
                ) < 0
            ) {
                return 0;
            }
        }
        return close - index + 1;
    }

    private static int resetTagLengthAt(
        String value,
        int index
    ) {
        if (value.startsWith("<reset>", index)) {
            return "<reset>".length();
        }
        if (value.startsWith("</reset>", index)) {
            return "</reset>".length();
        }
        return 0;
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
        for (
            int cursor = index + 2;
            cursor < index + 8;
            cursor += 1
        ) {
            if (
                Character.digit(
                    value.charAt(cursor),
                    16
                ) < 0
            ) {
                return false;
            }
        }
        return true;
    }

    private static void addLiteral(
        List<Segment> segments,
        String value,
        StyleState style
    ) {
        if (value.isEmpty()) {
            return;
        }
        segments.add(
            new Segment(
                value,
                style.rgb(),
                false,
                style.bold(),
                false,
                style.underlined(),
                style.italic()
            )
        );
    }

    private static void addBoldLiteral(
        List<Segment> segments,
        String value,
        StyleState style
    ) {
        if (value.isEmpty()) {
            return;
        }
        segments.add(
            new Segment(
                value,
                style.rgb(),
                false,
                true,
                false,
                style.underlined(),
                style.italic()
            )
        );
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

    private static boolean isConfiguredCode(char code) {
        return "0123456789abcdefklmnor"
            .indexOf(code) >= 0;
    }

    private static boolean isModelCode(char code) {
        return "0123456789abcdeflnor"
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
        List<Segment> segments,
        String hoverText
    ) {
        public HoverSegment {
            segments = List.copyOf(
                Objects.requireNonNull(
                    segments,
                    "segments"
                )
            );
            Objects.requireNonNull(
                hoverText,
                "hoverText"
            );
            if (segments.isEmpty()) {
                throw new IllegalArgumentException(
                    "Hover segment must contain visible text."
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
