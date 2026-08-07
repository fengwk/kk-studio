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
 * 每个 Environment Daemon 提供的固定工具的唯一事实来源。
 *
 * <p>Descriptor、schema 与 prompt 文本刻意定义在无依赖的 {@code harness/tool} 模块中。Core 使用该 catalog 做校验与
 * 规划，Daemon 实现则用同一组 descriptor 做注册与 wire 校验。
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
                "read 工具参数。",
                Map.of(
                    "path", new ToolStringSchema("文件或目录路径"),
                    "workdir", new ToolStringSchema("可选的、相对于 environment root 的目录"),
                    "offset", new ToolIntegerSchema("从 1 开始的行偏移量"),
                    "limit", new ToolIntegerSchema("最多读取的行数")),
                Set.of("path"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1)),
        descriptor(
            "write",
            new ToolParamsSchema(
                "write 工具参数。",
                Map.of(
                    "path", new ToolStringSchema("文件路径"),
                    "content", new ToolStringSchema("完整的替换内容"),
                    "workdir", new ToolStringSchema("可选的、相对于 environment root 的目录")),
                Set.of("path", "content"),
                false),
            ToolSideEffect.IDEMPOTENT,
            Duration.ofMinutes(1)),
        descriptor(
            "edit",
            new ToolParamsSchema(
                "edit 工具参数。",
                Map.of(
                    "path", new ToolStringSchema("文件路径"),
                    "old_string", new ToolStringSchema("要替换的精确文本"),
                    "new_string", new ToolStringSchema("替换文本"),
                    "replace_all", new ToolBooleanSchema("替换所有精确匹配"),
                    "workdir", new ToolStringSchema("可选的、相对于 environment root 的目录")),
                Set.of("path", "old_string", "new_string"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMinutes(1)),
        descriptor(
            "apply_patch",
            new ToolParamsSchema(
                "apply_patch 工具参数。",
                Map.of(
                    "patchText",
                    new ToolStringSchema(
                        "从 *** Begin Patch 到 *** End Patch 的完整 apply_patch 协议文本。可选首条指令：*** Workdir: <path>。")),
                Set.of("patchText"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMinutes(2)),
        descriptor(
            "bash",
            new ToolParamsSchema(
                "bash 工具参数。",
                Map.of(
                    "command", new ToolStringSchema("已获得 Platform 授权的 shell 命令"),
                    "workdir", new ToolStringSchema("可选的、相对于 environment root 的目录"),
                    "timeout_seconds", new ToolIntegerSchema("可选超时时间（秒）；默认 120，且不得超过 3600")),
                Set.of("command"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofHours(1)),
        descriptor(
            "grep",
            new ToolParamsSchema(
                "grep 工具参数。",
                Map.of(
                    "pattern", new ToolStringSchema("正则表达式或字面量"),
                    "path", new ToolStringSchema("要搜索的文件或目录"),
                    "workdir", new ToolStringSchema("可选的、相对于 environment root 的目录"),
                    "include", new ToolStringSchema("可选的 glob pattern"),
                    "ignore_case", new ToolBooleanSchema("忽略大小写"),
                    "literal", new ToolBooleanSchema("按字面量处理 pattern"),
                    "multiline", new ToolBooleanSchema("启用跨行 pattern"),
                    "limit", new ToolIntegerSchema("最多返回的匹配数"),
                    "timeout_seconds", new ToolIntegerSchema("搜索超时时间（秒）")),
                Set.of("pattern", "path"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofHours(1)),
        descriptor(
            "find",
            new ToolParamsSchema(
                "find 工具参数。",
                Map.of(
                    "pattern", new ToolStringSchema("glob pattern"),
                    "path", new ToolStringSchema("要搜索的目录"),
                    "workdir", new ToolStringSchema("可选的、相对于 environment root 的目录"),
                    "limit", new ToolIntegerSchema("最多返回的结果数"),
                    "timeout_seconds", new ToolIntegerSchema("搜索超时时间（秒）")),
                Set.of("pattern", "path"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofHours(1)),
        descriptor(
            "lsp_goto_definition",
            new ToolParamsSchema(
                "lsp_goto_definition 工具参数。",
                Map.of(
                    "path",
                    new ToolStringSchema("由 LSP server 支持的已有源文件路径；同时根据此路径推断 workspace root。"),
                    "workdir",
                    new ToolStringSchema("用于解析相对路径的工作目录。默认是 Agent 当前工作目录；提供后从该目录解析相对路径。"),
                    "line",
                    new ToolIntegerSchema("目标位置的行号，从 1 开始计数。"),
                    "character",
                    new ToolIntegerSchema("目标位置的字符偏移，从 0 开始计数；默认值为 0。")),
                Set.of("path", "line"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)),
        descriptor(
            "lsp_workspace_symbols",
            new ToolParamsSchema(
                "lsp_workspace_symbols 工具参数。",
                Map.of(
                    "path",
                    new ToolStringSchema("用于推断 workspace root 的已有源文件路径。"),
                    "workdir",
                    new ToolStringSchema("用于解析相对路径的工作目录。默认是 Agent 当前工作目录；提供后从该目录解析相对路径。"),
                    "query",
                    new ToolStringSchema("符号搜索 query。"),
                    "limit",
                    new ToolIntegerSchema("本地最多展示的结果数；默认值为 50。")),
                Set.of("path", "query"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)),
        descriptor(
            "lsp_java_decompile",
            new ToolParamsSchema(
                "lsp_java_decompile 工具参数。",
                Map.of(
                    "path",
                    new ToolStringSchema(
                        "目标 workspace 中的任意本地 `.java` 文件。根据此路径推断 workspace root 并定位 JDTLS。"),
                    "workdir",
                    new ToolStringSchema("用于解析相对路径的工作目录。默认是 Agent 当前工作目录；提供后从该目录解析相对路径。"),
                    "target",
                    new ToolStringSchema(
                        "原始 `jdt://` URI、workspace symbol 输出行，或 `file://` / `.class` 路径。")),
                Set.of("path", "target"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)));
  }

  private static ToolDescriptor descriptor(
      String name, ToolParamsSchema inputSchema, ToolSideEffect sideEffect, Duration timeout) {
    return new ToolDescriptor(
        name, "1", ToolType.ENVIRONMENT, loadPrompt(name), name, inputSchema, sideEffect, timeout);
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
