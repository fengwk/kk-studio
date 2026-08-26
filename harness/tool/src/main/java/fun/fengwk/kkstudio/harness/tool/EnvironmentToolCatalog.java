package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines model-visible environment-backed Agent Tools.
 *
 * <p>This catalog owns Agent Tool names, model prompt metadata and selectable visibility. Daemon
 * implementation, registration and wire contracts use {@link EnvironmentCapabilityCatalog}.
 */
public final class EnvironmentToolCatalog {

  public static final String VERSION = "3";

  private static final String RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/harness/tool/environment/prompts/";
  private static final List<Entry> ENTRIES = createEntries();
  private static final List<ToolDescriptor> DESCRIPTORS = descriptorsOf(ENTRIES);
  private static final Map<AgentToolId, Entry> BY_ID = indexById(ENTRIES);
  private static final Map<String, Entry> BY_NAME = indexByName(ENTRIES);

  private EnvironmentToolCatalog() {}

  /**
   * Stable mapping between a model-visible Environment-backed Base Tool and its execution
   * capability.
   */
  public record Entry(AgentToolDefinition definition, EnvironmentCapabilityDescriptor capability) {

    public Entry {
      definition = Objects.requireNonNull(definition, "definition");
      capability = Objects.requireNonNull(capability, "capability");
      if (definition.backend() != AgentToolBackend.ENVIRONMENT_CAPABILITY) {
        throw new IllegalArgumentException(
            "Environment catalog entries must use the Environment Capability backend");
      }
      if (definition.visibility() != ToolVisibility.SELECTABLE) {
        throw new IllegalArgumentException("Environment catalog entries must be selectable");
      }
      if (definition.descriptor().type() != ToolType.ENVIRONMENT) {
        throw new IllegalArgumentException(
            "Environment catalog entries must use Environment descriptors");
      }
      if (!definition.descriptor().inputSchema().equals(capability.inputSchema())
          || !definition.descriptor().timeout().equals(capability.timeout())) {
        throw new IllegalArgumentException(
            "Environment tool descriptor schema and timeout must match capability");
      }
    }
  }

  public static String version() {
    return VERSION;
  }

  public static List<Entry> entries() {
    return ENTRIES;
  }

  public static List<ToolDescriptor> descriptors() {
    return DESCRIPTORS;
  }

  public static Optional<ToolDescriptor> find(String name) {
    return Optional.ofNullable(BY_NAME.get(name)).map(entry -> entry.definition().descriptor());
  }

  public static Optional<Entry> find(AgentToolId id) {
    return Optional.ofNullable(BY_ID.get(id));
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

  public static Entry require(AgentToolId id) {
    return find(id)
        .orElseThrow(() -> new IllegalArgumentException("unknown Environment tool id: " + id));
  }

  private static List<Entry> createEntries() {
    return List.of(
        entry(BaseToolIds.READ, "read", EnvironmentCapabilityIds.FS_READ, ToolSideEffect.READ_ONLY),
        entry(
            BaseToolIds.WRITE,
            "write",
            EnvironmentCapabilityIds.FS_WRITE,
            ToolSideEffect.IDEMPOTENT),
        entry(
            BaseToolIds.EDIT,
            "edit",
            EnvironmentCapabilityIds.FS_APPLY_EDIT,
            ToolSideEffect.NON_IDEMPOTENT),
        entry(
            BaseToolIds.APPLY_PATCH,
            "apply_patch",
            EnvironmentCapabilityIds.FS_APPLY_PATCH,
            ToolSideEffect.NON_IDEMPOTENT),
        entry(
            BaseToolIds.BASH,
            "bash",
            EnvironmentCapabilityIds.PROCESS_EXEC,
            ToolSideEffect.NON_IDEMPOTENT),
        entry(
            BaseToolIds.GREP, "grep", EnvironmentCapabilityIds.FS_SEARCH, ToolSideEffect.READ_ONLY),
        entry(BaseToolIds.FIND, "find", EnvironmentCapabilityIds.FS_FIND, ToolSideEffect.READ_ONLY),
        entry(
            BaseToolIds.LSP_GOTO_DEFINITION,
            "lsp_goto_definition",
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            ToolSideEffect.READ_ONLY),
        entry(
            BaseToolIds.LSP_WORKSPACE_SYMBOLS,
            "lsp_workspace_symbols",
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            ToolSideEffect.READ_ONLY),
        entry(
            BaseToolIds.LSP_JAVA_DECOMPILE,
            "lsp_java_decompile",
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
            ToolSideEffect.READ_ONLY),
        entry(
            BaseToolIds.MCP_LIST_TOOLS,
            "mcp_list_tools",
            EnvironmentCapabilityIds.MCP_LIST,
            ToolSideEffect.READ_ONLY),
        entry(
            BaseToolIds.MCP_CALL_TOOL,
            "mcp_call_tool",
            EnvironmentCapabilityIds.MCP_CALL,
            ToolSideEffect.NON_IDEMPOTENT));
  }

  private static Entry entry(
      AgentToolId id,
      String name,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect) {
    EnvironmentCapabilityDescriptor capability = EnvironmentCapabilityCatalog.require(capabilityId);
    return new Entry(
        new AgentToolDefinition(
            id,
            descriptor(name, sideEffect, capability),
            ToolVisibility.SELECTABLE,
            AgentToolBackend.ENVIRONMENT_CAPABILITY),
        capability);
  }

  private static ToolDescriptor descriptor(
      String name, ToolSideEffect sideEffect, EnvironmentCapabilityDescriptor capability) {
    return new ToolDescriptor(
        name,
        "1",
        ToolType.ENVIRONMENT,
        loadPrompt(name),
        name,
        capability.inputSchema(),
        sideEffect,
        capability.timeout());
  }

  static Map<AgentToolId, Entry> indexById(List<Entry> entries) {
    Objects.requireNonNull(entries, "entries");
    Map<AgentToolId, Entry> result = new LinkedHashMap<>();
    for (Entry entry : entries) {
      Objects.requireNonNull(entry, "entries[]");
      if (result.putIfAbsent(entry.definition().id(), entry) != null) {
        throw new IllegalStateException(
            "duplicate Environment tool catalog id: " + entry.definition().id());
      }
    }
    return Map.copyOf(result);
  }

  static Map<String, Entry> indexByName(List<Entry> entries) {
    Objects.requireNonNull(entries, "entries");
    Map<String, Entry> result = new LinkedHashMap<>();
    for (Entry entry : entries) {
      Objects.requireNonNull(entry, "entries[]");
      String name = entry.definition().descriptor().name();
      if (result.putIfAbsent(name, entry) != null) {
        throw new IllegalStateException("duplicate Environment tool catalog name: " + name);
      }
    }
    return Map.copyOf(result);
  }

  private static List<ToolDescriptor> descriptorsOf(List<Entry> entries) {
    return entries.stream().map(entry -> entry.definition().descriptor()).toList();
  }

  private static String loadPrompt(String name) {
    return loadText(name + ".md", "prompt").trim();
  }

  private static String loadText(String fileName, String kind) {
    Objects.requireNonNull(fileName, "fileName");
    String resource = RESOURCE_PREFIX + fileName;
    try (InputStream input = EnvironmentToolCatalog.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException(
            "missing Environment tool " + kind + " resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(
          "failed to load Environment tool " + kind + ": " + resource, error);
    }
  }
}
