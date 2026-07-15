package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionContractsTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ToolSettingsCodec codec = new ToolSettingsCodec(objectMapper);
  private final PermissionEvaluator evaluator =
      new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer());

  /** codec 接受 canonical list、null permission 和未知字段，并拒绝所有非规范类型。 */
  @Test
  void validatesCanonicalToolSettingsShapes() {
    String canonical =
        codec.canonicalize(
            "{\"other\":1,\"permission\":{\"bash\":[{\"pattern\":\"git"
                + " ?\",\"action\":\"allow\"}]}}");
    assertTrue(canonical.contains("\"other\":1"));
    assertEquals(PermissionAction.ALLOW, codec.decode(canonical).rulesFor("bash").get(0).action());
    assertFalse(codec.decode("{\"permission\":null}").defaultYolo());

    for (String invalid :
        List.of(
            "[]",
            "not-json",
            "{\"defaultYolo\":1}",
            "{\"permission\":\"invalid\"}",
            "{\"permission\":[]}",
            "{\"permission\":{\"bash\":1}}",
            "{\"permission\":{\"bash\":{\"*\":1}}}",
            "{\"permission\":{\"bash\":[{}]}}",
            "{\"permission\":{\"bash\":[{\"pattern\":\"*\",\"action\":\"invalid\"}]}}")) {
      assertThrows(IllegalArgumentException.class, () -> codec.canonicalize(invalid), invalid);
    }
  }

  /** wildcard `?`、绝对 workdir、Environment root 外路径和空 command 使用确定候选。 */
  @Test
  void evaluatesWildcardAbsoluteAndEmptyTargets() {
    Map<String, List<PermissionRule>> rules = new LinkedHashMap<>();
    rules.put(
        "write",
        List.of(
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("file?.txt", PermissionAction.ALLOW),
            new PermissionRule("/outside/*", PermissionAction.DENY)));
    ToolSettings settings = new ToolSettings(rules, false);

    assertEquals(
        PermissionAction.ALLOW, evaluate("write", "{\"path\":\"file1.txt\"}", settings).action());
    assertEquals(
        PermissionAction.DENY,
        evaluate("write", "{\"workdir\":\"/outside\",\"path\":\"secret\"}", settings).action());
    assertEquals(
        PermissionAction.ASK,
        evaluate(
                "bash",
                "{\"command\":\"\"}",
                new ToolSettings(
                    Map.of("bash", List.of(new PermissionRule("*", PermissionAction.ASK))), false))
            .action());
  }

  /** malformed arguments、blank contracts 和 prompt bounds 必须在 preparation 前失败。 */
  @Test
  void rejectsInvalidPermissionContracts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluator.evaluate(
                new PermissionEvaluationContext(
                    "write", "[]", Path.of("."), Path.of("."), ToolSettings.DEFAULT)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PermissionEvaluationContext(
                " ", "{}", Path.of("."), Path.of("."), ToolSettings.DEFAULT));
    PermissionEvaluationContext invalidWorkdir =
        new PermissionEvaluationContext(
            "write",
            "{\"path\":\"x\",\"workdir\":\"@\"}",
            Path.of("."),
            Path.of("."),
            ToolSettings.DEFAULT);
    assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(invalidWorkdir));
    assertEquals("<invalid-workdir>", evaluator.preview(invalidWorkdir).workdir());
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue("invalid"));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue(null));
    assertThrows(
        IllegalArgumentException.class, () -> new PermissionRule(" ", PermissionAction.ALLOW));
    assertThrows(
        IllegalArgumentException.class, () -> new PermissionPromptPreview("tool", ".", "x\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PermissionPromptPreview("tool", "bad\tworkdir", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new PermissionPromptPreview("", ".", "{}"));
  }

  private PermissionEvaluator.Evaluation evaluate(
      String tool, String arguments, ToolSettings settings) {
    return evaluator.evaluate(
        new PermissionEvaluationContext(
            tool, arguments, Path.of("/environment"), Path.of("/environment"), settings));
  }
}
