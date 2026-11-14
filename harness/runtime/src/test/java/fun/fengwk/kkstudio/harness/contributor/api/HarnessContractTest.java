package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Contributor API 统一定义、Tool 契约、执行请求与上下文测试。 */
class HarnessContractTest {

  private static final ContributorId CID = new ContributorId("acme");
  private static final InputSchema SCHEMA =
      new InputSchema(
          "Test schema",
          Map.of(
              "path", new StringSchema(null),
              "offset", new IntegerSchema(null)),
          Set.of("path"),
          false);
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "test_tool",
          "A test tool",
          "test_tool",
          SCHEMA,
          ToolSideEffect.READ_ONLY,
          Duration.ofSeconds(30));

  /** 验证 ContributionId 校验规则、字典序比较与字符串格式化。 */
  @Test
  void contributionIdValidatesAndCompares() {
    ContributionId c1 = new ContributionId(CID, "create");
    ContributionId c2 = new ContributionId(CID, "create");
    ContributionId c3 = new ContributionId(CID, "delete");
    ContributionId c4 = new ContributionId(new ContributorId("aaa"), "create");

    assertEquals("acme:create", c1.toString());
    assertEquals(c1, c2);
    assertEquals(c1.hashCode(), c2.hashCode());
    assertTrue(c1.compareTo(c3) < 0);
    assertTrue(c1.compareTo(c4) > 0);

    assertThrows(NullPointerException.class, () -> new ContributionId(null, "create"));
    assertThrows(NullPointerException.class, () -> new ContributionId(CID, null));
    assertThrows(IllegalArgumentException.class, () -> new ContributionId(CID, "Create"));
    assertThrows(IllegalArgumentException.class, () -> new ContributionId(CID, "create_notes"));
  }

  /** 验证 ContributorDescriptor 依赖校验，包括拒绝自依赖与自动排序。 */
  @Test
  void contributorDescriptorValidatesRequires() {
    ContributorDescriptor desc =
        new ContributorDescriptor(
            CID, "Acme Contributor", "1.0.0", Set.of(new ContributorId("core")));
    assertEquals(CID, desc.id());
    assertEquals("Acme Contributor", desc.name());
    assertEquals("1.0.0", desc.version());
    assertEquals(Set.of(new ContributorId("core")), desc.requires());

    assertThrows(
        NullPointerException.class, () -> new ContributorDescriptor(null, "Name", "1.0", Set.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new ContributorDescriptor(CID, "", "1.0", Set.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new ContributorDescriptor(CID, "Name", "", Set.of()));
    assertThrows(
        NullPointerException.class, () -> new ContributorDescriptor(CID, "Name", "1.0", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContributorDescriptor(CID, "Name", "1.0", Set.of(CID)));
  }

  /**
   * 验证 ToolRequirements 的环境关系声明。
   *
   * <p>环境关系只有执行位置语义：{@code NONE} 工具不参与环境绑定，{@code ROUTED} 文件工具按每次调用的参数决定位置，{@code REQUIRED}
   * 工具只能在绑定环境执行。
   */
  @Test
  void toolRequirementsDeclareEnvironmentSupport() {
    ToolRequirements none = ToolRequirements.none();
    assertEquals(EnvironmentSupport.NONE, none.environmentSupport());

    ToolRequirements routed = ToolRequirements.routed();
    assertEquals(EnvironmentSupport.ROUTED, routed.environmentSupport());

    ToolRequirements env = ToolRequirements.environment();
    assertEquals(EnvironmentSupport.REQUIRED, env.environmentSupport());

    assertThrows(NullPointerException.class, () -> new ToolRequirements(null));
  }

  /** 验证 ToolOutcome 只携带模型可见结果，error 结果同样合法。 */
  @Test
  void toolOutcomeCarriesResultOnly() {
    ToolResult successResult =
        new ToolResult("call-1", List.of(new TextResultContent("done")), false, "{}");
    ToolResult errorResult = ToolResult.error("call-1", "failed");

    ToolOutcome outcome = new ToolOutcome(successResult);
    assertEquals(successResult, outcome.result());

    ToolOutcome validError = new ToolOutcome(errorResult);
    assertTrue(validError.result().error());

    assertThrows(NullPointerException.class, () -> new ToolOutcome(null));
  }

  /** 验证 ToolExecutionContext 的会话/执行根/assistant 入口/冻结能力快照与完整非空校验。 */
  @Test
  void toolExecutionContextValidatesNonNull() {
    UUID invocationId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID rootThreadId = UUID.randomUUID();
    UUID rootSessionId = UUID.randomUUID();
    UUID assistantEntryId = UUID.randomUUID();
    Instant now = Instant.now();
    TurnMediaCapabilities capabilities = TurnMediaCapabilities.NONE;
    BoundEnvironment env = new DummyBoundEnvironment();

    ToolExecutionContext ctxWithEnv =
        new ToolExecutionContext(
            invocationId,
            threadId,
            sessionId,
            rootThreadId,
            rootSessionId,
            assistantEntryId,
            now,
            capabilities,
            Optional.of(env));
    assertEquals(invocationId, ctxWithEnv.invocationId());
    assertEquals(threadId, ctxWithEnv.threadId());
    assertEquals(sessionId, ctxWithEnv.sessionId());
    assertEquals(rootThreadId, ctxWithEnv.rootThreadId());
    assertEquals(rootSessionId, ctxWithEnv.rootSessionId());
    assertEquals(assistantEntryId, ctxWithEnv.assistantEntryId());
    assertEquals(now, ctxWithEnv.executedAt());
    assertEquals(capabilities, ctxWithEnv.mediaCapabilities());
    assertTrue(ctxWithEnv.environment().isPresent());
    assertEquals(env, ctxWithEnv.environment().get());

    ToolExecutionContext ctxWithoutEnv =
        new ToolExecutionContext(
            invocationId,
            threadId,
            sessionId,
            rootThreadId,
            rootSessionId,
            assistantEntryId,
            now,
            capabilities,
            Optional.empty());
    assertFalse(ctxWithoutEnv.environment().isPresent());

    // 完整非空校验
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                null,
                threadId,
                sessionId,
                rootThreadId,
                rootSessionId,
                assistantEntryId,
                now,
                capabilities,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                null,
                sessionId,
                rootThreadId,
                rootSessionId,
                assistantEntryId,
                now,
                capabilities,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                null,
                rootThreadId,
                rootSessionId,
                assistantEntryId,
                now,
                capabilities,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                sessionId,
                null,
                rootSessionId,
                assistantEntryId,
                now,
                capabilities,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                sessionId,
                rootThreadId,
                null,
                assistantEntryId,
                now,
                capabilities,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                sessionId,
                rootThreadId,
                rootSessionId,
                null,
                now,
                capabilities,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                sessionId,
                rootThreadId,
                rootSessionId,
                assistantEntryId,
                null,
                capabilities,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                sessionId,
                rootThreadId,
                rootSessionId,
                assistantEntryId,
                now,
                null,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                sessionId,
                rootThreadId,
                rootSessionId,
                assistantEntryId,
                now,
                null,
                Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId,
                threadId,
                sessionId,
                rootThreadId,
                rootSessionId,
                assistantEntryId,
                now,
                capabilities,
                (Optional<BoundEnvironment>) null));
  }

  /** 验证 BoundEnvironment 执行窄能力契约。 */
  @Test
  void boundEnvironmentContract() {
    DummyBoundEnvironment env = new DummyBoundEnvironment();
    assertEquals("11111111-1111-1111-1111-111111111111", env.environmentId().toString());

    EnvironmentCapabilityDescriptor cap =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("fs.read"), SCHEMA, Duration.ofSeconds(10));
    ToolExecutionRequest req =
        new ToolExecutionRequest(
            DESCRIPTOR, new ToolCall("call-1", "test_tool", "{\"path\":\"a.txt\"}"), Duration.ZERO);
    ToolResult result = env.execute(cap, req, partial -> {});
    assertEquals("call-1", result.toolCallId());
  }

  /**
   * 验证 ToolExecutionRequest 参数静默归一化，以及 timeout 是已解析值原样保存、不做二次回落。
   *
   * <p>默认 Tool 的 {@link Tool#resolveTimeout} 只返回 descriptor.defaultTimeout()，因此 0 与显式值都按原样进入请求。
   */
  @Test
  void toolExecutionRequestNormalizesAndKeepsResolvedTimeout() {
    ToolCall rawCall =
        new ToolCall("call-1", "test_tool", "{\"offset\":\"20\",\"path\":\"README.md\"}");
    ToolExecutionRequest request = new ToolExecutionRequest(DESCRIPTOR, rawCall, Duration.ZERO);

    // 校验 call 已被归一化：整数字符串转为 integer
    assertEquals("{\"offset\":20,\"path\":\"README.md\"}", request.call().argumentsJson());
    // strict Provider 为原可选字段填入 null 时，执行请求把它等价为缺省。
    ToolExecutionRequest nullOptionalRequest =
        new ToolExecutionRequest(
            DESCRIPTOR,
            new ToolCall("call-2", "test_tool", "{\"offset\":null,\"path\":\"README.md\"}"),
            Duration.ZERO);
    assertEquals("{\"path\":\"README.md\"}", nullOptionalRequest.call().argumentsJson());
    // timeout 是执行前解析完成的唯一结果：请求原样保存，0 表示没有 deadline。
    assertEquals(Duration.ZERO, request.timeout());
    assertEquals(
        Duration.ofSeconds(5),
        new ToolExecutionRequest(DESCRIPTOR, rawCall, Duration.ofSeconds(5)).timeout());

    // 默认 resolveTimeout 只返回 definition 默认值，不读取 arguments。
    Tool defaultTimeoutTool = tool(DESCRIPTOR, ToolRequirements.none());
    assertEquals(Duration.ofSeconds(30), defaultTimeoutTool.resolveTimeout(rawCall));

    // 拒绝负数超时
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolExecutionRequest(DESCRIPTOR, rawCall, Duration.ofSeconds(-1)));
  }

  /** 构造仅覆盖 descriptor/requirements 的匿名 Tool，用于验证默认 SPI 行为。 */
  private static Tool tool(ToolDescriptor descriptor, ToolRequirements requirements) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolRequirements requirements() {
        return requirements;
      }

      @Override
      public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
        return dummyOutcome();
      }
    };
  }

  /** 验证 ToolContribution 单一 record 构造与契约校验（descriptor 与 requirements 一致性）。 */
  @Test
  void toolContributionValidatesDescriptorAndRequirements() {
    ContributionId id = new ContributionId(CID, "my-tool");
    AgentToolDefinition definition = new AgentToolDefinition(DESCRIPTOR, ToolVisibility.SELECTABLE);
    ToolRequirements requirements = ToolRequirements.none();

    Tool matchingTool =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return DESCRIPTOR;
          }

          @Override
          public ToolRequirements requirements() {
            return requirements;
          }

          @Override
          public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
            return dummyOutcome();
          }
        };

    ToolContribution contribution =
        new ToolContribution(id, definition, matchingTool, requirements, 10);
    assertEquals(id, contribution.id());
    assertEquals(definition, contribution.definition());
    assertEquals(matchingTool, contribution.tool());
    assertEquals(requirements, contribution.requirements());
    assertEquals(10, contribution.priority());

    // descriptor 不匹配抛异常
    Tool mismatchedDescriptorTool =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                "other", "other tool", "other", SCHEMA, ToolSideEffect.READ_ONLY, Duration.ZERO);
          }

          @Override
          public ToolRequirements requirements() {
            return requirements;
          }

          @Override
          public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
            return dummyOutcome();
          }
        };

    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolContribution(id, definition, mismatchedDescriptorTool, requirements, 0));

    // requirements 不匹配抛异常
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolContribution(id, definition, matchingTool, ToolRequirements.environment(), 0));
  }

  private static ToolOutcome dummyOutcome() {
    return new ToolOutcome(
        new ToolResult("call-1", List.of(new TextResultContent("ok")), false, "{}"));
  }

  private static final class DummyBoundEnvironment implements BoundEnvironment {
    @Override
    public EnvironmentId environmentId() {
      return EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    }

    @Override
    public ToolResult execute(
        EnvironmentCapabilityDescriptor capability,
        ToolExecutionRequest request,
        Consumer<ToolResult> progress) {
      return dummyOutcome().result();
    }
  }
}
