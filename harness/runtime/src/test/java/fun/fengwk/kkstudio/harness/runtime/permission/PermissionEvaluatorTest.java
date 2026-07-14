package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionEvaluatorTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PermissionEvaluator evaluator =
      new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer());

  /** 最后命中规则生效，且 tool-specific 必须在全局规则之后应用。 */
  @Test
  void appliesGlobalThenToolSpecificRulesWithLastMatchWinning() {
    WorkspaceToolSettings settings =
        settings(
            Map.of(
                "*",
                List.of(new PermissionRule("*", PermissionAction.DENY)),
                "write",
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/*.java", PermissionAction.ALLOW))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluate("write", "{\"path\":\"src/Main.java\"}", settings).action());
    assertEquals(
        PermissionAction.ASK, evaluate("write", "{\"path\":\"README.md\"}", settings).action());
  }

  /** path 候选同时保留 raw、workdir-relative、workspace-relative 与规范绝对展示路径。 */
  @Test
  void buildsPathCandidatesRelativeToExplicitWorkdirAndWorkspace() throws Exception {
    Path workspace = Path.of("/tmp/permission-workspace");
    Path workdir = workspace.resolve("repo");
    PermissionEvaluationContext context =
        new PermissionEvaluationContext(
            "write", "{}", workdir, workspace, WorkspaceToolSettings.DEFAULT);
    JsonNode input = objectMapper.readTree("{\"path\":\"src/Main.java\"}");

    assertEquals(
        List.of(
            "src/Main.java", "repo/src/Main.java", "/tmp/permission-workspace/repo/src/Main.java"),
        evaluator.buildPathCandidates("src/Main.java", workdir, workspace));
    assertEquals(
        List.of(
            "src/Main.java", "repo/src/Main.java", "/tmp/permission-workspace/repo/src/Main.java"),
        evaluator.describeCandidates(input, context));
  }

  /** 显式 workdir 决定 path 解析，generic Tool 使用单一 `*` 候选。 */
  @Test
  void matchesExplicitWorkdirAndGenericCandidate() {
    WorkspaceToolSettings settings =
        settings(
            Map.of(
                "write",
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("repo/src/*.java", PermissionAction.ALLOW)),
                "browser",
                List.of(new PermissionRule("*", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluate("write", "{\"workdir\":\"repo\",\"path\":\"src/Main.java\"}", settings).action());
    PermissionEvaluator.Evaluation atPrefixed =
        evaluate("write", "{\"workdir\":\"@repo\",\"path\":\"src/Main.java\"}", settings);
    assertEquals(PermissionAction.ALLOW, atPrefixed.action());
    assertEquals("repo", atPrefixed.promptPreview().workdir());
    assertEquals(PermissionAction.DENY, evaluate("browser", "{}", settings).action());
  }

  /** `~`、`$HOME` 与 `${HOME}` 规则和路径都按同一 home 目录匹配。 */
  @Test
  void expandsSupportedHomeShortcuts() {
    WorkspaceToolSettings settings =
        settings(
            Map.of(
                "write",
                List.of(
                    new PermissionRule("*", PermissionAction.ALLOW),
                    new PermissionRule("$HOME/*", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.DENY,
        evaluate("write", "{\"path\":\"${HOME}/secret.txt\"}", settings).action());
    assertEquals(
        PermissionAction.DENY, evaluate("write", "{\"path\":\"~/secret.txt\"}", settings).action());
  }

  /** permission prompt 参数必须单行且最多 120 字符，无 UI 时可原样持久等待。 */
  @Test
  void createsCompactPersistentPromptPreview() {
    String command = "x".repeat(150) + "\nnext";
    PermissionEvaluator.Evaluation evaluation =
        evaluate(
            "bash",
            "{\"command\":\"" + command.replace("\n", "\\n") + "\"}",
            settings(Map.of("bash", List.of(new PermissionRule("*", PermissionAction.ASK)))));

    assertEquals(PermissionAction.ASK, evaluation.action());
    assertTrue(evaluation.promptPreview().workdir().endsWith("(default)"));
    assertEquals(120, evaluation.promptPreview().arguments().length());
    assertTrue(evaluation.promptPreview().arguments().endsWith("..."));
    assertEquals(
        "{\"path\":\"README.md\"}",
        evaluate(
                "write",
                " { \"path\" : \"README.md\" } ",
                settings(Map.of("write", List.of(new PermissionRule("*", PermissionAction.ASK)))))
            .promptPreview()
            .arguments());
  }

  /** 管理输入兼容 PiBase 简写，持久输出统一为 ordered rule list。 */
  @Test
  void canonicalizesWorkspaceSettingsShorthand() throws Exception {
    WorkspaceToolSettingsCodec codec = new WorkspaceToolSettingsCodec(objectMapper);
    String canonical =
        codec.canonicalize(
            "{\"permission\":{\"write\":\"ask\",\"bash\":{\"*\":\"ask\",\"git"
                + " *\":\"allow\"}},\"defaultYolo\":true}");
    JsonNode root = objectMapper.readTree(canonical);

    assertTrue(root.path("defaultYolo").asBoolean());
    assertTrue(root.path("permission").path("write").isArray());
    assertEquals("*", root.path("permission").path("write").get(0).path("pattern").asText());
    assertEquals("git *", root.path("permission").path("bash").get(1).path("pattern").asText());
    assertEquals(PermissionAction.ALLOW, codec.decode(canonical).rulesFor("bash").get(1).action());

    JsonNode global = objectMapper.readTree(codec.canonicalize("{\"permission\":\"ask\"}"));
    assertEquals("*", global.path("permission").path("*").get(0).path("pattern").asText());
    assertEquals("ask", global.path("permission").path("*").get(0).path("action").asText());
  }

  private PermissionEvaluator.Evaluation evaluate(
      String toolName, String arguments, WorkspaceToolSettings settings) {
    return evaluator.evaluate(
        new PermissionEvaluationContext(
            toolName,
            arguments,
            Path.of("/tmp/permission-workspace"),
            Path.of("/tmp/permission-workspace"),
            settings));
  }

  private static WorkspaceToolSettings settings(Map<String, List<PermissionRule>> rules) {
    return new WorkspaceToolSettings(rules, false);
  }
}
