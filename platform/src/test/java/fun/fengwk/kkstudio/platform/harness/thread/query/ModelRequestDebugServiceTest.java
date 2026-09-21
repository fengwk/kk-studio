package fun.fengwk.kkstudio.platform.harness.thread.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.task.SkillPromptEntry;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ModelRequestDebugService 契约：在单次不可变 snapshot 上投影共享规划结果、把确定性拒绝降级为稳定 code 与安全空投影、 只按 request head
 * 前缀物化活动 Invocation 的冻结请求，并且全程只读。
 */
class ModelRequestDebugServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 100L);
  private static final UUID TURN_START_ID = new UUID(0L, 2L);
  private static final UUID USER_ENTRY_ID = new UUID(0L, 3L);
  private static final UUID ASSISTANT_ENTRY_ID = new UUID(0L, 4L);
  private static final UUID MISSING_ENTRY_ID = new UUID(0L, 99L);
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final EnvironmentId ENV_B =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final String CURRENT_COMMIT = "0123456789abcdef0123456789abcdef01234567";
  private static final String LOCAL_PATH = "/data/skills/test-package/dev/SKILL.md";

  /** required-nullable 字段在真实 NON_NULL 序列化配置下也必须显式发射。 */
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private final HarnessRuntime runtime = mock(HarnessRuntime.class);
  private final DatabaseTurnResolver turnResolver = mock(DatabaseTurnResolver.class);

  private final ModelRequestDebugService service =
      new ModelRequestDebugService(runtime, turnResolver, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void projectsTheSharedPlanIntoTheStructuredContract() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLive(THREAD_ID, snapshot.entryPath())).thenReturn(successfulPlan());

    HarnessModelRequestDebugDTO debug = service.getModelRequestDebug(THREAD_ID);

    assertEquals("NEXT_REQUEST_PREVIEW", debug.getKind());
    assertEquals(NOW, debug.getGeneratedAt());
    // model / environment 只来自 branch settings；没有活动 Invocation 时冻结视图为 null。
    assertEquals("provider", debug.getModel().getProviderName());
    assertEquals("model", debug.getModel().getModelName());
    assertEquals("default", debug.getModel().getVariant());
    assertEquals("env-b", debug.getEnvironmentName());
    assertEquals("system instruction", debug.getSystemInstruction());
    assertNull(debug.getPlanningError());
    assertNull(debug.getFrozenInvocation());

    // 候选 Tool 保持共享规划的「SENT 在前、FILTERED 在后」顺序，并携带稳定原因与固定 requiredEnvironmentId。
    assertEquals(
        List.of("read", "bash"),
        debug.getTools().stream().map(HarnessModelRequestDebugDTO.ToolDTO::getName).toList());
    HarnessModelRequestDebugDTO.ToolDTO sent = debug.getTools().getFirst();
    assertEquals("SENT", sent.getState());
    assertNull(sent.getFilterReason());
    assertEquals(EnvironmentSupportDTO.OPTIONAL, sent.getEnvironmentSupport());
    assertNull(sent.getRequiredEnvironmentId());
    assertEquals("builtin:read", sent.getProvenance());
    assertEquals("{\"type\":\"object\"}", sent.getInputSchemaJson());
    HarnessModelRequestDebugDTO.ToolDTO filtered = debug.getTools().get(1);
    assertEquals("FILTERED", filtered.getState());
    assertEquals("ENVIRONMENT_NOT_SELECTED", filtered.getFilterReason());
    assertEquals(EnvironmentSupportDTO.REQUIRED, filtered.getEnvironmentSupport());
    assertEquals(ENV_B.toString(), filtered.getRequiredEnvironmentId());

    // Skill 交付事实与 promptXml：只复用 compose 的同一渲染入口，XML 不可能漂移。
    HarnessModelRequestDebugDTO.SkillDTO skill = debug.getSkills().getFirst();
    assertEquals("test-package", skill.getPackageName());
    assertEquals("dev", skill.getName());
    assertEquals("LOCAL", skill.getDelivery());
    assertEquals(CURRENT_COMMIT, skill.getCurrentCommit());
    assertEquals(CURRENT_COMMIT, skill.getInstalledCommit());
    assertEquals("observed-head", skill.getObservedHeadCommit());
    assertEquals(
        AgentPromptComposer.skillFragment(
            new SkillPromptEntry("dev", "dev description", LOCAL_PATH)),
        skill.getPromptXml());

    assertEquals(
        List.of("coder"),
        debug.getSubagents().stream()
            .map(HarnessModelRequestDebugDTO.SubagentDTO::getName)
            .toList());
    assertEquals("SHORT", debug.getCacheControl().getRetention());
    assertEquals("pc2-key", debug.getCacheControl().getAffinityKey());
    assertEquals(List.of("SYSTEM", "TOOLS"), debug.getCacheControl().getBreakpoints());
  }

  /**
   * 测试意图：预览是单次不可变 snapshot 上的纯查询——只读取一次 Thread snapshot，并把该 snapshot 的 path 原样交给共享规划， 不做任何写入或控制面调用。
   */
  @Test
  void readsOneImmutableSnapshotAndNeverMutates() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLive(THREAD_ID, snapshot.entryPath())).thenReturn(successfulPlan());

    service.getModelRequestDebug(THREAD_ID);

    verify(runtime).getThreadSnapshot(THREAD_ID);
    verifyNoMoreInteractions(runtime);
    verify(turnResolver).planLive(THREAD_ID, snapshot.entryPath());
    verifyNoMoreInteractions(turnResolver);
  }

  /** 测试意图：确定性 planning 拒绝只投影稳定 code 与安全空投影，自由文本 detail 绝不进入对外投影。 */
  @Test
  void projectsDeterministicRejectionAsStableCodeAndSafeEmptyProjection() throws IOException {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLive(THREAD_ID, snapshot.entryPath()))
        .thenReturn(
            new LiveTurnPlan.Rejected(
                DatabaseTurnResolver.REJECTION_CODE, "agent not found: ghost"));

    HarnessModelRequestDebugDTO debug = service.getModelRequestDebug(THREAD_ID);

    assertEquals(DatabaseTurnResolver.REJECTION_CODE, debug.getPlanningError());
    assertEquals("", debug.getSystemInstruction());
    assertEquals(List.of(), debug.getTools());
    assertEquals(List.of(), debug.getSkills());
    assertEquals(List.of(), debug.getSubagents());
    assertNull(debug.getCacheControl());
    assertNull(debug.getFrozenInvocation());
    // branch settings 的 model / environment 与生成时间不因 planning 失败而丢失。
    assertEquals("provider", debug.getModel().getProviderName());
    assertEquals("env-b", debug.getEnvironmentName());

    String json = JSON.writeValueAsString(debug);
    assertTrue(
        json.contains("\"planningError\":\"" + DatabaseTurnResolver.REJECTION_CODE + "\""), json);
    // required-nullable 字段在 NON_NULL 配置下仍显式发射 null，客户端不会把「未返回」当成「无值」。
    assertTrue(json.contains("\"frozenInvocation\":null"), json);
    assertFalse(json.contains("agent not found"), json);
  }

  /**
   * 测试意图：活动 Invocation 的冻结请求必须物化在 root-to-requestHead 前缀上。Tool context 的当前 head 是 Assistant
   * 结果（request head 的后代），用当前 head 会把后续结果投影进请求，因此这里显式断言它不出现。
   */
  @Test
  void materializesFrozenInvocationAgainstTheRequestHeadPrefixInsteadOfTheToolContextHead() {
    ThreadSnapshot snapshot = toolContextSnapshot(USER_ENTRY_ID);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLive(THREAD_ID, snapshot.entryPath())).thenReturn(successfulPlan());

    HarnessModelRequestDebugDTO.FrozenInvocationDTO frozen =
        service.getModelRequestDebug(THREAD_ID).getFrozenInvocation();

    assertEquals("FROZEN_INVOCATION", frozen.getKind());
    ProviderRequest materialized = new ProviderRequestJsonCodec().decode(frozen.getRequestJson());
    assertEquals("system instruction", materialized.systemInstruction());
    assertEquals(1, materialized.messages().size());
    assertEquals(
        "user-1",
        ((ProviderTextBlock) materialized.messages().getFirst().contents().getFirst()).text());
    assertFalse(frozen.getRequestJson().contains("assistant-reply"), frozen.getRequestJson());
  }

  /** 测试意图：当前预览被确定性拒绝时，活动 Invocation 的冻结事实仍然独立成立；反之没有活动 Model 时冻结视图必须缺席。 */
  @Test
  void keepsFrozenInvocationWhenThePreviewRejectsAndOmitsItWithoutActiveModel() {
    ThreadSnapshot rejectedSnapshot = toolContextSnapshot(USER_ENTRY_ID);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(rejectedSnapshot);
    when(turnResolver.planLive(THREAD_ID, rejectedSnapshot.entryPath()))
        .thenReturn(
            new LiveTurnPlan.Rejected(DatabaseTurnResolver.REJECTION_CODE, "agent not found"));

    HarnessModelRequestDebugDTO debug = service.getModelRequestDebug(THREAD_ID);

    assertEquals(DatabaseTurnResolver.REJECTION_CODE, debug.getPlanningError());
    assertEquals("FROZEN_INVOCATION", debug.getFrozenInvocation().getKind());

    ThreadSnapshot idle = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(idle);
    when(turnResolver.planLive(THREAD_ID, idle.entryPath()))
        .thenReturn(
            new LiveTurnPlan.Rejected(DatabaseTurnResolver.REJECTION_CODE, "agent not found"));
    assertNull(service.getModelRequestDebug(THREAD_ID).getFrozenInvocation());
  }

  /** 测试意图：request head 不在当前 Thread 路径上说明 durable 不变量已破坏，必须 fail closed 而不是投影错误请求。 */
  @Test
  void failsClosedWhenTheFrozenRequestHeadIsNotOnTheThreadPath() {
    ThreadSnapshot snapshot = toolContextSnapshot(MISSING_ENTRY_ID);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLive(THREAD_ID, snapshot.entryPath())).thenReturn(successfulPlan());

    assertThrows(IllegalStateException.class, () -> service.getModelRequestDebug(THREAD_ID));
  }

  /** 测试意图：基础设施/编程异常必须原样传播，绝不被误分类为 planning 失败。 */
  @Test
  void propagatesInfrastructureFailuresInsteadOfProjectingPlanningFailure() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLive(THREAD_ID, snapshot.entryPath()))
        .thenThrow(new IllegalStateException("catalog is down"));

    assertThrows(IllegalStateException.class, () -> service.getModelRequestDebug(THREAD_ID));
  }

  /**
   * 测试意图：旧的展示文本预览服务及其装配/测试必须彻底删除，Debug 只保留结构化投影这一条路径。这里扫描源码而不是 {@code Class.forName}，以免 target/
   * 下的陈旧 class 文件掩盖真实状态。
   */
  @Test
  void obsoleteTextPreviewServiceAndWiringAreGone() throws IOException {
    // 标记按片段拼接，避免本测试文件自身命中扫描。
    String obsoleteMarker = "SystemPrompt" + "Preview" + "Service";
    Path reactorRoot = reactorRoot();
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> paths =
        Stream.concat(
            Files.walk(reactorRoot.resolve("platform/src")),
            Files.walk(reactorRoot.resolve("web/src")))) {
      for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
        if (Files.readString(path, StandardCharsets.UTF_8).contains(obsoleteMarker)) {
          offenders.add(reactorRoot.relativize(path).toString());
        }
      }
    }
    assertTrue(offenders.isEmpty(), () -> "obsolete preview sources remain: " + offenders);
  }

  private static ThreadSnapshot idleSnapshot() {
    EntryPath path = basePath();
    return new ThreadSnapshot(thread(USER_ENTRY_ID), path, List.of(), null, List.of(), List.of());
  }

  /** Tool context：head 是 Assistant 结果，而活动 Invocation 的 request head 是更早的 USER Entry。 */
  private static ThreadSnapshot toolContextSnapshot(UUID requestHeadEntryId) {
    EntryPath path = assistantHeadPath();
    ModelInvocation model =
        new ModelInvocation(
            new UUID(0L, 50L),
            THREAD_ID,
            TURN_START_ID,
            requestHeadEntryId,
            requestSpec(),
            ModelInvocationStatus.READY,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            NOW,
            NOW);
    return new ThreadSnapshot(
        thread(ASSISTANT_ENTRY_ID), path, List.of(), model, List.of(), List.of());
  }

  private static EntryPath basePath() {
    return new EntryPath(
        List.of(
            new Entry(SESSION_ID, SESSION_ID, null, new RootPayload(branchSettings()), NOW),
            new Entry(
                TURN_START_ID,
                SESSION_ID,
                SESSION_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), THREAD_ID),
                NOW),
            new Entry(
                USER_ENTRY_ID,
                SESSION_ID,
                TURN_START_ID,
                message(AgentMessageRole.USER, "user-1"),
                NOW)));
  }

  private static EntryPath assistantHeadPath() {
    List<Entry> entries = new ArrayList<>(basePath().entries());
    entries.add(
        new Entry(
            ASSISTANT_ENTRY_ID,
            SESSION_ID,
            USER_ENTRY_ID,
            message(AgentMessageRole.ASSISTANT, "assistant-reply"),
            NOW));
    return new EntryPath(entries);
  }

  private static MessagePayload message(AgentMessageRole role, String text) {
    AgentMessage message = new AgentMessage(role, List.of(new TextMessageContent(text)));
    if (role == AgentMessageRole.ASSISTANT) {
      return new MessagePayload(
          message,
          new AssistantMessageMetadata(
              GenerationStopReason.COMPLETE,
              new ModelUsage(1, 1, 0, 0, 0, 0, 2),
              new ModelCost(
                  "USD",
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO)),
          null);
    }
    return new MessagePayload(message, null, null);
  }

  private static ThreadState thread(UUID headEntryId) {
    return new ThreadState(
        THREAD_ID, SESSION_ID, headEntryId, CREATION_REQUEST_HASH, "thread", false, 1, 0, NOW, NOW);
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings(
        "assistant", new ModelSelection("provider", "model", "default"), "env-b");
  }

  /** 成功共享规划：SENT 候选在前、FILTERED 候选在后，附 Skill 交付事实、subagent 与 cache control。 */
  private static LiveTurnPlan.Planned successfulPlan() {
    return new LiveTurnPlan.Planned(
        requestSpec(),
        4096,
        List.of(
            new LiveTurnPlan.CandidateTool(
                "read",
                "Read a file.",
                "{\"type\":\"object\"}",
                EnvironmentSupport.OPTIONAL,
                null,
                "builtin:read",
                LiveTurnPlan.ToolState.SENT,
                null),
            new LiveTurnPlan.CandidateTool(
                "bash",
                "Run a command.",
                "{\"type\":\"object\"}",
                EnvironmentSupport.REQUIRED,
                ENV_B,
                "builtin:environment.bash",
                LiveTurnPlan.ToolState.FILTERED,
                LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED)),
        List.of(
            new LiveTurnPlan.PlannedSkill(
                "test-package",
                "dev",
                "dev description",
                LOCAL_PATH,
                SkillPromptResolution.Delivery.LOCAL,
                CURRENT_COMMIT,
                "observed-head",
                CURRENT_COMMIT)));
  }

  private static ModelRequestSpec requestSpec() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 42L),
        new ModelDescriptor(
            "provider",
            "model",
            "wire-model",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            new ModelPricing(
                "USD",
                "standard",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
        // 冻结请求只携带 Agent 正文唯一的 systemInstruction 与零 tool binding。
        new ModelVariant("default"),
        512,
        "system instruction",
        List.of(),
        List.of(new SubagentBinding("coder", "Codes solutions.")),
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT,
            "pc2-key",
            Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)));
  }

  /** 从当前工作目录向上定位 reactor 根（同时含 platform 与 web 模块源码）。 */
  private static Path reactorRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("platform/src/main/java"))
          && Files.isDirectory(current.resolve("web/src/main/java"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("cannot locate reactor root for source scan");
  }
}
