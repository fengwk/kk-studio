package fun.fengwk.kkstudio.harness.runtime.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ask_user} 契约的值对象层：问卷严格解析、答案按冻结问卷规范化，以及身份判据。
 *
 * <p>测试意图：（1）问卷是接受时冻结、提交时必须精确重合的形状，任何未知字段/缺失必填/非法类型都必须拒绝；（2）答案规范化让“仅顺序不同”
 * 的提交比较相等（重试幂等的前提）；（3）拒绝信息只包含下标与原因，绝不回显提交值；（4）工具身份由冻结 binding 的 provenance 决定， 模型给出的同名工具不构成人输入。
 */
class HumanInputContractTest {

  private static final HumanInputJsonCodec CODEC = new HumanInputJsonCodec();

  private static final String QUESTIONNAIRE =
      "{\"questions\":["
          + "{\"question\":\"which plan?\",\"options\":[{\"label\":\"fast\"},{\"label\":\"safe\",\"recommended\":true,\"description\":\"careful\"}]},"
          + "{\"question\":\"which tracks?\",\"multiple\":true,\"options\":[{\"label\":\"a\"},{\"label\":\"b\"},{\"label\":\"c\"}]},"
          + "{\"question\":\"anything else?\"}"
          + "]}";

  @Test
  void questionnaireParsesStrictlyWithDefaults() {
    HumanInputQuestionnaire questionnaire = CODEC.decodeQuestionnaire(QUESTIONNAIRE);

    assertEquals(3, questionnaire.questions().size());
    HumanInputQuestion first = questionnaire.questions().get(0);
    assertEquals("which plan?", first.question());
    assertFalse(first.multiple());
    assertEquals(2, first.options().size());
    assertNull(first.options().get(0).description());
    assertFalse(first.options().get(0).recommended());
    assertEquals("careful", first.options().get(1).description());
    assertTrue(first.options().get(1).recommended());
    // 多选与“无选项=只接受自定义回答”的形状。
    assertTrue(questionnaire.questions().get(1).multiple());
    assertTrue(questionnaire.questions().get(2).options().isEmpty());
  }

  @Test
  void malformedQuestionnaireIsRejectedWithoutEchoingValues() {
    // 未知字段、缺失 questions、非对象/非数组、空问题、重复 label、单选多推荐、空白问题文本。
    for (String malformed :
        List.of(
            "{\"questions\":[{\"question\":\"q\",\"unknown\":1}]}",
            "{\"questions\":[]}",
            "[]",
            "{\"questions\":{}}",
            "{\"questions\":[{\"question\":\"   \"}]}",
            "{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"x\"},{\"label\":\"x\"}]}]}",
            "{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"x\",\"recommended\":true},{\"label\":\"y\",\"recommended\":true}]}]}",
            "{\"questions\":[{\"question\":\"secret-value\",\"multiple\":\"yes\"}]}")) {
      IllegalArgumentException invalid =
          assertThrows(
              IllegalArgumentException.class,
              () -> CODEC.decodeQuestionnaire(malformed),
              malformed);
      // 拒绝只报告字段路径，不回显问题或选项原文。
      assertFalse(invalid.getMessage().contains("secret-value"));
      assertFalse(invalid.getMessage().contains("q\","));
    }
    assertThrows(NullPointerException.class, () -> CODEC.decodeQuestionnaire(null));
  }

  @Test
  void answersAreNormalizedByFrozenQuestionnaireOrder() {
    HumanInputQuestionnaire questionnaire = CODEC.decodeQuestionnaire(QUESTIONNAIRE);

    // 单选一项；多选按问卷选项顺序归一化，因此提交顺序不影响规范化结果。
    HumanInputAnswers answers =
        HumanInputAnswers.accept(
            questionnaire,
            false,
            List.of(List.of("fast"), List.of("c", "a", "a"), List.of("plain text")));

    assertEquals(
        List.of(List.of("fast"), List.of("a", "c"), List.of("plain text")), answers.answers());
    assertEquals(
        "{\"answers\":[[\"fast\"],[\"a\",\"c\"],[\"plain text\"]]}", CODEC.encodeAnswers(answers));
    // 只有顺序不同的提交规范化后完全相等（重试幂等的判据）。
    assertEquals(
        answers,
        HumanInputAnswers.accept(
            questionnaire,
            false,
            List.of(List.of("fast"), List.of("a", "c"), List.of("plain text"))));
  }

