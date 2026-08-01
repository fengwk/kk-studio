package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The single source of truth for the fixed tools provided by every Environment Daemon.
 *
 * <p>Descriptors, schemas and prompt text are deliberately defined in the dependency-free {@code
 * harness/tool} module. Core uses this catalog for validation and planning, while Daemon
 * implementations use the same descriptors for registration and wire validation.
 */
public final class EnvironmentToolCatalog {

  public static final String VERSION = "1";

  private static final String PROMPT_RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/harness/tool/environment/prompts/";
  private static final List<ToolDescriptor> DESCRIPTORS = createDescriptors();
  private static final Map<String, ToolDescriptor> BY_NAME = indexByName(DESCRIPTORS);

  private EnvironmentToolCatalog() {}

  public static String version() {
    return VERSION;
  }

  public static List<ToolDescriptor> descriptors() {
    return DESCRIPTORS;
  }

  public static Optional<ToolDescriptor> find(String name) {
    return Optional.ofNullable(BY_NAME.get(name));
  }

  public static Optional<ToolDescriptor> find(String name, String version) {
    return find(name).filter(descriptor -> descriptor.version().equals(version));
  }

  public static ToolDescriptor require(String name) {
    return find(name)
        .orElseThrow(() -> new IllegalArgumentException("unknown Environment tool: " + name));
  }

  public static ToolDescriptor require(String name, String version) {
    ToolDescriptor descriptor = require(name);
    if (!descriptor.version().equals(version)) {
      throw new IllegalArgumentException(
          "Environment tool version mismatch for "
              + name
              + ": expected "
              + descriptor.version()
              + " but got "
              + version);
    }
    return descriptor;
  }

