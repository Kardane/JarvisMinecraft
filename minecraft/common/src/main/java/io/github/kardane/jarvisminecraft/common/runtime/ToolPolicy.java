package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class ToolPolicy {
    private static final int MAX_COMMAND_LENGTH = 2_048;
    private static final int MAX_NESTED_DEPTH = 8;
    private static final String COMMAND_PREFIX = "command.";

    private final Path path;
    private final Path legacyActionsPath;

    public static ToolPolicy inDirectory(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        return new ToolPolicy(
            dataDirectory.resolve("tools.properties"),
            dataDirectory.resolve("actions.properties")
        );
    }

    public ToolPolicy(Path path, Path legacyActionsPath) {
        this.path = Objects.requireNonNull(path, "path")
            .toAbsolutePath()
            .normalize();
        this.legacyActionsPath = legacyActionsPath == null
            ? null
            : legacyActionsPath.toAbsolutePath().normalize();
    }

    public synchronized void syncTools(Collection<ToolName> tools) {
        Objects.requireNonNull(tools, "tools");
        SyncState state = loadForSync();
        Map<String, Boolean> configured = state.configured();
        boolean changed = state.changed();

        for (ToolName tool : tools) {
            if (tool == null) {
                continue;
            }
            if (!configured.containsKey(tool.wireName())) {
                configured.put(tool.wireName(), defaultEnabled(tool));
                changed = true;
            }
        }

        if (changed) {
            write(configured);
        }
    }

    public synchronized void syncCommandRoots(Collection<String> commandRoots) {
        Objects.requireNonNull(commandRoots, "commandRoots");
        SyncState state = loadForSync();
        Map<String, Boolean> configured = state.configured();
        boolean changed = state.changed();

        for (String root : commandRoots) {
            String normalized = normalizeRoot(root);
            if (normalized == null) {
                continue;
            }
            String key = commandKey(normalized);
            if (!configured.containsKey(key)) {
                configured.put(key, false);
                changed = true;
            }
        }

        if (changed) {
            write(configured);
        }
    }

    public synchronized boolean isToolEnabled(ToolName tool) {
        Objects.requireNonNull(tool, "tool");
        Boolean configured = read().get(tool.wireName());
        return configured == null ? defaultEnabled(tool) : configured;
    }

    public synchronized Decision authorizeCommand(String rawCommand) {
        if (!isToolEnabled(ToolName.RUN_COMMAND)) {
            return Decision.denied(
                "",
                "run_command is disabled in tools.properties."
            );
        }
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
        if (!Boolean.TRUE.equals(configured.get(commandKey(root)))) {
            return Decision.denied(
                root,
                "Command action is disabled in tools.properties."
            );
        }

        String semanticRoot = semanticRoot(root);
        if ("execute".equals(semanticRoot) || "return".equals(semanticRoot)) {
            for (String nested : nestedAfterRun(command)) {
                Decision nestedDecision = authorize(
                    nested,
                    configured,
                    depth + 1
                );
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

    private SyncState loadForSync() {
        if (Files.exists(path)) {
            return new SyncState(new TreeMap<>(read()), false);
        }

        Map<String, Boolean> configured = new TreeMap<>();
        if (legacyActionsPath != null && Files.exists(legacyActionsPath)) {
            configured.putAll(readLegacyActions());
            configured.put(ToolName.RUN_COMMAND.wireName(), true);
        }
        return new SyncState(configured, true);
    }

    private Map<String, Boolean> read() {
        if (!Files.exists(path)) {
            return Map.of();
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                "Tool policy configuration path is not a regular file."
            );
        }

        LinkedHashMap<String, Boolean> output = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not read Tool policy configuration.",
                failure
            );
        }

        for (int index = 0; index < lines.size(); index += 1) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw invalidLine(index + 1);
            }

            String key = normalizeKey(line.substring(0, separator));
            if (key == null) {
                throw invalidLine(index + 1);
            }
            boolean enabled = parseBoolean(
                line.substring(separator + 1),
                index + 1,
                "tools.properties"
            );
            output.put(key, enabled);
        }
        return Map.copyOf(output);
    }

    private Map<String, Boolean> readLegacyActions() {
        if (legacyActionsPath == null || !Files.isRegularFile(legacyActionsPath)) {
            return Map.of();
        }

        Map<String, Boolean> output = new TreeMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(
                legacyActionsPath,
                StandardCharsets.UTF_8
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not read legacy command action configuration.",
                failure
            );
        }

        for (int index = 0; index < lines.size(); index += 1) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IllegalArgumentException(
                    "actions.properties line "
                        + (index + 1)
                        + " must be <command-root>=true|false."
                );
            }

            String root = normalizeRoot(line.substring(0, separator));
            if (root == null) {
                throw new IllegalArgumentException(
                    "actions.properties line "
                        + (index + 1)
                        + " must be <command-root>=true|false."
                );
            }
            boolean enabled = parseBoolean(
                line.substring(separator + 1),
                index + 1,
                "actions.properties"
            );
            output.put(commandKey(root), enabled);
        }
        return output;
    }

    private boolean parseBoolean(
        String value,
        int lineNumber,
        String source
    ) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                source
                    + " line "
                    + lineNumber
                    + " must use true or false."
            );
        };
    }

    private void write(Map<String, Boolean> configured) {
        StringBuilder output = new StringBuilder();
        output.append("# JarvisMinecraft Tool policy.\n");
        output.append("# Read-only Tools default to true; state-changing Tools default to false.\n");
        output.append("# Command roots are controlled by command.<root> and default to false.\n");
        output.append("# Changes are read at runtime; use only true or false.\n\n");

        configured.entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith(COMMAND_PREFIX))
            .forEach(entry -> append(output, entry));
        if (configured.keySet().stream().anyMatch(key -> key.startsWith(COMMAND_PREFIX))) {
            output.append('\n');
            configured.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(COMMAND_PREFIX))
                .forEach(entry -> append(output, entry));
        }

        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
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
                "Could not write Tool policy configuration.",
                failure
            );
        }
    }

    private void append(
        StringBuilder output,
        Map.Entry<String, Boolean> entry
    ) {
        output.append(entry.getKey())
            .append('=')
            .append(entry.getValue())
            .append('\n');
    }

    private boolean defaultEnabled(ToolName tool) {
        return !tool.stateChanging();
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(COMMAND_PREFIX)) {
            String root = normalizeRoot(key.substring(COMMAND_PREFIX.length()));
            return root == null ? null : commandKey(root);
        }
        if (key.isEmpty()) {
            return null;
        }
        for (int index = 0; index < key.length(); index += 1) {
            char ch = key.charAt(index);
            if (Character.isWhitespace(ch) || ch == '=' || ch == '#') {
                return null;
            }
        }
        return key;
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
                && !Character.isWhitespace(command.charAt(end))
        ) {
            end += 1;
        }
        String root = normalizeRoot(command.substring(0, end));
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
            if (Character.isWhitespace(ch) || ch == '=' || ch == '#') {
                return null;
            }
        }
        return root;
    }

    private String semanticRoot(String root) {
        int namespace = root.lastIndexOf(':');
        return namespace < 0 ? root : root.substring(namespace + 1);
    }

    private String commandKey(String root) {
        return COMMAND_PREFIX + root;
    }

    private List<String> nestedAfterRun(String command) {
        java.util.ArrayList<String> nestedCommands =
            new java.util.ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int index = 0;

        while (index < command.length()) {
            while (
                index < command.length()
                    && Character.isWhitespace(command.charAt(index))
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
                if (!quoted && Character.isWhitespace(ch)) {
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
                    && Character.isWhitespace(command.charAt(nestedStart))
            ) {
                nestedStart += 1;
            }
            if (nestedStart >= command.length()) {
                throw new IllegalArgumentException(
                    "run must be followed by a nested command."
                );
            }
            nestedCommands.add(command.substring(nestedStart));
        }

        return List.copyOf(nestedCommands);
    }

    private IllegalArgumentException invalidLine(int lineNumber) {
        return new IllegalArgumentException(
            "tools.properties line "
                + lineNumber
                + " must be <tool>=true|false or command.<root>=true|false."
        );
    }

    private record SyncState(
        Map<String, Boolean> configured,
        boolean changed
    ) {
    }

    public record Decision(
        boolean allowed,
        String root,
        String reason
    ) {
        public static Decision allowed(String root) {
            return new Decision(true, root, null);
        }

        public static Decision denied(String root, String reason) {
            return new Decision(
                false,
                root == null ? "" : root,
                Objects.requireNonNull(reason, "reason")
            );
        }
    }
}
