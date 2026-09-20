package fun.fengwk.kkstudio.harness.builtin.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Optional;

/**
 * {@link EnvironmentCapabilityRenderer} 历史动作渲染器的单元测试。
 *
 * <p>验证环境能力动作、核心目标/路径/模式提取，省略 timeout、limit 等执行控制参数， 以及环境名后缀追加、缺失核心字段与畸形输入安全回退和纯函数确定性。
 */
class EnvironmentCapabilityRendererTest {

  private static ToolHistoryRenderRequest request(String toolName, String argumentsJson) {
    return new ToolHistoryRenderRequest(new ToolCall("call-1", toolName, argumentsJson), null);
  }

  private static ToolHistoryRenderRequest request(
      String toolName, String argumentsJson, String environmentName) {
    return new ToolHistoryRenderRequest(
        new ToolCall("call-1", toolName, argumentsJson), environmentName);
  }

  private static ToolHistoryRenderRequest malformedRequest(String toolName, String argumentsJson) {
    ToolCall call = mock(ToolCall.class);
    when(call.id()).thenReturn("call-1");
    when(call.toolName()).thenReturn(toolName);
    when(call.argumentsJson()).thenReturn(argumentsJson);
    return new ToolHistoryRenderRequest(call, null);
  }

  /** 验证 FS_READ 动作渲染：保留 path 与 workdir 作用域，并省略 offset、limit 等结果窗口参数。 */
  @Test
  void fsReadRendersPathAndOmitsExecutionControlParameters() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_READ);

    Optional<String> action = renderer.render(request("fs_read", "{\"path\":\"src/App.java\"}"));
    assertEquals(Optional.of("read src/App.java"), action);

    Optional<String> actionWithControls =
        renderer.render(
            request(
                "fs_read",
                "{\"path\":\"src/App.java\",\"offset\":10,\"limit\":50,\"workdir\":\"/tmp\"}"));
    assertEquals(Optional.of("read src/App.java from /tmp"), actionWithControls);
  }

  /** 验证 FS_WRITE 动作渲染：保留 path 与 workdir 作用域，并省略已由结果确认的 content。 */
  @Test
  void fsWriteRendersPathAndOmitsExecutionControlParameters() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_WRITE);

    Optional<String> action = renderer.render(request("fs_write", "{\"path\":\"docs/README.md\"}"));
    assertEquals(Optional.of("write docs/README.md"), action);

    Optional<String> actionWithControls =
        renderer.render(
            request(
                "fs_write",
                "{\"path\":\"docs/README.md\",\"content\":\"# Hello\",\"workdir\":\"/tmp\"}"));
    assertEquals(Optional.of("write docs/README.md from /tmp"), actionWithControls);
  }

  /**
   * 验证 FS_EDIT 动作渲染：在 replace_all 为 true 时渲染为 "replace every occurrence in " + path， 在缺省或为 false
   * 时渲染为 "edit " + path，且绝不包含 old_string、new_string 等具体编辑内容。
   */
  @Test
  void fsEditRendersPathAndDistinguishesReplaceAll() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_EDIT);

    // 缺省 replace_all
    Optional<String> defaultEdit =
        renderer.render(
            request(
                "fs_edit",
                "{\"path\":\"src/Main.java\",\"old_string\":\"foo\",\"new_string\":\"bar\"}"));
    assertEquals(Optional.of("edit src/Main.java"), defaultEdit);

    // 显式 replace_all = false
    Optional<String> explicitFalse =
        renderer.render(
            request(
                "fs_edit",
                "{\"path\":\"src/Main.java\",\"replace_all\":false,\"old_string\":\"foo\",\"new_string\":\"bar\"}"));
    assertEquals(Optional.of("edit src/Main.java"), explicitFalse);

    // 显式 replace_all = true
    Optional<String> replaceAll =
        renderer.render(
            request(
                "fs_edit",
                "{\"path\":\"src/Main.java\",\"replace_all\":true,\"old_string\":\"foo\",\"new_string\":\"bar\"}"));
    assertEquals(Optional.of("replace every occurrence in src/Main.java"), replaceAll);
  }

  /** 验证 PROCESS_EXEC 动作渲染：保留 command 与 workdir 作用域，并省略 timeout_seconds。 */
  @Test
  void processExecRendersCommandAndOmitsExecutionControlParameters() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.PROCESS_EXEC);

    Optional<String> action =
        renderer.render(request("process_exec", "{\"command\":\"mvn clean test\"}"));
    assertEquals(Optional.of("run command mvn clean test"), action);

    Optional<String> actionWithControls =
        renderer.render(
            request(
                "process_exec",
                "{\"command\":\"mvn clean test\",\"timeout_seconds\":120,\"workdir\":\"/app\"}"));
    assertEquals(Optional.of("run command mvn clean test from /app"), actionWithControls);
  }

  /**
   * 验证 FS_GREP 动作渲染：保留 pattern，按严格顺序追加字面量 (" as literal text")、忽略大小写 (" ignoring case")、多行 ("
   * across lines") 和路径 (" in " + path)，并省略 include、limit、timeout_seconds 等执行控制参数。
   */
  @Test
  void fsGrepRendersPatternAndSemanticFlagsInStrictOrder() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_GREP);

    // 仅 pattern
    assertEquals(
        Optional.of("search for TODO"),
        renderer.render(request("fs_grep", "{\"pattern\":\"TODO\"}")));

    // pattern + literal
    assertEquals(
        Optional.of("search for TODO as literal text"),
        renderer.render(request("fs_grep", "{\"pattern\":\"TODO\",\"literal\":true}")));

    // pattern + ignore_case
    assertEquals(
        Optional.of("search for TODO ignoring case"),
        renderer.render(request("fs_grep", "{\"pattern\":\"TODO\",\"ignore_case\":true}")));

    // pattern + multiline
    assertEquals(
        Optional.of("search for TODO across lines"),
        renderer.render(request("fs_grep", "{\"pattern\":\"TODO\",\"multiline\":true}")));

    // pattern + path
    assertEquals(
        Optional.of("search for TODO in src"),
        renderer.render(request("fs_grep", "{\"pattern\":\"TODO\",\"path\":\"src\"}")));

    // 全部语义标志与 include 作用域按严格顺序保留，同时忽略 limit/timeout_seconds
    assertEquals(
        Optional.of(
            "search for TODO as literal text ignoring case across lines in src within files matching *.java"),
        renderer.render(
            request(
                "fs_grep",
                "{\"pattern\":\"TODO\",\"literal\":true,\"ignore_case\":true,\"multiline\":true,\"path\":\"src\",\"include\":\"*.java\",\"limit\":100,\"timeout_seconds\":30}")));

    // 显式为 false 的语义标志不被追加
    assertEquals(
        Optional.of("search for TODO"),
        renderer.render(
            request(
                "fs_grep",
                "{\"pattern\":\"TODO\",\"literal\":false,\"ignore_case\":false,\"multiline\":false}")));
  }

  /**
   * 验证 FS_FIND 动作渲染：path 为空时渲染为 "find files matching " + pattern， path 存在时渲染为 "find " + pattern + "
   * under " + path，且省略 limit、timeout_seconds 等参数。
   */
  @Test
  void fsFindRendersPatternAndDistinguishesPath() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_FIND);

    // 无 path
    assertEquals(
        Optional.of("find files matching *.ts"),
        renderer.render(request("fs_find", "{\"pattern\":\"*.ts\"}")));

    // 有 path
    assertEquals(
        Optional.of("find *.ts under src"),
        renderer.render(request("fs_find", "{\"pattern\":\"*.ts\",\"path\":\"src\"}")));

    // 包含执行控制参数
    assertEquals(
        Optional.of("find *.ts under src"),
        renderer.render(
            request(
                "fs_find",
                "{\"pattern\":\"*.ts\",\"path\":\"src\",\"limit\":200,\"timeout_seconds\":10}")));
  }

  /**
   * 验证 LSP_GOTO_DEFINITION 动作渲染：以 "go to the definition at " 为前缀，保留 path，并省略 line、character 等位置细节。
   */
  @Test
  void lspGotoDefinitionRendersPathAndOmitsPositionParameters() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION);

    assertEquals(
        Optional.of("go to the definition at src/App.java"),
        renderer.render(request("lsp_goto_definition", "{\"path\":\"src/App.java\"}")));

    assertEquals(
        Optional.of("go to the definition at src/App.java"),
        renderer.render(
            request(
                "lsp_goto_definition",
                "{\"path\":\"src/App.java\",\"line\":42,\"character\":10}")));
  }

  /**
   * 验证 LSP_WORKSPACE_SYMBOLS 动作渲染：以 "search workspace symbols for " 为前缀，保留 query，并省略 path、limit
   * 等参数。
   */
  @Test
  void lspWorkspaceSymbolsRendersQueryAndOmitsExecutionControlParameters() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS);

    assertEquals(
        Optional.of("search workspace symbols for UserService"),
        renderer.render(request("lsp_workspace_symbols", "{\"query\":\"UserService\"}")));

    assertEquals(
        Optional.of("search workspace symbols for UserService"),
        renderer.render(
            request(
                "lsp_workspace_symbols",
                "{\"query\":\"UserService\",\"path\":\"src/App.java\",\"limit\":20}")));
  }

  /** 验证 LSP_JAVA_DECOMPILE 动作渲染：以 "decompile " 为前缀，保留 target，并省略 path 等定位宿主参数。 */
  @Test
  void lspJavaDecompileRendersTargetAndOmitsPath() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);

    String target = "jdt://contents/demo/MyClass.class";
    assertEquals(
        Optional.of("decompile " + target),
        renderer.render(request("lsp_java_decompile", "{\"target\":\"" + target + "\"}")));

    assertEquals(
        Optional.of("decompile " + target),
        renderer.render(
            request(
                "lsp_java_decompile",
                "{\"target\":\"" + target + "\",\"path\":\"src/App.java\"}")));
  }

  /** 验证在非 null environmentName 存在时，动作末尾必须追加 " in environment " + environmentName；为 null 时不追加。 */
  @Test
  void environmentNameAppendedWhenPresent() {
    EnvironmentCapabilityRenderer readRenderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_READ);
    assertEquals(
        Optional.of("read src/App.java in environment dev"),
        readRenderer.render(request("fs_read", "{\"path\":\"src/App.java\"}", "dev")));

    EnvironmentCapabilityRenderer editRenderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_EDIT);
    assertEquals(
        Optional.of("replace every occurrence in src/App.java in environment staging"),
        editRenderer.render(
            request("fs_edit", "{\"path\":\"src/App.java\",\"replace_all\":true}", "staging")));

    EnvironmentCapabilityRenderer execRenderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.PROCESS_EXEC);
    assertEquals(
        Optional.of("run command ./test.sh in environment container-1"),
        execRenderer.render(request("process_exec", "{\"command\":\"./test.sh\"}", "container-1")));

    EnvironmentCapabilityRenderer grepRenderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_GREP);
    assertEquals(
        Optional.of("search for err as literal text in logs in environment prod"),
        grepRenderer.render(
            request(
                "fs_grep", "{\"pattern\":\"err\",\"literal\":true,\"path\":\"logs\"}", "prod")));

    EnvironmentCapabilityRenderer findRenderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_FIND);
    assertEquals(
        Optional.of("find files matching *.xml in environment staging"),
        findRenderer.render(request("fs_find", "{\"pattern\":\"*.xml\"}", "staging")));
    assertEquals(
        Optional.of("find *.xml under config in environment staging"),
        findRenderer.render(
            request("fs_find", "{\"pattern\":\"*.xml\",\"path\":\"config\"}", "staging")));
  }

  /** 验证当核心字段缺失、为 null、为空白字符串或为非文本类型时，所有环境能力渲染器均安全回退返回 Optional.empty()。 */
  @Test
  void returnsEmptyWhenCoreFieldsMissingOrBlank() {
    // FS_READ: 缺失 path
    EnvironmentCapabilityRenderer read =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_READ);
    assertEquals(Optional.empty(), read.render(request("fs_read", "{}")));
    assertEquals(Optional.empty(), read.render(request("fs_read", "{\"path\":\"\"}")));
    assertEquals(Optional.empty(), read.render(request("fs_read", "{\"path\":\"   \"}")));
    assertEquals(Optional.empty(), read.render(request("fs_read", "{\"path\":123}")));
    assertEquals(Optional.empty(), read.render(request("fs_read", "{\"path\":null}")));

    // FS_WRITE: 缺失 path
    EnvironmentCapabilityRenderer write =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_WRITE);
    assertEquals(Optional.empty(), write.render(request("fs_write", "{}")));
    assertEquals(Optional.empty(), write.render(request("fs_write", "{\"path\":\"  \"}")));
    assertEquals(Optional.empty(), write.render(request("fs_write", "{\"path\":false}")));

    // FS_EDIT: 缺失 path
    EnvironmentCapabilityRenderer edit =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_EDIT);
    assertEquals(Optional.empty(), edit.render(request("fs_edit", "{}")));
    assertEquals(Optional.empty(), edit.render(request("fs_edit", "{\"replace_all\":true}")));
    assertEquals(Optional.empty(), edit.render(request("fs_edit", "{\"path\":\"  \"}")));

    // PROCESS_EXEC: 缺失 command
    EnvironmentCapabilityRenderer exec =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.PROCESS_EXEC);
    assertEquals(Optional.empty(), exec.render(request("process_exec", "{}")));
    assertEquals(Optional.empty(), exec.render(request("process_exec", "{\"command\":\"  \"}")));
    assertEquals(Optional.empty(), exec.render(request("process_exec", "{\"command\":999}")));

    // FS_GREP: 缺失 pattern（即使有 path 也无法形成 grep 动作）
    EnvironmentCapabilityRenderer grep =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_GREP);
    assertEquals(Optional.empty(), grep.render(request("fs_grep", "{}")));
    assertEquals(Optional.empty(), grep.render(request("fs_grep", "{\"path\":\"src\"}")));
    assertEquals(Optional.empty(), grep.render(request("fs_grep", "{\"pattern\":\"  \"}")));
    assertEquals(Optional.empty(), grep.render(request("fs_grep", "{\"pattern\":true}")));

    // FS_FIND: 缺失 pattern
    EnvironmentCapabilityRenderer find =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_FIND);
    assertEquals(Optional.empty(), find.render(request("fs_find", "{}")));
    assertEquals(Optional.empty(), find.render(request("fs_find", "{\"path\":\"src\"}")));
    assertEquals(Optional.empty(), find.render(request("fs_find", "{\"pattern\":\"  \"}")));

    // LSP_GOTO_DEFINITION: 缺失 path
    EnvironmentCapabilityRenderer gotoDef =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION);
    assertEquals(Optional.empty(), gotoDef.render(request("lsp_goto_definition", "{}")));
    assertEquals(
        Optional.empty(), gotoDef.render(request("lsp_goto_definition", "{\"path\":\"  \"}")));

    // LSP_WORKSPACE_SYMBOLS: 缺失 query
    EnvironmentCapabilityRenderer sym =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS);
    assertEquals(Optional.empty(), sym.render(request("lsp_workspace_symbols", "{}")));
    assertEquals(
        Optional.empty(), sym.render(request("lsp_workspace_symbols", "{\"query\":\"  \"}")));

    // LSP_JAVA_DECOMPILE: 缺失 target
    EnvironmentCapabilityRenderer decompile =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);
    assertEquals(Optional.empty(), decompile.render(request("lsp_java_decompile", "{}")));
    assertEquals(
        Optional.empty(), decompile.render(request("lsp_java_decompile", "{\"target\":\"  \"}")));
  }

  /** 验证当 arguments 文本是畸形 JSON、非 JSON 对象（数组/标量）或 null 时，渲染器安全返回 Optional.empty()。 */
  @Test
  void returnsEmptyOnMalformedJsonOrNonObjectArguments() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_READ);

    assertEquals(
        Optional.empty(), renderer.render(malformedRequest("fs_read", "{invalid json format")));
    assertEquals(Optional.empty(), renderer.render(malformedRequest("fs_read", "[1, 2, 3]")));
    assertEquals(
        Optional.empty(), renderer.render(malformedRequest("fs_read", "\"scalar string\"")));
    assertEquals(Optional.empty(), renderer.render(malformedRequest("fs_read", "12345")));
    assertEquals(Optional.empty(), renderer.render(malformedRequest("fs_read", null)));
  }

  /** 验证未知 capabilityId 时安全返回 Optional.empty()。 */
  @Test
  void returnsEmptyForUnknownCapabilityId() {
    EnvironmentCapabilityRenderer unknown =
        EnvironmentCapabilityRenderer.of(new EnvironmentCapabilityId("custom.unsupported"));

    Optional<String> result =
        unknown.render(request("custom_tool", "{\"path\":\"some/file.txt\"}"));
    assertEquals(Optional.empty(), result);
  }

  /** 验证渲染器为确定性纯函数：多次调用相同输入恒定返回相同结果。 */
  @Test
  void deterministicPureFunction() {
    EnvironmentCapabilityRenderer renderer =
        EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_READ);
    ToolHistoryRenderRequest req = request("fs_read", "{\"path\":\"src/App.java\"}", "dev");

    Optional<String> first = renderer.render(req);
    Optional<String> second = renderer.render(req);

    assertTrue(first.isPresent());
    assertEquals(first, second);
    assertEquals(Optional.of("read src/App.java in environment dev"), first);
  }
}
