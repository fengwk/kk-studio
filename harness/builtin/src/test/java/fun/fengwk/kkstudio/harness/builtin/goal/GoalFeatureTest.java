package fun.fengwk.kkstudio.harness.builtin.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用户维护 Goal 的只读读取、goal-id-bound 进度声明与 catalog 冻结测试。
 *
 * <p>目标正文只存在于 branch settings（{@link BranchView#goal()}），Agent 只能读取与声明进度。
 */
class GoalFeatureTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = T0.plusSeconds(1);
  private static final Instant T2 = T0.plusSeconds(2);
  private static final UUID GOAL_ID = new UUID(0L, 42L);
  private static final GoalSnapshot GOAL = new GoalSnapshot(GOAL_ID, "Ship and verify");

  private final GoalProgressCodec codec = new GoalProgressCodec();

  /** Catalog 冻结验证：只有只读读取与进度声明工具，Goal 没有创建工具、也不注册任何 SYSTEM context projector。 */
  @Test
  void catalogFreezesGoalToolsProgressOwnershipAndAccessModes() {
    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributor(
            new ReadTool((request, listener) -> null), stubTool("task", ToolRequirements.none()));
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    assertTrue(catalog.findTool("create_goal").isEmpty());
    assertTrue(catalog.findTool("get_goal").isPresent());
    assertTrue(catalog.findTool("update_goal").isPresent());
    assertEquals(
        ToolVisibility.SELECTABLE,
        catalog.findTool("get_goal").orElseThrow().definition().visibility());
    assertEquals(
        "builtin", catalog.findTool("get_goal").orElseThrow().id().contributorId().value());
    assertEquals(
        new ToolRequirements(
            EnvironmentSupport.NONE,
            List.of(
                new StateDeclaration(
                    BuiltinHarnessContributor.GOAL_PROGRESS_TYPE, StateMode.READ))),
        catalog.findTool("get_goal").orElseThrow().requirements());
    assertEquals(
        new ToolRequirements(
            EnvironmentSupport.NONE,
            List.of(
                new StateDeclaration(
                    BuiltinHarnessContributor.GOAL_PROGRESS_TYPE, StateMode.WRITE))),
        catalog.findTool("update_goal").orElseThrow().requirements());
    assertTrue(
        catalog
            .findCustomEntryType(BuiltinHarnessContributor.ID, GoalFeature.PROGRESS_TYPE)
            .isPresent());
    // Goal 绝不提升为 systemInstruction。
    assertTrue(catalog.contextProjectors().isEmpty());
  }

  /** 两个 Goal Tool 的模型可见 name、副作用与 schema 符合稳定声明。 */
  @Test
  void exposesCanonicalGoalToolDescriptors() {
    ToolDescriptor get = new GetGoalTool().descriptor();
    assertDescriptor(get, GetGoalTool.NAME, ToolSideEffect.READ_ONLY, Set.of(), Set.of());

    ToolDescriptor update = new UpdateGoalTool().descriptor();
    assertDescriptor(
        update,
        UpdateGoalTool.NAME,
        ToolSideEffect.IDEMPOTENT,
        Set.of("status", "reason"),
        Set.of("status", "reason"));
    EnumSchema status =
        assertInstanceOf(EnumSchema.class, update.inputSchema().properties().get("status"));
    assertEquals(List.of("complete", "blocked"), status.values());
    assertInstanceOf(StringSchema.class, update.inputSchema().properties().get("reason"));
  }

  /** 读取语义：Goal 正文来自 settings，进度只取绑定当前 goalId 的报告，历史目标 id 的报告必须被忽略。 */
  @Test
  void getGoalReadsSettingsGoalAndOnlyCurrentGoalProgress() {
    GoalProgress stale = new GoalProgress(new UUID(0L, 41L), GoalStatus.COMPLETE, "old", T0);
    GoalProgress current = new GoalProgress(GOAL_ID, GoalStatus.BLOCKED, "needs input", T1);
    SimpleBranchView branch =
        new SimpleBranchView()
            .withGoal(GOAL)
            .withEntry(GoalProgressCodec.SCHEMA_VERSION, codec.encode(stale))
            .withEntry(GoalProgressCodec.SCHEMA_VERSION, codec.encode(current));

    ToolOutcome outcome = executeTool(new GetGoalTool(), branch, T2, "get-1", "{}");
    assertFalse(outcome.result().error());
    assertTrue(outcome.customEntries().isEmpty());
    String text = textContent(outcome.result());
    assertTrue(text.contains("Ship and verify"));
    assertTrue(text.contains(GOAL_ID.toString()));
    // 只呈现当前 goalId 的报告：旧 id 的 complete 声明不得冒充当前进度。
    assertTrue(text.contains("blocked"));
    assertTrue(text.contains("needs input"));
    assertFalse(text.contains("old"));

    // 用户清除后：明确没有用户设定 Goal，且不再暴露任何进度。
    ToolOutcome cleared = executeTool(new GetGoalTool(), new SimpleBranchView(), T2, "get-2", "{}");
    assertFalse(cleared.result().error());
    assertTrue(textContent(cleared.result()).contains("There is no user-set goal on this branch."));
    assertTrue(textContent(cleared.result()).contains("\"goal\":null"));
  }

  /** 声明语义：进度只绑定当前 goalId，不改动目标正文，也不产生除 goal.progress 以外的任何 effect。 */
  @Test
  void updateGoalRecordsGoalIdBoundProgressOnly() {
    SimpleBranchView branch = new SimpleBranchView().withGoal(GOAL);

    ToolOutcome outcome =
        executeTool(
            new UpdateGoalTool(),
            branch,
            T2,
            "update-1",
            "{\"status\":\"complete\",\"reason\":\"All checks passed\"}");

    assertFalse(outcome.result().error());
    assertEquals(1, outcome.customEntries().size());
    AppendCustomEntry entry = outcome.customEntries().get(0);
    assertEquals("goal.progress", entry.customType());
    assertEquals(GoalProgressCodec.SCHEMA_VERSION, entry.schemaVersion());
    GoalProgress progress =
        codec.decode(new CustomStateSnapshot(entry.schemaVersion(), entry.dataJson()));
    assertEquals(GOAL_ID, progress.goalId());
    assertEquals(GoalStatus.COMPLETE, progress.status());
    assertEquals("All checks passed", progress.reason());
    assertEquals(T2, progress.reportedAt());
    // 目标正文由用户拥有：工具结果只回传声明本身。
    assertFalse(textContent(outcome.result()).contains("Ship and verify"));
  }

  /** 陈旧报告拒绝：当前 Goal 已经有终态声明时再次声明被拒（重复/迟到报告不得覆盖当前进度），没有 Goal 时同样拒绝。 */
  @Test
  void updateGoalRejectsStaleReportAndMissingGoal() {
    GoalProgress reported = new GoalProgress(GOAL_ID, GoalStatus.COMPLETE, "done", T1);
    SimpleBranchView alreadyReported =
        new SimpleBranchView()
            .withGoal(GOAL)
            .withEntry(GoalProgressCodec.SCHEMA_VERSION, codec.encode(reported));

    ToolOutcome late =
        executeTool(
            new UpdateGoalTool(),
            alreadyReported,
            T2,
            "update-late",
            "{\"status\":\"blocked\",\"reason\":\"changed my mind\"}");
    assertTrue(late.result().error());
    assertTrue(late.customEntries().isEmpty());
    assertTrue(textContent(late.result()).contains("already reported complete"));

    ToolOutcome noGoal =
        executeTool(
            new UpdateGoalTool(),
            new SimpleBranchView(),
            T2,
            "update-no-goal",
            "{\"status\":\"complete\",\"reason\":\"nothing\"}");
    assertTrue(noGoal.result().error());
    assertTrue(noGoal.customEntries().isEmpty());
    assertTrue(textContent(noGoal.result()).contains("No goal is set"));
  }

  /** 参数校验：status 只接受 complete/blocked（schema 拦截 active），reason 必须非空。 */
  @Test
  void updateGoalValidatesTerminalStatusAndReason() {
    SimpleBranchView branch = new SimpleBranchView().withGoal(GOAL);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            executeTool(
                new UpdateGoalTool(),
                branch,
                T2,
                "update-active",
                "{\"status\":\"active\",\"reason\":\"r\"}"));

    ToolOutcome blankReason =
        executeTool(
            new UpdateGoalTool(),
            branch,
            T2,
            "update-blank",
            "{\"status\":\"complete\",\"reason\":\"  \"}");
    assertTrue(blankReason.result().error());
    assertTrue(blankReason.customEntries().isEmpty());
  }

  /** 上下文缺失时两个工具都应返回错误且无 effects。 */
  @Test
  void toolsHandleMissingExecutionContextGracefully() {
    ToolOutcome getOutcome = executeToolWithoutContext(new GetGoalTool(), "call-2", "{}");
    assertTrue(getOutcome.result().error());
    assertTrue(getOutcome.customEntries().isEmpty());

    ToolOutcome updateOutcome =
        executeToolWithoutContext(
            new UpdateGoalTool(), "call-3", "{\"status\":\"complete\",\"reason\":\"r\"}");
    assertTrue(updateOutcome.result().error());
    assertTrue(updateOutcome.customEntries().isEmpty());
  }

  /** 验证 terminal-at-most-once：listener 同步抛出时工具不会再次触发 onComplete。 */
  @Test
  void listenerThrowingExceptionDoesNotTriggerSecondaryCallback() {
    SimpleBranchView branch = new SimpleBranchView().withGoal(GOAL);
    AtomicInteger calls = new AtomicInteger();
    ToolExecutionListener throwingListener =
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            calls.incrementAndGet();
            throw new RuntimeException("listener sync failure");
          }

          @Override
          public void onError(Throwable error) {}
        };

    ToolExecutionRequest getRequest =
        new ToolExecutionRequest(
            new GetGoalTool().descriptor(),
            new ToolCall("call-1", "get_goal", "{}"),
            Duration.ZERO,
            new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), T1, branch));
    assertThrows(
        RuntimeException.class, () -> new GetGoalTool().execute(getRequest, throwingListener));
    assertEquals(1, calls.get(), "get_goal must invoke onComplete exactly once");

    calls.set(0);
    ToolExecutionRequest updateRequest =
        new ToolExecutionRequest(
            new UpdateGoalTool().descriptor(),
            new ToolCall("call-2", "update_goal", "{\"status\":\"complete\",\"reason\":\"done\"}"),
            Duration.ZERO,
            new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), T2, branch));
    assertThrows(
        RuntimeException.class,
        () -> new UpdateGoalTool().execute(updateRequest, throwingListener));
    assertEquals(1, calls.get(), "update_goal must invoke onComplete exactly once");
  }

  private static ToolOutcome executeTool(
      Tool tool, BranchView branch, Instant executedAt, String callId, String argumentsJson) {
    ToolExecutionContext context =
        new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), executedAt, branch);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(callId, tool.descriptor().name(), argumentsJson),
            Duration.ZERO,
            context);
    AtomicReference<ToolOutcome> ref = new AtomicReference<>();
    tool.execute(
        request,
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            ref.set(outcome);
          }

          @Override
          public void onError(Throwable error) {}
        });
    assertNotNull(ref.get(), "listener.onComplete must be called synchronously");
    return ref.get();
  }

  private static ToolOutcome executeToolWithoutContext(
      Tool tool, String callId, String argumentsJson) {
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(callId, tool.descriptor().name(), argumentsJson),
            Duration.ZERO);
    AtomicReference<ToolOutcome> ref = new AtomicReference<>();
    tool.execute(
        request,
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            ref.set(outcome);
          }

          @Override
          public void onError(Throwable error) {}
        });
    assertNotNull(ref.get(), "listener.onComplete must be called");
    return ref.get();
  }

  private static String textContent(ToolResult result) {
    return ((TextResultContent) result.contents().get(0)).text();
  }

  private static void assertDescriptor(
      ToolDescriptor descriptor,
      String expectedName,
      ToolSideEffect expectedSideEffect,
      Set<String> requiredParams,
      Set<String> allParams) {
    assertEquals(expectedName, descriptor.name());
    assertEquals(expectedName, descriptor.rendererKey());
    assertEquals(expectedSideEffect, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());
    assertEquals(requiredParams, descriptor.inputSchema().required());
    assertEquals(allParams, descriptor.inputSchema().properties().keySet());
  }

  private static Tool stubTool(String name, ToolRequirements requirements) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            name + " description",
            name,
            new InputSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
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
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return new ToolExecutionHandle() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }
        };
      }
    };
  }

  /** 测试用 branch view：用户 Goal 来自 settings 投影，custom state 只暴露 goal.progress 快照。 */
  private static final class SimpleBranchView implements BranchView {
    private final List<CustomStateSnapshot> entries;
    private final GoalSnapshot goal;

    private SimpleBranchView() {
      this(List.of(), null);
    }

    private SimpleBranchView(List<CustomStateSnapshot> entries, GoalSnapshot goal) {
      this.entries = List.copyOf(entries);
      this.goal = goal;
    }

    private SimpleBranchView withGoal(GoalSnapshot value) {
      return new SimpleBranchView(entries, value);
    }

    private SimpleBranchView withEntry(int version, String dataJson) {
      List<CustomStateSnapshot> next = new ArrayList<>(entries);
      next.add(new CustomStateSnapshot(version, dataJson));
      return new SimpleBranchView(next, goal);
    }

    @Override
    public List<CustomStateSnapshot> customEntries(String customType) {
      if (GoalFeature.PROGRESS_TYPE.equals(customType)) {
        return entries;
      }
      return List.of();
    }

    @Override
    public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
      List<CustomStateSnapshot> list = customEntries(customType);
      return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    @Override
    public Optional<GoalSnapshot> goal() {
      return Optional.ofNullable(goal);
    }
  }
}
