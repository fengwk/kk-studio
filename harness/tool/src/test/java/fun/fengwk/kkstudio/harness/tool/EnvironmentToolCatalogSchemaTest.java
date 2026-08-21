package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolSchemaElement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Environment daemon 固定 11 工具 catalog 的 schema 契约测试。 */
class EnvironmentToolCatalogSchemaTest {

  private static final List<String> CODING_TOOLS =
      List.of(
          "read",
          "write",
          "edit",
          "bash",
          "grep",
          "find",
          "lsp_goto_definition",
          "lsp_workspace_symbols",
          "lsp_java_decompile");
  private static final String PROMPT_RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/harness/tool/environment/prompts/";

  /** 目录必须恰好是 Daemon 侧的 9 个 coding tool 加 2 个 MCP 桥接工具，不得新增其它工具。 */
  @Test
  void exposesExactlyTheElevenDaemonTools() {
    assertEquals(11, EnvironmentToolCatalog.descriptors().size());
    assertEquals(
        List.of(
            "read",
            "write",
            "edit",
            "bash",
            "grep",
            "find",
            "lsp_goto_definition",
            "lsp_workspace_symbols",
            "lsp_java_decompile",
            "mcp_list_tools",
            "mcp_call_tool"),
        EnvironmentToolCatalog.descriptors().stream().map(ToolDescriptor::name).toList());
    assertThrows(
        IllegalArgumentException.class, () -> EnvironmentToolCatalog.require("apply_patch"));
  }

  /** 每个工具精确断言 required 与 optional keys；find 必须要求 pattern 和 path。 */
  @Test
  void everyDaemonToolDeclaresExactRequiredAndOptionalKeys() {
    assertTool("read", Set.of("path"), Set.of("workdir", "offset", "limit"));
    assertTool("write", Set.of("path", "content"), Set.of("workdir"));
    assertTool(
        "edit", Set.of("path", "old_string", "new_string"), Set.of("replace_all", "workdir"));
    assertTool("bash", Set.of("command"), Set.of("workdir", "timeout_seconds"));
    assertTool(
        "grep",
        Set.of("pattern", "path"),
        Set.of(
            "workdir",
            "include",
            "ignore_case",
            "literal",
            "multiline",
            "limit",
            "timeout_seconds"));
    assertTool("find", Set.of("pattern", "path"), Set.of("workdir", "limit", "timeout_seconds"));
    assertTool("lsp_goto_definition", Set.of("path", "line"), Set.of("workdir", "character"));
    assertTool("lsp_workspace_symbols", Set.of("path", "query"), Set.of("workdir", "limit"));
    assertTool("lsp_java_decompile", Set.of("path", "target"), Set.of("workdir"));
    assertTool("mcp_list_tools", Set.of(), Set.of("server"));
    assertTool("mcp_call_tool", Set.of("server", "tool", "arguments"), Set.of());
  }

  /** 9 个 coding tool 的 description 必须与资源 md 原文一致（trim 后），不允许本地化改写。 */
  @Test
  void codingToolDescriptionsMatchPromptResources() {
    for (String name : CODING_TOOLS) {
      assertEquals(
          promptResource(name).trim(),
          EnvironmentToolCatalog.require(name).description(),
          name + " description must equal the prompt resource md");
    }
  }

  /** 关键字段的 description 必须是英文原文（对齐 pi-base schema），不能残留中文人读说明。 */
  @Test
  void keyFieldDescriptionsAreEnglish() {
    ToolDescriptor read = EnvironmentToolCatalog.require("read");
    assertEquals(
        "File, directory, or supported image path to read.",
        read.inputSchema().properties().get("path").description());
    assertEquals(
        "Positive integer 1-based line offset for text reads. Defaults to 1.",
        read.inputSchema().properties().get("offset").description());
    ToolDescriptor bash = EnvironmentToolCatalog.require("bash");
    assertEquals(
        "Shell command to execute.", bash.inputSchema().properties().get("command").description());
    assertEquals(
        "Positive timeout in seconds. Defaults to 120 (2 minutes). For commands that may run longer, provide a larger value.",
        bash.inputSchema().properties().get("timeout_seconds").description());
    ToolDescriptor find = EnvironmentToolCatalog.require("find");
    assertEquals(
        "Directory to search in. Required. Use '.' for the current working directory. There is no implicit default — the model must always state the search root.",
        find.inputSchema().properties().get("path").description());
    ToolDescriptor decompile = EnvironmentToolCatalog.require("lsp_java_decompile");
    assertEquals(
        "A raw `jdt://` URI, a workspace symbol output line, or a `file://` / `.class` path.",
        decompile.inputSchema().properties().get("target").description());
    ToolDescriptor mcpCall = EnvironmentToolCatalog.require("mcp_call_tool");
    assertEquals(
        "Arbitrary JSON object arguments, passed through to the MCP tool as-is.",
        mcpCall.inputSchema().properties().get("arguments").description());
  }

  /** bash 的 timeout_seconds 描述不允许出现 3600 上限文案（上限属于 daemon 侧，不属于模型可见 schema）。 */
  @Test
  void noSchemaMentionsThe3600UpperBound() {
    for (ToolDescriptor descriptor : EnvironmentToolCatalog.descriptors()) {
      assertNo3600(descriptor.inputSchema(), descriptor.name());
    }
  }

  /** find 的 path 必须参与参数校验，不只是声明。 */
  @Test
  void findRejectsCallsWithoutRequiredPath() {
    ToolDescriptor find = EnvironmentToolCatalog.require("find");
    new ToolCall("call", "find", "{\"pattern\":\"*.txt\",\"path\":\".\"}").validateFor(find);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call", "find", "{\"pattern\":\"*.txt\"}").validateFor(find));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call", "find", "{\"path\":\".\"}").validateFor(find));
  }

  private static void assertNo3600(ToolParamsSchema schema, String name) {
    StringBuilder all = new StringBuilder();
    if (schema.description() != null) {
      all.append(schema.description());
    }
    for (Object element : schema.properties().values()) {
      if (element instanceof ToolSchemaElement typed && typed.description() != null) {
        all.append(typed.description());
      }
    }
    assertFalse(all.toString().contains("3600"), name + " schema must not mention 3600");
  }

  private static void assertTool(String name, Set<String> required, Set<String> optional) {
    ToolDescriptor descriptor = EnvironmentToolCatalog.require(name);
    ToolParamsSchema schema = descriptor.inputSchema();
    assertEquals(required, schema.required(), name + " required keys");
    Set<String> expectedProperties = new HashSet<>(required);
    expectedProperties.addAll(optional);
    assertEquals(expectedProperties, schema.properties().keySet(), name + " declared keys");
    assertFalse(schema.additionalProperties(), name + " must reject unknown keys");
  }

  private static String promptResource(String name) {
    String resource = PROMPT_RESOURCE_PREFIX + name + ".md";
    try (InputStream input = EnvironmentToolCatalogSchemaTest.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read prompt resource: " + resource, error);
    }
  }
}
