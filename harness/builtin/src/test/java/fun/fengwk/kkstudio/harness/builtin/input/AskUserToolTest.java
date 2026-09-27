package fun.fengwk.kkstudio.harness.builtin.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaElement;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * {@code ask_user} 的模型侧契约：descriptor 身份、问卷 schema 与 Runtime 冻结问卷 codec 的字段对齐，以及从不伪造答案的执行语义。
 *
 * <p>冻结问卷 fixture 与 Runtime 侧的 {@code HarnessRuntimeTestSupport.ASK_USER_QUESTIONNAIRE}
 * 逐字相同：Runtime 的端到端人工作答用例（冻结为 WAITING_INPUT -&gt; 提交答案 -&gt; ToolResult/回执）就使用这份原文，因此它必须能通过本工具的
 * schema 校验；schema 也必须拒绝 Runtime codec 会拒绝的漂移形状。跨模块无法引用 Runtime 的 codec（builtin 不依赖
 * runtime），因此这里锁定同一份字段集合与同一份原文。
 */
class AskUserToolTest {

  /** 冻结问卷原文：一问单选（含 recommended）+ 一问多选，覆盖答案覆盖度与顺序归一化所依赖的输入形状。 */
  private static final String FROZEN_QUESTIONNAIRE =
      "{\"questions\":["
          + "{\"question\":\"which plan?\",\"options\":[{\"label\":\"fast\"},"
          + "{\"label\":\"safe\",\"recommended\":true}]},"
          + "{\"question\":\"which tracks?\",\"multiple\":true,"
          + "\"options\":[{\"label\":\"a\"},{\"label\":\"b\"},{\"label\":\"c\"}]}]}";

