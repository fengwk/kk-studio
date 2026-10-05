package fun.fengwk.kkstudio.project.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * {@link ProjectRunScopeJsonCodec} 是 Run 冻结快照的唯一事实源：编码必须确定性，解码必须严格拒绝未知/缺失/错误类型字段，
 * 使损坏或版本不符的快照确定性失败而不是被降级成"没有 Issue 上下文"。
 */
class ProjectRunScopeJsonCodecTest {

  private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ISSUE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID PROJECT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID THREAD_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  private final ProjectRunScopeJsonCodec codec = new ProjectRunScopeJsonCodec();

  /** 完整快照的规范编码必须无损往返，且相同快照始终得到相同文本。 */
  @Test
  void encodesCanonicalFormAndRoundTrips() {
    ProjectRunScope scope = scope("设计", "完成可交付方案", List.of("REVIEW", "DONE"));

    String json = codec.encode(scope);

    assertEquals(json, codec.encode(scope));
    assertEquals(scope, codec.decode(json));
  }

  /** 可空字段允许为 null，但字段本身必须存在：缺失 issueDescription 是损坏快照，而不是"没有描述"。 */
  @Test
  void rejectsMissingOptionalButRequiredField() {
    ProjectRunScope scope =
        new ProjectRunScope(
            RUN_ID,
            ISSUE_ID,
            PROJECT_ID,
            THREAD_ID,
            7L,
            "修复登录",
            null,
            "DESIGN",
            "设计",
            null,
            List.of(),
            "worker",
            true);
    String json = codec.encode(scope);
    assertNull(codec.decode(json).issueDescription());
    assertNull(codec.decode(json).stageInstructions());

    String missing = json.replace("\"issueDescription\":null,", "");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missing));
  }

  /** 未知字段、缺失字段与错误类型都必须确定性拒绝，绝不静默忽略。 */
  @Test
  void rejectsUnknownMissingAndWrongTypedFields() {
    String json = codec.encode(scope("设计", "做事", List.of("REVIEW")));

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.substring(0, json.length() - 1) + ",\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace(",\"agentName\":\"worker\"", "")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(json.replace(",\"active\":true", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"active\":true", "\"active\":\"true\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"issueNumber\":7", "\"issueNumber\":\"7\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(json.replace("\"nextStates\":[\"REVIEW\"]", "\"nextStates\":\"REVIEW\"")));
  }

  /** duplicate field 与 trailing token 必须拒绝：它们是请求方伪造/损坏快照的典型形态。 */
  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    String json = codec.encode(scope("设计", "做事", List.of("REVIEW")));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                json.replace("\"stage\":\"DESIGN\"", "\"stage\":\"DESIGN\",\"stage\":\"X\"")));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
  }

  /** 非对象 JSON 与非法 JSON 均拒绝，避免把数组/标量当成合法快照。 */
  @Test
  void rejectsNonObjectAndMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{oops}"));
  }

  /** 关闭副本与活跃快照共用同一严格格式，只通过 active 区分，且必须无损往返。 */
  @Test
  void encodesAndDecodesClosedScopeFaithfully() {
    ProjectRunScope active = scope("做事", "设计", List.of("REVIEW"));
    ProjectRunScope closed = active.closed();

    assertEquals(active.runId(), closed.runId());
    assertFalse(closed.active());
    String json = codec.encode(closed);
    assertTrue(json.contains("\"active\":false"));
    assertEquals(closed, codec.decode(json));
  }

  private static ProjectRunScope scope(
      String instructions, String stageName, List<String> nextStates) {
    return new ProjectRunScope(
        RUN_ID,
        ISSUE_ID,
        PROJECT_ID,
        THREAD_ID,
        7L,
        "修复登录",
        "登录偶发失败",
        "DESIGN",
        "设计",
        instructions,
        nextStates,
        "worker",
        true);
  }
}
