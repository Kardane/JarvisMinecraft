package io.github.kardane.jarvisminecraft.common.chat;

import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class InvocationMatcher {
    private static final List<String> KOREAN_SUFFIXES = List.of(
        "",
        "아",
        "야",
        "은",
        "는",
        "이",
        "가",
        "을",
        "를",
        "도",
        "만",
        "의",
        "에",
        "에게",
        "한테",
        "께",
        "와",
        "과",
        "랑",
        "이랑",
        "으로",
        "로",
        "께서",
        "에서",
        "부터",
        "까지",
        "님",
        "님아",
        "님이",
        "님은",
        "님도"
    );

    public boolean matches(
        String message,
        List<String> wakeWords
    ) {
        return find(
            message,
            wakeWords,
            JarvisConfig.WakeWordMatching.defaults()
        ).isPresent();
    }

    public Optional<Match> find(
        String message,
        List<String> wakeWords,
        JarvisConfig.WakeWordMatching config
    ) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(wakeWords, "wakeWords");
        Objects.requireNonNull(config, "config");

        Match best = null;
        int index = 0;
        while (index < message.length()) {
            while (
                index < message.length()
                    && !isTokenChar(message.charAt(index))
            ) {
                index += 1;
            }
            if (index >= message.length()) {
                break;
            }

            int start = index;
            while (
                index < message.length()
                    && isTokenChar(message.charAt(index))
            ) {
                index += 1;
            }
            int end = index;

            if (
                !config.anywhere()
                    && hasNonWhitespaceBefore(
                        message,
                        start
                    )
            ) {
                break;
            }

            String token = message.substring(start, end);
            for (String wakeWord : wakeWords) {
                Match candidate = matchToken(
                    message,
                    token,
                    start,
                    end,
                    wakeWord,
                    config
                );
                if (
                    candidate != null
                        && (
                            best == null
                                || Match.ORDER.compare(
                                    candidate,
                                    best
                                ) < 0
                        )
                ) {
                    best = candidate;
                }
            }

            if (!config.anywhere()) {
                break;
            }
        }

        return Optional.ofNullable(best);
    }

    private Match matchToken(
        String message,
        String token,
        int start,
        int tokenEnd,
        String wakeWord,
        JarvisConfig.WakeWordMatching config
    ) {
        if (
            wakeWord == null
                || wakeWord.isBlank()
        ) {
            return null;
        }

        String normalizedWake =
            wakeWord.toLowerCase(Locale.ROOT);
        String normalizedToken =
            token.toLowerCase(Locale.ROOT);
        boolean korean = containsHangul(normalizedWake);

        Match exact = exactMatch(
            message,
            token,
            normalizedToken,
            start,
            wakeWord,
            normalizedWake,
            korean
        );
        if (exact != null) {
            return exact;
        }

        if (
            !config.fuzzyEnabled()
                || config.maxEditDistance() == 0
                || normalizedWake.length() < 3
        ) {
            return null;
        }

        int wakeLength = normalizedWake.length();
        int minLength = Math.max(
            1,
            wakeLength - config.maxEditDistance()
        );
        int maxLength = Math.min(
            normalizedToken.length(),
            wakeLength + config.maxEditDistance()
        );

        Match best = null;
        for (
            int candidateLength = minLength;
            candidateLength <= maxLength;
            candidateLength += 1
        ) {
            if (
                korean
                    && !allowedKoreanFuzzyLength(
                        normalizedToken,
                        wakeLength,
                        candidateLength
                    )
            ) {
                continue;
            }

            String candidate =
                normalizedToken.substring(
                    0,
                    candidateLength
                );
            String suffix =
                normalizedToken.substring(
                    candidateLength
                );
            if (
                !allowedSuffix(
                    suffix,
                    korean
                )
            ) {
                continue;
            }

            int distance = damerauLevenshtein(
                candidate,
                normalizedWake,
                config.maxEditDistance()
            );
            if (
                distance < 1
                    || distance
                        > config.maxEditDistance()
            ) {
                continue;
            }

            Match match = new Match(
                start,
                start + token.length(),
                wakeWord,
                token.substring(0, candidateLength),
                false,
                distance,
                stripInvocation(
                    message,
                    start,
                    start + token.length()
                )
            );
            if (
                best == null
                    || Match.ORDER.compare(
                        match,
                        best
                    ) < 0
            ) {
                best = match;
            }
        }
        return best;
    }

    private Match exactMatch(
        String message,
        String token,
        String normalizedToken,
        int start,
        String wakeWord,
        String normalizedWake,
        boolean korean
    ) {
        if (
            normalizedToken.length()
                < normalizedWake.length()
                || !normalizedToken.startsWith(
                    normalizedWake
                )
        ) {
            return null;
        }

        String suffix = normalizedToken.substring(
            normalizedWake.length()
        );
        if (!allowedSuffix(suffix, korean)) {
            return null;
        }

        return new Match(
            start,
            start + token.length(),
            wakeWord,
            token.substring(
                0,
                normalizedWake.length()
            ),
            true,
            0,
            stripInvocation(
                message,
                start,
                start + token.length()
            )
        );
    }

    private boolean allowedKoreanFuzzyLength(
        String token,
        int wakeLength,
        int candidateLength
    ) {
        if (candidateLength == wakeLength) {
            return true;
        }
        if (candidateLength != wakeLength + 1) {
            return false;
        }
        return isHangulJamo(
            token.charAt(candidateLength - 1)
        );
    }

    private boolean allowedSuffix(
        String suffix,
        boolean koreanWakeWord
    ) {
        if (suffix.isEmpty()) {
            return true;
        }
        return koreanWakeWord
            && KOREAN_SUFFIXES.contains(suffix);
    }

    private boolean containsHangul(String value) {
        for (
            int index = 0;
            index < value.length();
            index += 1
        ) {
            char ch = value.charAt(index);
            if (
                ch >= '\uAC00'
                    && ch <= '\uD7A3'
            ) {
                return true;
            }
        }
        return false;
    }

    private boolean isHangulJamo(char value) {
        return (
            value >= '\u1100'
                && value <= '\u11FF'
        ) || (
            value >= '\u3130'
                && value <= '\u318F'
        );
    }

    private boolean isTokenChar(char value) {
        return Character.isLetterOrDigit(value)
            || value == '_';
    }

    private boolean hasNonWhitespaceBefore(
        String message,
        int start
    ) {
        for (
            int index = 0;
            index < start;
            index += 1
        ) {
            if (!Character.isWhitespace(
                message.charAt(index)
            )) {
                return true;
            }
        }
        return false;
    }

    private String stripInvocation(
        String message,
        int start,
        int end
    ) {
        String left = message.substring(0, start)
            .stripTrailing();
        String right = message.substring(end)
            .stripLeading();

        if (
            !right.isEmpty()
                && isGapSeparator(
                    right.charAt(0)
                )
        ) {
            right = right.substring(1)
                .stripLeading();
        } else if (
            right.isEmpty()
                && !left.isEmpty()
                && isGapSeparator(
                    left.charAt(left.length() - 1)
                )
        ) {
            left = left.substring(
                0,
                left.length() - 1
            ).stripTrailing();
        }

        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        if (isClosingPunctuation(right.charAt(0))) {
            return left + right;
        }
        return left + " " + right;
    }

    private boolean isGapSeparator(char value) {
        return value == ','
            || value == ':'
            || value == ';'
            || value == '，'
            || value == '：'
            || value == '；';
    }

    private boolean isClosingPunctuation(char value) {
        return value == '.'
            || value == ','
            || value == ':'
            || value == ';'
            || value == '!'
            || value == '?'
            || value == '。'
            || value == '，'
            || value == '！'
            || value == '？';
    }

    private int damerauLevenshtein(
        String left,
        String right,
        int limit
    ) {
        int rows = left.length() + 1;
        int cols = right.length() + 1;
        int[][] distance = new int[rows][cols];

        for (int row = 0; row < rows; row += 1) {
            distance[row][0] = row;
        }
        for (int col = 0; col < cols; col += 1) {
            distance[0][col] = col;
        }

        for (int row = 1; row < rows; row += 1) {
            int rowMinimum = Integer.MAX_VALUE;
            for (int col = 1; col < cols; col += 1) {
                int cost =
                    left.charAt(row - 1)
                            == right.charAt(col - 1)
                        ? 0
                        : 1;
                int value = Math.min(
                    Math.min(
                        distance[row - 1][col] + 1,
                        distance[row][col - 1] + 1
                    ),
                    distance[row - 1][col - 1] + cost
                );

                if (
                    row > 1
                        && col > 1
                        && left.charAt(row - 1)
                            == right.charAt(col - 2)
                        && left.charAt(row - 2)
                            == right.charAt(col - 1)
                ) {
                    value = Math.min(
                        value,
                        distance[row - 2][col - 2] + 1
                    );
                }
                distance[row][col] = value;
                rowMinimum = Math.min(
                    rowMinimum,
                    value
                );
            }
            if (rowMinimum > limit) {
                return rowMinimum;
            }
        }
        return distance[rows - 1][cols - 1];
    }

    public record Match(
        int start,
        int end,
        String wakeWord,
        String matchedText,
        boolean exact,
        int editDistance,
        String remainingText
    ) {
        private static final Comparator<Match> ORDER =
            Comparator.comparing(Match::exact)
                .reversed()
                .thenComparingInt(Match::editDistance)
                .thenComparingInt(Match::start);

        public Match {
            Objects.requireNonNull(
                wakeWord,
                "wakeWord"
            );
            Objects.requireNonNull(
                matchedText,
                "matchedText"
            );
            Objects.requireNonNull(
                remainingText,
                "remainingText"
            );
        }
    }
}