  @Test
  void descriptorFreezesTheAskUserIdentityAndWaitSemantics() {
    ToolDescriptor descriptor = new AskUserTool().descriptor();

    assertEquals("ask_user", descriptor.name());
    assertEquals("ask_user", descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    // 等待没有执行 deadline：真正的等待不占用 Worker，也不经过 Gateway。
    assertEquals(Duration.ZERO, descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());
    assertEquals(ToolRequirements.none(), new AskUserTool().requirements());
  }

  @Test
  void schemaFreezesExactlyTheRuntimeQuestionnaireFields() {
    InputSchema schema = new AskUserTool().descriptor().inputSchema();

    assertEquals(Set.of("questions"), schema.required());
    assertEquals(Set.of("questions"), schema.properties().keySet());
    assertFalse(schema.additionalProperties());

    ObjectSchema question = itemsObject(schema.properties().get("questions"));
    assertEquals(Set.of("question"), question.required());
    assertEquals(Set.of("question", "multiple", "options"), question.properties().keySet());
    assertFalse(question.additionalProperties());

    ObjectSchema option = itemsObject(question.properties().get("options"));
    assertEquals(Set.of("label"), option.required());
    assertEquals(Set.of("label", "description", "recommended"), option.properties().keySet());
    assertFalse(option.additionalProperties());
  }

  /** 冻结 schema 必须能被受限 schema codec 规范化往返：持久化 / 复述 descriptor 不会因不支持的 JSON Schema 关键字漂移。 */
  @Test
  void schemaSurvivesCanonicalCodecRoundtrip() {
    SchemaJsonCodec codec = new SchemaJsonCodec();
    InputSchema schema = new AskUserTool().descriptor().inputSchema();

    assertEquals(schema, codec.decode(codec.encode(schema)));
  }

  /** 被 schema 校验通过的调用必须原样保持问卷原文：durable ToolCall 是 Runtime 冻结问卷的唯一事实源，归一化不得改写它。 */
  @Test
  void validatedCallsKeepTheFrozenQuestionnaireUnchanged() {
    AskUserTool tool = new AskUserTool();

    assertEquals(FROZEN_QUESTIONNAIRE, validate(tool, FROZEN_QUESTIONNAIRE));
    assertEquals(
        "{\"questions\":[{\"question\":\"q\"}]}",
        validate(tool, "{\"questions\":[{\"question\":\"q\"}]}"));
    // 显式 false 与 description 都是合法冻结形状，必须逐字保留。
    String withExplicitDefaults =
        "{\"questions\":[{\"question\":\"q\",\"multiple\":false,"
            + "\"options\":[{\"label\":\"a\",\"description\":\"first\"}]}]}";
    assertEquals(withExplicitDefaults, validate(tool, withExplicitDefaults));
  }

  /** schema 必须拒绝无法冻结成 Runtime 问卷的漂移形状，避免接受一个必然 INVALID_QUESTIONNAIRE 的调用。 */
  @Test
  void schemaRejectsEveryQuestionnaireDrift() {
    AskUserTool tool = new AskUserTool();

    // 缺少 questions 或 questions 不是数组
    assertRejected(tool, "{}");
    assertRejected(tool, "{\"questions\":{}}");
    // 未知字段：问卷只有 questions，问题只有 question/multiple/options，选项只有 label/description/recommended
    assertRejected(tool, "{\"questions\":[{\"question\":\"q\"}],\"header\":\"x\"}");
    assertRejected(tool, "{\"questions\":[{\"question\":\"q\",\"header\":\"x\"}]}");
    assertRejected(
        tool, "{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"a\",\"x\":1}]}]}");
    // 缺失必填或类型错误
    assertRejected(tool, "{\"questions\":[{}]}");
    assertRejected(tool, "{\"questions\":[{\"question\":null}]}");
    assertRejected(tool, "{\"questions\":[{\"question\":\"q\",\"multiple\":\"true\"}]}");
    assertRejected(
        tool, "{\"questions\":[{\"question\":\"q\",\"options\":[{\"description\":\"d\"}]}]}");
    assertRejected(
        tool,
        "{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"a\","
            + "\"recommended\":\"yes\"}]}]}");
    // recommended 属于选项而非问题
    assertRejected(tool, "{\"questions\":[{\"question\":\"q\",\"recommended\":true}]}");
  }

  /**
   * 可选布尔字段的显式 {@code null} 是 schema 层唯一无法表达的形状：归一化把可缺省属性上的显式 null 删除（等同缺省），因此 schema 校验不会拒绝它。调用必须发送
   * JSON 布尔值（工具说明已明确要求）；若模型仍发送 null，Runtime 会以 INVALID_QUESTIONNAIRE 确定性失败并唤醒 Thread，绝不把它当成已作答或默认值。
   */
  @Test
  void explicitNullOnOptionalBooleanIsNormalizedAwayRatherThanFrozen() {
    AskUserTool tool = new AskUserTool();

    assertEquals(
        "{\"questions\":[{\"question\":\"q\"}]}",
        validate(tool, "{\"questions\":[{\"question\":\"q\",\"multiple\":null}]}"));
  }

  /** 工具实现绝不伪造用户答案：被调用时以确定性错误收敛，且不回显问卷原文。 */
  @Test
  void executeNeverFabricatesAnAnswer() {
    AskUserTool tool = new AskUserTool();
    ToolCall call =
        new ToolCall("call-1", AskUserTool.NAME, FROZEN_QUESTIONNAIRE)
            .validateFor(tool.descriptor());
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(new ToolExecutionRequest(tool.descriptor(), call, Duration.ZERO), listener);

    assertEquals(CompletedToolExecutionHandle.INSTANCE, handle);
    assertNull(listener.error);
    ToolOutcome outcome = Objects.requireNonNull(listener.outcome, "the tool must complete");
    assertTrue(outcome.result().error());
    assertEquals("call-1", outcome.result().toolCallId());
    assertTrue(outcome.customEntries().isEmpty());
    String message =
        assertInstanceOf(TextResultContent.class, outcome.result().contents().get(0)).text();
    assertFalse(message.contains("which plan?"), "the error must not echo the questionnaire");
  }

  /** 工具说明必须交代等待语义与冻结限制：模型只能按 schema 生成可冻结的问卷。 */
  @Test
  void instructionsCarryTheFrozenContract() {
    String instructions = new AskUserTool().descriptor().description();

    assertTrue(instructions.contains("`questions`"));
    assertTrue(instructions.contains("`multiple: true`"));
    assertTrue(instructions.contains("`recommended: true`"));
    assertTrue(instructions.contains("never add an \"other\" option"));
    assertTrue(instructions.contains("may decline"));
    assertTrue(instructions.contains("never as `null`"));
  }

  private static String validate(AskUserTool tool, String argumentsJson) {
    return new ToolCall("call-1", AskUserTool.NAME, argumentsJson)
        .validateFor(tool.descriptor())
        .argumentsJson();
  }

  private static void assertRejected(AskUserTool tool, String argumentsJson) {
    assertThrows(IllegalArgumentException.class, () -> validate(tool, argumentsJson));
  }

  private static ObjectSchema itemsObject(SchemaElement array) {
    return assertInstanceOf(ObjectSchema.class, assertInstanceOf(ArraySchema.class, array).items());
  }

  /** 捕获单次执行回调；partial 与 error 都是 ask_user 不应产生的行为。 */
  private static final class RecordingListener implements ToolExecutionListener {

    private ToolOutcome outcome;
    private Throwable error;

    @Override
    public void onPartial(ToolResult partial) {
      throw new AssertionError("ask_user must not stream partial results");
    }

    @Override
    public void onComplete(ToolOutcome value) {
      this.outcome = value;
    }

    @Override
    public void onError(Throwable value) {
      this.error = value;
    }
  }
}