  @Test
  void customAnswerIsAlwaysAllowedButLimitedToOnePerQuestion() {
    HumanInputQuestionnaire questionnaire = CODEC.decodeQuestionnaire(QUESTIONNAIRE);

    // 无选项问题只能自定义回答；有选项问题可在选项之外补一项自定义回答。
    assertEquals(
        List.of("a", "custom"),
        HumanInputAnswers.accept(
                questionnaire,
                false,
                List.of(List.of("fast"), List.of("a", "custom"), List.of("x")))
            .answers()
            .get(1));
    // 多选至多一项自定义回答。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HumanInputAnswers.accept(
                questionnaire, false, List.of(List.of("fast"), List.of("x", "y"), List.of("z"))));
  }

  @Test
  void submissionMustCoverEveryQuestionExactlyOnce() {
    HumanInputQuestionnaire questionnaire = CODEC.decodeQuestionnaire(QUESTIONNAIRE);

    assertThrows(
        IllegalArgumentException.class,
        () -> HumanInputAnswers.accept(questionnaire, false, List.of(List.of("fast"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HumanInputAnswers.accept(
                questionnaire,
                false,
                List.of(List.of("fast"), List.of("a"), List.of("x"), List.of("extra"))));
    // 单选题必须恰好一个回答。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HumanInputAnswers.accept(
                questionnaire, false, List.of(List.of("fast", "a"), List.of("a"), List.of("x"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HumanInputAnswers.accept(
                questionnaire, false, List.of(List.of(), List.of("a"), List.of("x"))));
  }

  @Test
  void invalidAnswersAreRejectedWithoutLeakingSubmittedValues() {
    HumanInputQuestionnaire questionnaire = CODEC.decodeQuestionnaire(QUESTIONNAIRE);
    String secret = "customer-secret-value";

    IllegalArgumentException blank =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HumanInputAnswers.accept(
                    questionnaire, false, List.of(List.of("   "), List.of("a"), List.of("x"))));
    assertFalse(blank.getMessage().contains(secret));
    IllegalArgumentException tooManyCustom =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HumanInputAnswers.accept(
                    questionnaire,
                    false,
                    List.of(List.of(secret, "other"), List.of("a"), List.of("x"))));
    assertFalse(tooManyCustom.getMessage().contains(secret));
    // 拒绝信息使用问题下标定位，不复制问题文本。
    assertTrue(tooManyCustom.getMessage().contains("question#0"));
    assertFalse(tooManyCustom.getMessage().contains("which plan?"));
  }

  @Test
  void declinedSubmissionCarriesNoAnswers() {
    HumanInputQuestionnaire questionnaire = CODEC.decodeQuestionnaire(QUESTIONNAIRE);

    HumanInputAnswers declined = HumanInputAnswers.accept(questionnaire, true, List.of());
    assertTrue(declined.declined());
    assertEquals("{\"declined\":true}", CODEC.encodeAnswers(declined));
    assertThrows(
        IllegalArgumentException.class,
        () -> HumanInputAnswers.accept(questionnaire, true, List.of(List.of("fast"))));
    // 非拒答必须携带答案，且值对象本身也拒绝自相矛盾的组合。
    assertThrows(
        IllegalArgumentException.class, () -> HumanInputAnswers.accept(questionnaire, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HumanInputAnswers(true, List.of(List.of("fast"))));
    assertThrows(IllegalArgumentException.class, () -> new HumanInputAnswers(false, List.of()));
  }

  @Test
  void identityComesFromFrozenProvenanceNotFromTheToolName() {
    ToolBinding builtin = binding("ask_user", "builtin", "ask-user");
    ToolBinding sameNameOtherContributor = binding("ask_user", "core", "ask-user");
    ToolBinding otherNameInBuiltin = binding("bash", "builtin", "ask-user");

    assertTrue(HumanInputTool.isHumanInputTool(builtin));
    assertFalse(HumanInputTool.isHumanInputTool(sameNameOtherContributor));
    assertFalse(HumanInputTool.isHumanInputTool(otherNameInBuiltin));
    assertEquals("ask_user", HumanInputTool.ASK_USER);
    assertEquals("builtin", HumanInputTool.BUILTIN_CONTRIBUTOR_ID);
  }

  /**
   * 问卷与答案不再有业务数量/长度上限：fixture 的题数、选项数与问题/label/description 都超过旧上限，必须被接受并逐字保留。
   *
   * <p>长结构化问卷放在 classpath fixture（{@code input/long-questionnaire.json}），避免把巨型 JSON 写进测试代码。
   */
  @Test
  void longContentIsAcceptedWithoutBusinessCeilings() {
    HumanInputQuestionnaire questionnaire =
        CODEC.decodeQuestionnaire(
            readResource("/fun/fengwk/kkstudio/harness/runtime/input/long-questionnaire.json"));

    assertEquals(21, questionnaire.questions().size());
    assertEquals("Q".repeat(1100), questionnaire.questions().get(0).question());
    assertEquals(21, questionnaire.questions().get(1).options().size());
    assertEquals("L".repeat(300), questionnaire.questions().get(1).options().get(0).label());
    assertEquals("D".repeat(1100), questionnaire.questions().get(1).options().get(0).description());

    // 超长自定义答案按冻结问卷规范化，原样保留而不是截断或静默降级。
    String longAnswer = "a".repeat(5000);
    List<List<String>> submitted = new ArrayList<>(questionnaire.questions().size());
    for (int q = 0; q < questionnaire.questions().size(); q++) {
      submitted.add(List.of(longAnswer));
    }
    HumanInputAnswers answers = HumanInputAnswers.accept(questionnaire, false, submitted);
    assertEquals(questionnaire.questions().size(), answers.answers().size());
    assertEquals(longAnswer, answers.answers().get(0).get(0));
  }

  /** 去掉业务上限后仍保留结构约束：至少一问、非空文本、无首尾空白、label 唯一、单选至多一个 recommended。 */
  @Test
  void structuralConstraintsRemain() {
    assertThrows(IllegalArgumentException.class, () -> new HumanInputQuestionnaire(List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new HumanInputQuestion(" padded ", false, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new HumanInputQuestion("   ", false, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HumanInputQuestion(
                "ok",
                false,
                List.of(
                    new HumanInputOption("a", null, false),
                    new HumanInputOption("a", null, false))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HumanInputQuestion(
                "ok",
                false,
                List.of(
                    new HumanInputOption("a", null, true), new HumanInputOption("b", null, true))));
    // 多选允许多个 recommended：结构约束只针对单选。
    assertEquals(
        2,
        new HumanInputQuestion(
                "ok",
                true,
                List.of(
                    new HumanInputOption("a", null, true), new HumanInputOption("b", null, true)))
            .options()
            .size());
  }

  /** 提交命令自身的形状校验：actor 必须非空、无首尾空白且有界；拒答不得携带答案。 */
  @Test
  void submissionCommandRejectsMalformedShape() {
    assertThrows(
        IllegalArgumentException.class,
        () -> submission("  alice  ", false, List.of(List.of("a"))));
    assertThrows(
        IllegalArgumentException.class, () -> submission(" ", false, List.of(List.of("a"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> submission("a".repeat(129), false, List.of(List.of("a"))));
    assertThrows(
        IllegalArgumentException.class, () -> submission("alice", true, List.of(List.of("a"))));
    assertThrows(NullPointerException.class, () -> submission(null, false, List.of()));
    // 合法的拒答，以及"未填写的问题"以 null 占位保留（由运行时按冻结问卷精确拒绝）。
    assertTrue(submission("alice", true, List.of()).declined());
    ToolInputSubmissionCommand withGap =
        submission("alice", false, Collections.singletonList(null));
    assertEquals(1, withGap.answers().size());
    assertNull(withGap.answers().get(0));
  }

  private static ToolInputSubmissionCommand submission(
      String actor, boolean declined, List<List<String>> answers) {
    return new ToolInputSubmissionCommand(
        new UUID(0L, 1L), new UUID(0L, 2L), new UUID(0L, 3L), actor, declined, answers);
  }

  /** 读取 classpath 测试资源：长结构化问卷 fixture 不写进测试代码。 */
  private static String readResource(String resource) {
    try (InputStream input =
        Objects.requireNonNull(
            HumanInputContractTest.class.getResourceAsStream(resource), resource)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static ToolBinding binding(String toolName, String contributorId, String localName) {
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                toolName,
                "description",
                toolName,
                new InputSchema("arguments", Map.of(), Set.of(), true),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding(contributorId, localName, List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }
}
