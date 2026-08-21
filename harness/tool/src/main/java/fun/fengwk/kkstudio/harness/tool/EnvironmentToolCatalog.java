package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

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

/**
 * The single source of truth for the fixed tools provided by every Environment Daemon.
 *
 * <p>Descriptor, schema and prompt text are deliberately defined in the dependency-free {@code
 * harness/tool} module. Core uses the catalog for validation and planning, while Daemon
 * implementations use the same descriptors for registration and wire checks.
 */
public final class EnvironmentToolCatalog {

  public static final String VERSION = "2";

  private static final String RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/harness/tool/environment/prompts/";
  private static final ToolDescriptorJsonCodec CODEC = new ToolDescriptorJsonCodec();
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
        descriptor("read", ToolSideEffect.READ_ONLY, Duration.ofMinutes(1)),
        descriptor("write", ToolSideEffect.IDEMPOTENT, Duration.ofMinutes(1)),
        descriptor("edit", ToolSideEffect.NON_IDEMPOTENT, Duration.ofMinutes(1)),
        descriptor("bash", ToolSideEffect.NON_IDEMPOTENT, Duration.ofHours(1)),
        descriptor("grep", ToolSideEffect.READ_ONLY, Duration.ofHours(1)),
        descriptor("find", ToolSideEffect.READ_ONLY, Duration.ofHours(1)),
        descriptor("lsp_goto_definition", ToolSideEffect.READ_ONLY, Duration.ofMinutes(2)),
        descriptor("lsp_workspace_symbols", ToolSideEffect.READ_ONLY, Duration.ofMinutes(2)),
        descriptor("lsp_java_decompile", ToolSideEffect.READ_ONLY, Duration.ofMinutes(2)),
        descriptor("mcp_list_tools", ToolSideEffect.READ_ONLY, Duration.ofSeconds(30)),
        descriptor("mcp_call_tool", ToolSideEffect.NON_IDEMPOTENT, Duration.ofMinutes(5)));
  }

  private static ToolDescriptor descriptor(
      String name, ToolSideEffect sideEffect, Duration timeout) {
    return new ToolDescriptor(
        name,
        "1",
        ToolType.ENVIRONMENT,
        loadPrompt(name),
        name,
        loadSchema(name),
        sideEffect,
        timeout);
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
    return loadText(name + ".md", "prompt").trim();
  }

  private static ToolParamsSchema loadSchema(String name) {
    return CODEC.decodeInputSchema(loadText(name + ".schema.json", "schema"));
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
