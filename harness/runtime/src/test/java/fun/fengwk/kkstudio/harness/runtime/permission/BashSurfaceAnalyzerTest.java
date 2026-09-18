package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class BashSurfaceAnalyzerTest {
  private static final String BASH = "bash";
  private static final String CUSTOM_BASH = "custom_exec";

  private final BashSurfaceAnalyzer analyzer = new BashSurfaceAnalyzer();
  private final PermissionEvaluator evaluator =
      new PermissionEvaluator(new ObjectMapper(), analyzer);

  /** 顶层 composite/background 被拆分，quote、escape 与 comment 内操作符不拆分。 */
  @Test
  void splitsOnlyStaticTopLevelSegments() {
    assertEquals(
        List.of("pwd", "ls -ld .", "touch a.py"),
        analyzer.analyze("pwd && ls -ld . && touch a.py").segments());
    assertEquals(
        List.of("sleep 1", "rm -rf tmp"), analyzer.analyze("sleep 1 & rm -rf tmp").segments());
    assertEquals(
        List.of("echo 'a && b; c | d'", "echo a\\;b", "grep a"),
        analyzer.analyze("echo 'a && b; c | d' && echo a\\;b | grep a").segments());
    assertEquals(
        List.of("echo ok", "printf done"),
        analyzer.analyze("echo ok # ignore && rm\nprintf done").segments());
  }

  /** assignment 前缀与静态 quoted/escaped/path executable 都生成真实命令候选。 */
  @Test
  void buildsAssignmentAndStaticExecutableCandidates() {
    List<String> assigned = analyzer.buildCandidates("NODE_ENV=test DEBUG=pi-base npm test");
    assertTrue(assigned.contains("npm *"));
    assertTrue(assigned.contains("npm test"));

    for (String command :
        List.of("r'm' -rf tmp", "'r''m' -rf tmp", "r\\m -rf tmp", "/bin/rm -rf tmp")) {
      assertTrue(analyzer.buildCandidates(command).contains("rm *"), command);
    }
  }

  /** quoted heredoc 保持 literal；可展开 heredoc、substitution 与动态 shell surface 均 unsupported。 */
  @Test
  void classifiesHeredocsSubstitutionsAndDynamicWrappers() {
    assertTrue(analyzer.analyze("cat <<'EOF'\n$(rm -rf tmp)\nEOF").supported());
    for (String command :
        List.of(
            "cat <<EOF\n$(rm -rf tmp)\nEOF",
            "echo \"$(rm -rf tmp)\"",
            "diff <(rm -rf tmp) expected.txt",
            "bash -c \"$CMD\"",
            "env -u X bash -c \"$CMD\"",
            "exec rm -rf tmp",
            "nohup rm -rf tmp",
            "eval \"$NEXT_COMMAND\"",
            "source script.sh",
            ". script.sh",
            "alias rm='rm -i'",
            "$CMD -rf tmp",
            "(rm -rf tmp)",
            ">/dev/null rm -rf tmp",
            "if true; then rm -rf tmp; fi")) {
      assertFalse(analyzer.analyze(command).supported(), command);
    }
  }

  /** unsupported 只在完整命令规则明确 deny 时拒绝，否则必须 ASK，不能猜内部命令。 */
  @Test
  void unsupportedIsDeniedOnlyByCompleteCommandMatch() {
    ToolSettings ask =
        settings(
            List.of(
                new PermissionRule("*", PermissionAction.ASK),
                new PermissionRule("bash *", PermissionAction.ALLOW),
                new PermissionRule("echo *", PermissionAction.ALLOW),
                new PermissionRule("eval \"$NEXT_COMMAND\"", PermissionAction.DENY)));

    assertEquals(PermissionAction.ASK, evaluate("bash -c \"$REAL_BASH\"", ask));
    assertEquals(PermissionAction.ASK, evaluate("echo \"$(rm -rf tmp)\"", ask));
    assertEquals(PermissionAction.ALLOW, evaluate("echo '$(rm -rf tmp)'", ask));
    assertEquals(PermissionAction.DENY, evaluate("eval \"$NEXT_COMMAND\"", ask));
  }

  /** 任一静态 segment deny 立即 deny，否则 ask 优先于 allow。 */
  @Test
  void combinesCompositeSegmentActions() {
    ToolSettings settings =
        settings(
            List.of(
                new PermissionRule("*", PermissionAction.ASK),
                new PermissionRule("pwd *", PermissionAction.ALLOW),
                new PermissionRule("ls *", PermissionAction.ALLOW),
                new PermissionRule("sleep *", PermissionAction.ALLOW),
                new PermissionRule("rm *", PermissionAction.DENY)));

    assertEquals(PermissionAction.ASK, evaluate("pwd && ls -ld . && touch a.py", settings));
    assertEquals(PermissionAction.DENY, evaluate("sleep 1 & rm -rf tmp", settings));
  }

  /** 只要参数包含 textual command 字段，无论具体 tool name 为何均应用 command surface 分析。 */
  @Test
  void appliesCommandSurfaceAnalysisWheneverCommandFieldIsTextual() {
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                CUSTOM_BASH,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("echo *", PermissionAction.ALLOW),
                    new PermissionRule("rm *", PermissionAction.DENY)),
                BASH,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("echo *", PermissionAction.ALLOW),
                    new PermissionRule("rm *", PermissionAction.DENY))),
            false);
    String arguments = "{\"command\":\"echo ok && rm -rf tmp\",\"workdir\":\"/tmp/environment\"}";

    assertEquals(
        PermissionAction.DENY,
        evaluator.evaluate(new PermissionEvaluationContext(BASH, arguments, settings)).action());
    assertEquals(
        PermissionAction.DENY,
        evaluator
            .evaluate(new PermissionEvaluationContext(CUSTOM_BASH, arguments, settings))
            .action());
  }

  /** malformed surface ASK；若完整命令被 wildcard deny，则仍然确定性 DENY。 */
  @Test
  void handlesMalformedSurfaceConservatively() {
    assertEquals(
        PermissionAction.ASK,
        evaluate(
            "echo 'unterminated",
            settings(
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("echo *", PermissionAction.ALLOW)))));
    assertEquals(
        PermissionAction.DENY,
        evaluate(
            "echo 'unterminated",
            settings(List.of(new PermissionRule("*", PermissionAction.DENY)))));
  }

  private PermissionAction evaluate(String command, ToolSettings settings) {
    String arguments = "{\"command\":" + quote(command) + ",\"workdir\":\"/tmp/environment\"}";
    return evaluator.evaluate(new PermissionEvaluationContext(BASH, arguments, settings)).action();
  }

  private String quote(String value) {
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static ToolSettings settings(List<PermissionRule> bashRules) {
    return new ToolSettings(Map.of(BASH, bashRules), false);
  }
}