  private static List<ToolDescriptor> createDescriptors() {
    return List.of(
        descriptor(
            "read",
            new ToolParamsSchema(
                "Read parameters",
                Map.of(
                    "path", new ToolStringSchema("File or directory path"),
                    "workdir", new ToolStringSchema("Optional environment-root-relative directory"),
                    "offset", new ToolIntegerSchema("One-based line offset"),
                    "limit", new ToolIntegerSchema("Maximum number of lines")),
                Set.of("path"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1)),
        descriptor(
            "write",
            new ToolParamsSchema(
                "Write parameters",
                Map.of(
                    "path", new ToolStringSchema("File path"),
                    "content", new ToolStringSchema("Complete replacement content"),
                    "workdir",
                        new ToolStringSchema("Optional environment-root-relative directory")),
                Set.of("path", "content"),
                false),
            ToolSideEffect.IDEMPOTENT,
            Duration.ofMinutes(1)),
        descriptor(
            "edit",
            new ToolParamsSchema(
                "Edit parameters",
                Map.of(
                    "path", new ToolStringSchema("File path"),
                    "old_string", new ToolStringSchema("Exact text to replace"),
                    "new_string", new ToolStringSchema("Replacement text"),
                    "replace_all", new ToolBooleanSchema("Replace every exact match"),
                    "workdir",
                        new ToolStringSchema("Optional environment-root-relative directory")),
                Set.of("path", "old_string", "new_string"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMinutes(1)),
        descriptor(
            "apply_patch",
            new ToolParamsSchema(
                "ApplyPatch parameters",
                Map.of(
                    "patchText",
                    new ToolStringSchema(
                        "Complete apply_patch protocol text from *** Begin Patch through *** End Patch. Optional first directive: *** Workdir: <path>.")),
                Set.of("patchText"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMinutes(2)),
        descriptor(
            "bash",
            new ToolParamsSchema(
                "Bash parameters",
                Map.of(
                    "command", new ToolStringSchema("Platform-authorized shell command"),
                    "workdir", new ToolStringSchema("Optional environment-root-relative directory"),
                    "timeout_seconds",
                        new ToolIntegerSchema(
                            "Optional timeout in seconds; defaults to 120 and must not exceed 3600")),
                Set.of("command"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofHours(1)),
        descriptor(
            "grep",
            new ToolParamsSchema(
                "Grep parameters",
                Map.of(
                    "pattern", new ToolStringSchema("Regular expression or literal"),
                    "path", new ToolStringSchema("Search file or directory"),
                    "workdir", new ToolStringSchema("Optional environment-root-relative directory"),
                    "include", new ToolStringSchema("Optional glob"),
                    "ignore_case", new ToolBooleanSchema("Ignore case"),
                    "literal", new ToolBooleanSchema("Treat pattern literally"),
                    "multiline", new ToolBooleanSchema("Enable multiline pattern"),
                    "limit", new ToolIntegerSchema("Maximum reported matches"),
                    "timeout_seconds", new ToolIntegerSchema("Search timeout in seconds")),
                Set.of("pattern", "path"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofHours(1)),
        descriptor(
            "find",
            new ToolParamsSchema(
                "Find parameters",
                Map.of(
                    "pattern", new ToolStringSchema("Glob pattern"),
                    "path", new ToolStringSchema("Search directory"),
                    "workdir", new ToolStringSchema("Optional environment-root-relative directory"),
                    "limit", new ToolIntegerSchema("Maximum results"),
                    "timeout_seconds", new ToolIntegerSchema("Search timeout in seconds")),
                Set.of("pattern", "path"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofHours(1)),
        descriptor(
            "lsp_goto_definition",
            new ToolParamsSchema(
                "LspGotoDefinition parameters",
                Map.of(
                    "path",
                    new ToolStringSchema(
                        "Existing source file path supported by an LSP server. This path is also used to infer the workspace root."),
                    "workdir",
                    new ToolStringSchema(
                        "Working directory for resolving relative paths. Defaults to the agent's current working directory. If provided, relative paths resolve from that directory."),
                    "line",
                    new ToolIntegerSchema("1-based line number for the target position."),
                    "character",
                    new ToolIntegerSchema(
                        "0-based character offset at the target position. Default: 0.")),
                Set.of("path", "line"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)),
        descriptor(
            "lsp_workspace_symbols",
            new ToolParamsSchema(
                "LspWorkspaceSymbols parameters",
                Map.of(
                    "path",
                    new ToolStringSchema(
                        "Existing source file path used to resolve the workspace root."),
                    "workdir",
                    new ToolStringSchema(
                        "Working directory for resolving relative paths. Defaults to the agent's current working directory. If provided, relative paths resolve from that directory."),
                    "query",
                    new ToolStringSchema("Symbol search query."),
                    "limit",
                    new ToolIntegerSchema(
                        "Maximum number of results to display locally. Default: 50.")),
                Set.of("path", "query"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)),
        descriptor(
            "lsp_java_decompile",
            new ToolParamsSchema(
                "LspJavaDecompile parameters",
                Map.of(
                    "path",
                    new ToolStringSchema(
                        "Any local `.java` file in the target workspace. This path is used to infer the workspace root and locate JDTLS."),
                    "workdir",
                    new ToolStringSchema(
                        "Working directory for resolving relative paths. Defaults to the agent's current working directory. If provided, relative paths resolve from that directory."),
                    "target",
                    new ToolStringSchema(
                        "A raw `jdt://` URI, a workspace symbol output line, or a `file://` / `.class` path.")),
                Set.of("path", "target"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)));
  }

  private static ToolDescriptor descriptor(
      String name, ToolParamsSchema inputSchema, ToolSideEffect sideEffect, Duration timeout) {
    return new ToolDescriptor(name, "1", loadPrompt(name), name, inputSchema, sideEffect, timeout);
  }

  private static Map<String, ToolDescriptor> indexByName(List<ToolDescriptor> descriptors) {
    Map<String, ToolDescriptor> result = new LinkedHashMap<>();
    for (ToolDescriptor descriptor : descriptors) {
      if (result.putIfAbsent(descriptor.name(), descriptor) != null) {
        throw new IllegalStateException(
            "duplicate Environment tool catalog name: " + descriptor.name());
      }
    }
    return Map.copyOf(result);
  }

  private static String loadPrompt(String name) {
    Objects.requireNonNull(name, "name");
    String resource = PROMPT_RESOURCE_PREFIX + name + ".md";
    try (InputStream input = EnvironmentToolCatalog.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing Environment tool prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException error) {
      throw new UncheckedIOException("failed to load Environment tool prompt: " + resource, error);
    }
  }
}
