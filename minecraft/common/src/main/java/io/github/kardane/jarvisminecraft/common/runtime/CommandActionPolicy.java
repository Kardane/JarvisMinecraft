package io.github.kardane.jarvisminecraft.common.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class CommandActionPolicy {
    private static final int MAX_COMMAND_LENGTH = 2_048;
    private static final int MAX_NESTED_DEPTH = 8;

    private final Path path;

    public CommandActionPolicy(Path path) {
        this.path = Objects.requireNonNull(path, "path")
            .toAbsolutePath()
            .normalize();
    }

    public synchronized void sync(Collection<String> commandRoots) {
        Objects.requireNonNull(commandRoots, "commandRoots");
        Map<String, Boolean> configured = new TreeMap<>(read());
        boolean changed = !Files.exists(path);

        for (String root : commandRoots) {
            String normalized = normalizeRoot(root);
            if (normalized == null) {
                continue;
            }
            if (!configured.containsKey(normalized)) {
                configured.put(normalized, false);
                changed = true;
            }
        }

        if (changed) {
            write(configured);
        }
    }

    public synchronized Decision authorize(String rawCommand) {
        String command = normalizeCommand(rawCommand);
        return authorize(command, read(), 0);
    }

    public Path path() {
        return path;
    }

    private Decision authorize(
        String command,
        Map<String, Boolean> configured,
        int depth
    ) {
        if (depth > MAX_NESTED_DEPTH) {
            return Decision.denied("", "Nested command depth exceeded.");
        }

        String root = rootOf(command);
        if (!Boolean.TRUE.equals(configured.get(root))) {
            return Decision.denied(
                root,
                "Command action is disabled in actions.properties."
            );
        }

        String semanticRoot = semanticRoot(root);
        if (
            "execute".equals(semanticRoot)
                || "return".equals(semanticRoot)
        ) {
            for (String nested : nestedAfterRun(command)) {
                Decision nestedDecision =
                    authorize(nested, configured, depth + 1);
                if (!nestedDecision.allowed()) {
                    return Decision.denied(
                        root,
                        "Nested command action is disabled: "
                            + nestedDecision.root()
                    );
                }
            }
        }

        return Decision.allowed(root);
    }

    private Map<String, Boolean> read() {
        if (!Files.exists(path)) {
            return Map.of();
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                "Command action configuration path is not a regular file."
            );
        }

        LinkedHashMap<String, Boolean> output =
            new LinkedHashMap<>();
        final java.util.List<String> lines;
        try {
            lines = Files.readAllLines(
                path,
                StandardCharsets.UTF_8
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not read command action configuration.",
                failure
            );
        }

        for (int index = 0; index < lines.size(); index += 1) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (
                separator <= 0
                    || separator == line.length() - 1
            ) {
                throw invalidLine(index + 1);
            }

            String root = normalizeRoot(
                line.substring(0, separator)
            );
            if (root == null) {
                throw invalidLine(index + 1);
            }

            String rawValue = line.substring(separator + 1)
                .trim()
                .toLowerCase(Locale.ROOT);
            boolean enabled = switch (rawValue) {
                case "true" -> true;
                case "false" -> false;
                default -> throw invalidLine(index + 1);
            };
            output.put(root, enabled);
        }
        return Map.copyOf(output);
    }

    private void write(Map<String, Boolean> configured) {
        StringBuilder output = new StringBuilder();
        output.append(
            "# JarvisMinecraft command action policy.\n"
        );
        output.append(
            "# Generated from the server command dispatcher. "
                + "Set only true or false.\n"
        );
        output.append(
            "# New command roots are added automatically as false.\n\n"
        );
        configured.forEach((root, enabled) ->
            output.append(root)
                .append('=')
                .append(enabled)
                .append('\n')
        );

        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(
                path.getFileName() + ".tmp"
            );
            Files.writeString(
                temporary,
                output.toString(),
                StandardCharsets.UTF_8
            );
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not write command action configuration.",
                failure
            );
        }
    }

    private String normalizeCommand(String rawCommand) {
        Objects.requireNonNull(rawCommand, "rawCommand");
        if (
            rawCommand.length() > MAX_COMMAND_LENGTH
                || rawCommand.indexOf('\n') >= 0
                || rawCommand.indexOf('\r') >= 0
                || rawCommand.indexOf('\0') >= 0
        ) {
            throw new IllegalArgumentException(
                "Command must be a single line no longer than "
                    + MAX_COMMAND_LENGTH
                    + " characters."
            );
        }

        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException(
                "Command must not be blank."
            );
        }
        return command;
    }

    private String rootOf(String command) {
        int end = 0;
        while (
            end < command.length()
                && !Character.isWhitespace(
                    command.charAt(end)
                )
        ) {
            end += 1;
        }
        String root = normalizeRoot(
            command.substring(0, end)
        );
        if (root == null) {
            throw new IllegalArgumentException(
                "Command root is invalid."
            );
        }
        return root;
    }

    private String normalizeRoot(String value) {
        if (value == null) {
            return null;
        }
        String root = value.trim().toLowerCase(Locale.ROOT);
        if (root.startsWith("/")) {
            root = root.substring(1);
        }
        if (root.startsWith("minecraft:")) {
            root = root.substring("minecraft:".length());
        }
        if (root.isEmpty()) {
            return null;
        }
        for (int index = 0; index < root.length(); index += 1) {
            char ch = root.charAt(index);
            if (
                Character.isWhitespace(ch)
                    || ch == '='
                    || ch == '#'
            ) {
                return null;
            }
        }
        return root;
    }

    private String semanticRoot(String root) {
        int namespace = root.lastIndexOf(':');
        return namespace < 0
            ? root
            : root.substring(namespace + 1);
    }

    private List<String> nestedAfterRun(String command) {
        List<String> nestedCommands = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int index = 0;

        while (index < command.length()) {
            while (
                index < command.length()
                    && Character.isWhitespace(
                        command.charAt(index)
                    )
            ) {
                index += 1;
            }
            if (index >= command.length()) {
                break;
            }

            int start = index;
            while (index < command.length()) {
                char ch = command.charAt(index);
                if (escaped) {
                    escaped = false;
                    index += 1;
                    continue;
                }
                if (ch == '\\' && quoted) {
                    escaped = true;
                    index += 1;
                    continue;
                }
                if (ch == '"') {
                    quoted = !quoted;
                    index += 1;
                    continue;
                }
                if (
                    !quoted
                        && Character.isWhitespace(ch)
                ) {
                    break;
                }
                index += 1;
            }

            String token = command.substring(start, index);
            if (!"run".equalsIgnoreCase(token)) {
                continue;
            }

            int nestedStart = index;
            while (
                nestedStart < command.length()
                    && Character.isWhitespace(
                        command.charAt(nestedStart)
                    )
            ) {
                nestedStart += 1;
            }
            if (nestedStart >= command.length()) {
                throw new IllegalArgumentException(
                    "run must be followed by a nested command."
                );
            }
            nestedCommands.add(
                command.substring(nestedStart)
            );
        }

        return List.copyOf(nestedCommands);
    }

    private IllegalArgumentException invalidLine(int lineNumber) {
        return new IllegalArgumentException(
            "actions.properties line "
                + lineNumber
                + " must be <command-root>=true|false."
        );
    }

    public record Decision(
        boolean allowed,
        String root,
        String reason
    ) {
        public static Decision allowed(String root) {
            return new Decision(true, root, null);
        }

        public static Decision denied(
            String root,
            String reason
        ) {
            return new Decision(
                false,
                root == null ? "" : root,
                Objects.requireNonNull(reason, "reason")
            );
        }
    }
}
