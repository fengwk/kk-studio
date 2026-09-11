package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Contributor API 统一定义、Tool 契约、执行请求与上下文测试。 */
class HarnessContractTest {

  private static final ContributorId CID = new ContributorId("goal");
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
          "1.0",
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
    ContributionId c4 = new ContributionId(new ContributorId("alpha"), "create");

    assertEquals("goal:create", c1.toString());
    assertEquals(c1, c2);
    assertEquals(c1.hashCode(), c2.hashCode());
    assertTrue(c1.compareTo(c3) < 0);
    assertTrue(c1.compareTo(c4) > 0);

    assertThrows(NullPointerException.class, () -> new ContributionId(null, "create"));
    assertThrows(NullPointerException.class, () -> new ContributionId(CID, null));
    assertThrows(IllegalArgumentException.class, () -> new ContributionId(CID, "Create"));
    assertThrows(IllegalArgumentException.class, () -> new ContributionId(CID, "create_goal"));
  }

  /** 验证 ContributorDescriptor 依赖校验，包括拒绝自依赖与自动排序。 */
  @Test
  void contributorDescriptorValidatesRequires() {
    ContributorDescriptor desc =
        new ContributorDescriptor(
            CID, "Goal Contributor", "1.0.0", Set.of(new ContributorId("core")));
    assertEquals(CID, desc.id());
    assertEquals("Goal Contributor", desc.name());
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

  /** 验证 StateDeclaration 校验 canonical customType 与模式非空。 */
  @Test
  void stateDeclarationValidates() {
    StateDeclaration decl = new StateDeclaration("goal-state", StateMode.WRITE);
    assertEquals("goal-state", decl.customType());
    assertEquals(StateMode.WRITE, decl.mode());

    assertThrows(NullPointerException.class, () -> new StateDeclaration(null, StateMode.READ));
    assertThrows(NullPointerException.class, () -> new StateDeclaration("state", null));
    assertThrows(
        IllegalArgumentException.class, () -> new StateDeclaration("State", StateMode.READ));
    assertThrows(
        IllegalArgumentException.class, () -> new StateDeclaration("goal_state", StateMode.READ));
  }

  /** 验证 ToolRequirements 工厂方法、重复 customType 校验与不可变性。 */
  @Test
  void toolRequirementsValidatesAndEnforcesUniqueness() {
    ToolRequirements none = ToolRequirements.none();
    assertFalse(none.environmentRequired());
    assertTrue(none.stateAccesses().isEmpty());

    ToolRequirements env = ToolRequirements.environment();
    assertTrue(env.environmentRequired());
    assertTrue(env.stateAccesses().isEmpty());

    StateDeclaration read = new StateDeclaration("state.read", StateMode.READ);
    StateDeclaration write = new StateDeclaration("state.write", StateMode.WRITE);
    ToolRequirements custom = new ToolRequirements(true, List.of(read, write));
    assertTrue(custom.environmentRequired());
    assertEquals(List.of(read, write), custom.stateAccesses());

    // 拒绝重复 customType
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolRequirements(
                false,
                List.of(
                    new StateDeclaration("state", StateMode.READ),
                    new StateDeclaration("state", StateMode.WRITE))));

    // 拒绝 null
    assertThrows(NullPointerException.class, () -> new ToolRequirements(false, null));
  }

  /** 验证 ToolOutcome 成功携带 effects、error 拒绝 effects、以及 withoutEffects 工厂。 */
  @Test
  void toolOutcomeValidatesErrorAndEffects() {
    ToolResult successResult =
        new ToolResult("call-1", List.of(new TextResultContent("done")), false, "{}");
    ToolResult errorResult = ToolResult.error("call-1", "failed");
    AppendCustomEntry entry = new AppendCustomEntry("state", 1, "{}");

    ToolOutcome outcomeWithEffects = new ToolOutcome(successResult, List.of(entry));
    assertEquals(successResult, outcomeWithEffects.result());
    assertEquals(List.of(entry), outcomeWithEffects.customEntries());

    ToolOutcome outcomeWithoutEffects = ToolOutcome.withoutEffects(successResult);
    assertTrue(outcomeWithoutEffects.customEntries().isEmpty());

    // error 结果不允许携带 custom entries effects
    assertThrows(
        IllegalArgumentException.class, () -> new ToolOutcome(errorResult, List.of(entry)));

    // error 结果在无 effects 时合法
    ToolOutcome validError = ToolOutcome.withoutEffects(errorResult);
    assertTrue(validError.result().error());
    assertTrue(validError.customEntries().isEmpty());

    assertThrows(NullPointerException.class, () -> new ToolOutcome(null, List.of()));
    assertThrows(NullPointerException.class, () -> new ToolOutcome(successResult, null));
  }

  /** 验证 ToolExecutionListener 默认方法将 onComplete(ToolResult) 委托为无 effects 的 ToolOutcome。 */
  @Test
  void toolExecutionListenerDelegatesOnCompleteByDefault() {
    AtomicReference<ToolOutcome> received = new AtomicReference<>();
    ToolExecutionListener listener =
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            received.set(outcome);
          }

          @Override
          public void onError(Throwable error) {}
        };

    ToolResult result = new ToolResult("call-1", List.of(new TextResultContent("ok")), false, "{}");
    listener.onComplete(result);

    assertEquals(result, received.get().result());
    assertTrue(received.get().customEntries().isEmpty());
  }

  /** 验证 ToolExecutionHandle 取消契约。 */
  @Test
  void toolExecutionHandleContract() {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    ToolExecutionHandle handle =
        new ToolExecutionHandle() {
          @Override
          public void cancel() {
            cancelled.set(true);
          }

          @Override
          public boolean isCancelled() {
            return cancelled.get();
          }
        };

    assertFalse(handle.isCancelled());
    handle.cancel();
    assertTrue(handle.isCancelled());
  }

  /** 验证 ToolExecutionContext 构造、重载与完整非空校验。 */
  @Test
  void toolExecutionContextValidatesNonNull() {
    UUID invocationId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    Instant now = Instant.now();
    BranchView view = new DummyBranchView();
    BoundEnvironment env = new DummyBoundEnvironment();

    ToolExecutionContext ctxWithEnv =
        new ToolExecutionContext(invocationId, threadId, now, view, env);
    assertEquals(invocationId, ctxWithEnv.invocationId());
    assertEquals(threadId, ctxWithEnv.threadId());
    assertEquals(now, ctxWithEnv.executedAt());
    assertEquals(view, ctxWithEnv.branch());
    assertTrue(ctxWithEnv.environment().isPresent());
    assertEquals(env, ctxWithEnv.environment().get());

    ToolExecutionContext ctxWithoutEnv =
        new ToolExecutionContext(invocationId, threadId, now, view);
    assertFalse(ctxWithoutEnv.environment().isPresent());

    // 完整非空校验
    assertThrows(
        NullPointerException.class,
        () -> new ToolExecutionContext(null, threadId, now, view, Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () -> new ToolExecutionContext(invocationId, null, now, view, Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () -> new ToolExecutionContext(invocationId, threadId, null, view, Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () -> new ToolExecutionContext(invocationId, threadId, now, null, Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolExecutionContext(
                invocationId, threadId, now, view, (Optional<BoundEnvironment>) null));
  }

  /** 验证 BoundEnvironment 执行窄能力契约。 */
  @Test
  void boundEnvironmentContract() {
    DummyBoundEnvironment env = new DummyBoundEnvironment();
    assertEquals("11111111-1111-1111-1111-111111111111", env.binding().environmentId().toString());
    assertEquals(".", env.binding().workspacePath());

    EnvironmentCapabilityDescriptor cap =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("fs.read"), "1.0", SCHEMA, Duration.ofSeconds(10));
    ToolExecutionRequest req =
        new ToolExecutionRequest(
            DESCRIPTOR, new ToolCall("call-1", "test_tool", "{\"path\":\"a.txt\"}"), Duration.ZERO);
    AtomicBoolean called = new AtomicBoolean(false);
    ToolExecutionListener listener =
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            called.set(true);
          }

          @Override
          public void onError(Throwable error) {}
        };

    ToolExecutionHandle handle = env.execute(cap, req, listener);
    assertFalse(handle.isCancelled());
  }

  /** 验证 ToolExecutionRequest 构造、有效超时以及参数静默归一化行为。 */
  @Test
  void toolExecutionRequestNormalizesAndResolvesTimeout() {
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
    // 请求超时为 ZERO 时使用 descriptor 的默认超时
    assertEquals(Duration.ofSeconds(30), request.effectiveTimeout());

    // 覆盖超时
    ToolExecutionRequest customTimeoutReq =
        new ToolExecutionRequest(DESCRIPTOR, rawCall, Duration.ofSeconds(5));
    assertEquals(Duration.ofSeconds(5), customTimeoutReq.effectiveTimeout());

    // 拒绝负数超时
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolExecutionRequest(DESCRIPTOR, rawCall, Duration.ofSeconds(-1)));

    // 校验绝对路径 workdir
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                DESCRIPTOR, rawCall, Duration.ZERO, null, Path.of("relative/path")));

    ToolExecutionRequest absoluteReq =
        new ToolExecutionRequest(
            DESCRIPTOR, rawCall, Duration.ZERO, null, Path.of("/absolute/path"));
    assertEquals(Path.of("/absolute/path"), absoluteReq.workdir());
  }

  /** 验证 ToolContribution 单一 record 构造与契约校验（descriptor 与 requirements 一致性）。 */
  @Test
  void toolContributionValidatesDescriptorAndRequirements() {
    ContributionId id = new ContributionId(CID, "my-tool");
    AgentToolDefinition definition =
        new AgentToolDefinition(
            new AgentToolId("base.tool"), DESCRIPTOR, ToolVisibility.SELECTABLE);
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
          public ToolExecutionHandle execute(
              ToolExecutionRequest request, ToolExecutionListener listener) {
            return dummyHandle();
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
                "other",
                "1.0",
                "other tool",
                "other",
                SCHEMA,
                ToolSideEffect.READ_ONLY,
                Duration.ZERO);
          }

          @Override
          public ToolRequirements requirements() {
            return requirements;
          }

          @Override
          public ToolExecutionHandle execute(
              ToolExecutionRequest request, ToolExecutionListener listener) {
            return dummyHandle();
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

  private static ToolExecutionHandle dummyHandle() {
    return new ToolExecutionHandle() {
      @Override
      public void cancel() {}

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }

  private static final class DummyBranchView implements BranchView {
    @Override
    public List<CustomStateSnapshot> customEntries(String customType) {
      return List.of();
    }

    @Override
    public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
      return Optional.empty();
    }
  }

  private static final class DummyBoundEnvironment implements BoundEnvironment {
    @Override
    public EnvironmentBinding binding() {
      return new EnvironmentBinding(
          EnvironmentId.parse("11111111-1111-1111-1111-111111111111"), ".");
    }

    @Override
    public ToolExecutionHandle execute(
        EnvironmentCapabilityDescriptor capability,
        ToolExecutionRequest request,
        ToolExecutionListener listener) {
      return dummyHandle();
    }
  }
}
