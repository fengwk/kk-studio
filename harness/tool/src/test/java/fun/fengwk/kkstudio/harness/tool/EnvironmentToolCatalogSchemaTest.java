package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Environment daemon 固定 11 工具 catalog 的 schema 契约测试。 */
class EnvironmentToolCatalogSchemaTest {

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

  private static void assertTool(String name, Set<String> required, Set<String> optional) {
    ToolDescriptor descriptor = EnvironmentToolCatalog.require(name);
    ToolParamsSchema schema = descriptor.inputSchema();
    assertEquals(required, schema.required(), name + " required keys");
    Set<String> expectedProperties = new HashSet<>(required);
    expectedProperties.addAll(optional);
    assertEquals(expectedProperties, schema.properties().keySet(), name + " declared keys");
    assertFalse(schema.additionalProperties(), name + " must reject unknown keys");
  }
}
