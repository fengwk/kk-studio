package fun.fengwk.kkstudio.core.harness.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.harness.thread.store.MysqlHarnessThreadStore;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.tool.DatabaseThreadToolPort;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.core.harness.usage.store.mapper.ModelUsageRecordMapper;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtension;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.AssistantCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.CompactionCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ThreadIdle;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.TurnStarted;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.CompactionService;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResourceResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResources;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic ThreadProcessor integration with a controlled Fake ModelProvider.
 *
 * <p>Proves durable main-loop boundaries, fencing, Tool convergence, and recovery-safe state
 * transitions.
 */
@SpringBootTest
class ThreadProcessorIntegrationTest {
  private static final SessionEntryJsonCodec SESSION_ENTRY_CODEC = new SessionEntryJsonCodec();
  private static final ToolDescriptor RETRY_TEST_TOOL =
      new ToolDescriptor(
          "retry_tool",
          "1",
          "retry tool",
          null,
          new ToolParamsSchema("", Map.of(), Set.of(), false),
          ToolExecutionLocation.PLATFORM,
          ToolSideEffect.READ_ONLY,
          Duration.ofMinutes(1));

  @Autowired private HarnessThreadCommandService commandService;
  @Autowired private HarnessSessionCommandService sessionCommandService;
  @Autowired private HarnessThreadQueryService queryService;
  @Autowired private ThreadProcessor threadProcessor;
  @Autowired private ThreadTransactions transactions;
  @Autowired private MysqlHarnessThreadStore threadStore;
  @Autowired private ModelUsageRecordMapper usageMapper;
  @Autowired private ControlledFakeProvider fakeProvider;
  @Autowired private DatabaseThreadToolPort toolPort;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private HarnessThreadEventMapper threadEventMapper;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessSessionEntryMapper entryMapper;
  @Autowired private ThreadIdGenerator idGenerator;
  @Autowired private LifecycleProbe lifecycleProbe;
  @Autowired private MutableRetryPolicyResolver retryPolicyResolver;

  @BeforeEach
  void resetProvider() {
    fakeProvider.reset();
    lifecycleProbe.reset();
    retryPolicyResolver.reset();
  }

  /**
   * Turn admission rejection writes ASSISTANT_FAILED + THREAD_FAILED and never calls Provider.
   * Cancellation / maxTurns apply only at this Turn boundary; in-flight LLM work is not
   * interrupted.
   */
  @Test
  void turnAdmissionRejectionFailsWithoutCallingProvider() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-admission");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    long sessionId = HarnessIds.parsePositive(thread.getSessionId(), "sessionId");
    awaitIdle(threadId);

    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(7_700_001L + (threadId % 100_000));
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(threadId);
    task.setChildSessionId(sessionId);
    task.setChildThreadId(threadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(1);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);

    HarnessThreadEventDO priorTurn = new HarnessThreadEventDO();
    priorTurn.setId(7_700_101L + (threadId % 100_000));
    priorTurn.setThreadId(threadId);
    priorTurn.setEventType("turn_started");
    priorTurn.setPayloadJson("{\"schemaVersion\":1}");
    priorTurn.setCreateTime(now.minusSeconds(5));
    threadEventMapper.insert(priorTurn);

    int before = fakeProvider.requestsSinceReset();
    // Enqueue durable work without relying on async kick ordering, then process synchronously.
    submitReturn(thread.getThreadId(), "should-not-call-model", "cid-admission-" + threadId);
    threadProcessor.process(threadId);
    awaitFailed(threadId);

    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(events.stream().anyMatch(e -> "assistant_failed".equals(e.getEventType())));
    assertTrue(events.stream().anyMatch(e -> "thread_failed".equals(e.getEventType())));
    assertTrue(
        events.stream()
            .filter(e -> "thread_failed".equals(e.getEventType()))
            .anyMatch(e -> e.getPayloadJson() != null && e.getPayloadJson().contains("maxTurns")));
    assertEquals(before, fakeProvider.requestsSinceReset());
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  /**
   * Policy admission rejection force-releases the processor token even when a later input is still
   * pending, so the same rejection cannot spin/retain like a provider failure retry path.
   */
  @Test
  void turnAdmissionRejectionReleasesDespitePendingLaterInput() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-admission-pending");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    long sessionId = HarnessIds.parsePositive(thread.getSessionId(), "sessionId");
    awaitIdle(threadId);

    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(7_700_011L + (threadId % 100_000));
    task.setParentSessionId(1L);
    task.setParentThreadId(2L);
    task.setRootThreadId(threadId);
    task.setChildSessionId(sessionId);
    task.setChildThreadId(threadId);
    task.setTargetAgent("sub");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(1);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(now);
    task.setUpdateTime(now);
    taskMapper.insert(task);

    HarnessThreadEventDO priorTurn = new HarnessThreadEventDO();
    priorTurn.setId(7_700_111L + (threadId % 100_000));
    priorTurn.setThreadId(threadId);
    priorTurn.setEventType("turn_started");
    priorTurn.setPayloadJson("{\"schemaVersion\":1}");
    priorTurn.setCreateTime(now.minusSeconds(5));
    threadEventMapper.insert(priorTurn);

    int before = fakeProvider.requestsSinceReset();
    // Queue two durable inputs; admission rejects at first model boundary.
    submitReturn(thread.getThreadId(), "blocked-first", "cid-adm-p1-" + threadId);
    submitReturn(thread.getThreadId(), "blocked-second", "cid-adm-p2-" + threadId);
    threadProcessor.process(threadId);
    awaitFailed(threadId);

    assertEquals(before, fakeProvider.requestsSinceReset());
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
    List<HarnessThreadInputDTO> inputs = queryService.listInputs(thread.getThreadId());
    // createRoot 会先应用 bootstrap SET_AGENT；这里断言的是后续两条用户输入都被 apply。
    List<HarnessThreadInputDTO> userInputs = userMessageInputs(inputs);
    assertEquals(2, userInputs.size());
    // Steer-all 可在 admission 前应用完整 cutoff；无论输入是否已应用，FAILED 均不得保留 token 自旋。
    assertTrue(inputs.stream().allMatch(i -> i.getAppliedEntryId() != null));

    // A second activation must still not call Provider or hang with a retained token.
    threadProcessor.process(threadId);
    assertEquals(before, fakeProvider.requestsSinceReset());
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  @Test
  void noToolModelTurnAppliesInputPersistsAssistantUsageEventsAndReleasesToken() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-final");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    submit(thread.getThreadId(), "hello-a", "cid-a-" + threadId);
    awaitIdle(threadId);

    List<HarnessThreadInputDTO> inputs = queryService.listInputs(thread.getThreadId());
    List<HarnessThreadInputDTO> userInputs = userMessageInputs(inputs);
    assertEquals(1, userInputs.size());
    assertNotNull(userInputs.get(0).getAppliedEntryId());
    assertTrue(
        inputs.stream()
            .filter(i -> "SET_AGENT".equals(i.getInputType()))
            .allMatch(i -> i.getAppliedEntryId() != null));

    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    assertTrue(path.size() >= 3, "agent_change + user + assistant expected, got " + path.size());
    assertEquals("message", path.get(path.size() - 1).getEntryType());

    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(events.stream().anyMatch(e -> "assistant_completed".equals(e.getEventType())));
    assertTrue(events.stream().anyMatch(e -> "thread_idle".equals(e.getEventType())));

    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
    assertEquals(1, fakeProvider.requestsSinceReset());
    long assistantEntryId =
        HarnessIds.parsePositive(path.get(path.size() - 1).getEntryId(), "assistantEntryId");
    assertNotNull(usageMapper.findByAssistantEntryId(assistantEntryId));
    assertTrue(lifecycleProbe.contextTransformCount() > 0);
    assertTrue(lifecycleProbe.contains(threadId, TurnStarted.class));
    assertTrue(lifecycleProbe.contains(threadId, AssistantCompleted.class));
    assertTrue(lifecycleProbe.contains(threadId, ThreadIdle.class));
  }

  /** 配置-only harvest（SET_YOLO）只更新 Thread 状态与事件，不写 Session Entry，也不得调用 Provider。 */
  @Test
  void configOnlyBatchAppliesWithoutCallingProvider() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-config-only");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    long headBefore = threadStore.find(threadId).orElseThrow().headEntryId();

    transactions.submitSetYolo(threadId, true, "cid-yolo-" + threadId, Instant.now());
    threadProcessor.process(threadId);
    awaitIdle(threadId);

    assertEquals(0, fakeProvider.requestsSinceReset());
    List<HarnessThreadInputDTO> inputs = queryService.listInputs(thread.getThreadId());
    List<HarnessThreadInputDTO> yoloInputs =
        inputs.stream().filter(i -> "SET_YOLO".equals(i.getInputType())).toList();
    assertEquals(1, yoloInputs.size());
    assertNotNull(yoloInputs.get(0).getAppliedEntryId());
    assertTrue(inputs.stream().allMatch(i -> i.getAppliedEntryId() != null));
    assertTrue(queryService.getThread(thread.getThreadId()).getYoloEnabled());
    // SET_YOLO 不推进 head Entry；head 仍停在 bootstrap 的 model_change。
    assertEquals(headBefore, threadStore.find(threadId).orElseThrow().headEntryId());
    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    assertEquals("model_change", path.get(path.size() - 1).getEntryType());
    assertTrue(
        queryService.listEvents(thread.getThreadId(), 0, 100).stream()
            .anyMatch(e -> "yolo_changed".equals(e.getEventType())));
  }

  @Test
  void overflowRunsCompactionExtensionAndPublishesDurableObservation() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-compaction-extension");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failNext(ProviderErrorKind.OVERFLOW, "compact me");

    submit(thread.getThreadId(), "long context", "cid-compact-" + threadId);
    awaitIdle(threadId);

    assertEquals(2, fakeProvider.requestsSinceReset());
    assertTrue(lifecycleProbe.beforeCompactionCount() > 0);
    assertTrue(lifecycleProbe.contains(threadId, CompactionCompleted.class));
    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(events.stream().anyMatch(e -> "compaction_completed".equals(e.getEventType())));
  }

  @Test
  void messageArrivingDuringModelTurnIsConsumedAtNextBoundary() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-boundary");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");

    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fakeProvider.blockNext(entered, release, Duration.ofSeconds(5));
    submit(thread.getThreadId(), "first", "cid-1-" + threadId);
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    // 第二条在 Provider in-flight 时入队，只能由完成后的下一个安全边界处理。
    submit(thread.getThreadId(), "second", "cid-2-" + threadId);
    release.countDown();
    awaitIdle(threadId);

    List<HarnessThreadInputDTO> userInputs =
        userMessageInputs(queryService.listInputs(thread.getThreadId()));
    assertEquals(2, userInputs.size());
    assertNotNull(userInputs.get(0).getAppliedEntryId());
    assertNotNull(userInputs.get(1).getAppliedEntryId());
    assertEquals(2, fakeProvider.requestsSinceReset());
    assertEquals(List.of(1, 2), fakeProvider.completionOrder());

    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    long messageCount = path.stream().filter(e -> "message".equals(e.getEntryType())).count();
    // Path is head-cursor ordered; both user inputs applied implies two model turns produced
    // assistant entries on the active head path.
    assertTrue(
        messageCount >= 3 && fakeProvider.requestsSinceReset() == 2,
        "expected multi-turn path with two provider completions, messages="
            + messageCount
            + " requests="
            + fakeProvider.requestsSinceReset());
  }

  @Test
  void quiescenceLosesToConcurrentEnqueueAndKeepsOwnershipForContinuation() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-race");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    Instant now = Instant.now();
    markRunnable(threadId, now);

    assertTrue(
        threadStore.tryAcquire(threadId, "owner-a", now, Duration.ofSeconds(30)).isPresent());

    submit(thread.getThreadId(), "late-msg", "cid-late-" + threadId);

    ThreadTransactions.QuiescenceResult quiescence =
        transactions.quiesce(threadId, "owner-a", now.plusSeconds(1));
    assertEquals(
        ThreadTransactions.QuiescenceResult.WORK_REMAINS,
        quiescence,
        "pending input must prevent idle release");
    assertEquals("owner-a", threadStore.find(threadId).orElseThrow().processorToken());

    threadStore.release(threadId, "owner-a", now.plusSeconds(2));
    threadProcessor.process(threadId);
    awaitIdle(threadId);

    assertTrue(
        queryService.listInputs(thread.getThreadId()).stream()
            .anyMatch(i -> i.getAppliedEntryId() != null));
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  @Test
  void siblingThreadChildrenDoNotBlockThisThreadToolResultPendingDetection() {
    long headAssistantId = 50L;
    long threadA = 901L;
    long threadB = 902L;
    insertTerminalInvocation(8001L, threadA, headAssistantId);

    assertTrue(toolPort.hasTerminalResultsPendingApply(threadA, headAssistantId));
    assertFalse(toolPort.hasTerminalResultsPendingApply(threadB, headAssistantId));
  }

  /** 共享 Entry tree 的其他 branch child 不能阻止源 Thread 以自身 head fencing 写入 Tool result。 */
  @Test
  void siblingBranchChildDoesNotBlockApplyingThisThreadsTerminalToolResult() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-shared-tree-tool-result");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    Instant now = Instant.now();
    markRunnable(threadId, now);
    assertTrue(
        threadStore.tryAcquire(threadId, "owner-shared", now, Duration.ofSeconds(30)).isPresent());
    var owned = threadStore.find(threadId).orElseThrow();

    HarnessSessionEntryDO siblingChild = new HarnessSessionEntryDO();
    siblingChild.setId(idGenerator.newSessionEntryId());
    siblingChild.setSessionId(owned.sessionId());
    siblingChild.setParentEntryId(owned.headEntryId());
    siblingChild.setEntryType("message");
    siblingChild.setPayloadJson(
        SESSION_ENTRY_CODEC.encode(
            new MessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.USER, List.of(new TextMessageContent("sibling"))))));
    siblingChild.setCreateTime(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    entryMapper.insert(siblingChild);
    insertTerminalInvocation(8_003L + threadId % 1000, threadId, owned.headEntryId());

    assertTrue(transactions.applyTerminalToolResults(threadId, "owner-shared", now.plusSeconds(1)));
    assertNotEquals(
        owned.headEntryId(),
        threadStore.find(threadId).orElseThrow().headEntryId(),
        "own head advances");
    threadStore.release(threadId, "owner-shared", now.plusSeconds(2));
  }

  @Test
  void quiescenceKeepsTokenWhenTerminalToolResultsPendingApply() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-tool-race");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    Instant now = Instant.now();
    markRunnable(threadId, now);

    assertTrue(
        threadStore.tryAcquire(threadId, "owner-tool", now, Duration.ofSeconds(30)).isPresent());
    long head = threadStore.find(threadId).orElseThrow().headEntryId();
    // Simulate tool terminal callback committed while processor still holds token and is about to
    // release: durable work must block idle release so kick's failed acquire is not stranded.
    insertTerminalInvocation(8002L + threadId % 1000, threadId, head);

    ThreadTransactions.QuiescenceResult quiescence =
        transactions.quiesce(threadId, "owner-tool", now.plusSeconds(1));
    assertEquals(
        ThreadTransactions.QuiescenceResult.WORK_REMAINS,
        quiescence,
        "terminal tool results pending apply must prevent idle release");
    assertEquals("owner-tool", threadStore.find(threadId).orElseThrow().processorToken());

    threadStore.release(threadId, "owner-tool", now.plusSeconds(2));
  }

  @Test
  void steerAllPrequeuedUserInputsProduceOneProviderRequest() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-prequeue");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    Instant now = Instant.now();
    AgentMessage u1 =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("first-prequeued")));
    AgentMessage u2 =
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent("second-prequeued")));
    // 直接入队、不 kick，验证首次安全边界 harvest 全部消息并只偿还一次 response debt。
    transactions.submitUserMessage(threadId, u1, "pre-1-" + threadId, now);
    transactions.submitUserMessage(threadId, u2, "pre-2-" + threadId, now);
    threadProcessor.process(threadId);
    awaitIdle(threadId);

    assertEquals(1, fakeProvider.requestsSinceReset());
    assertEquals(List.of(1), fakeProvider.completionOrder());
    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    List<AgentMessageRole> roles = new ArrayList<>();
    for (HarnessSessionEntryDTO entry : path) {
      if (!"message".equals(entry.getEntryType())) {
        continue;
      }
      SessionEntryPayload payload =
          SESSION_ENTRY_CODEC.decode(SessionEntryType.MESSAGE, entry.getPayloadJson());
      assertTrue(payload instanceof MessageEntryPayload);
      roles.add(((MessageEntryPayload) payload).message().role());
    }
    assertEquals(
        List.of(AgentMessageRole.USER, AgentMessageRole.USER, AgentMessageRole.ASSISTANT), roles);
  }

  @Test
  void shortLeaseHeartbeatBlocksSecondAcquireDuringLongProviderCall() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-lease");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch releaseProvider = new CountDownLatch(1);
    // 阻塞 >2 个 150ms lease，证明 heartbeat 续租。
    fakeProvider.blockNext(entered, releaseProvider, Duration.ofMillis(400));
    submit(thread.getThreadId(), "lease-msg", "cid-lease-" + threadId);
    assertTrue(entered.await(3, TimeUnit.SECONDS), "provider should start");
    Thread.sleep(350);
    assertTrue(
        threadStore
            .tryAcquire(threadId, "intruder", Instant.now(), Duration.ofMillis(50))
            .isEmpty(),
        "after >2 leases, intruder must still fail because heartbeat renewed");
    releaseProvider.countDown();
    awaitIdle(threadId);
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  @Test
  void providerConfiguredTotalTimeoutCancelsAStalledTurn() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-provider-total-timeout");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.setModelCallTimeoutPolicy(
        new ModelCallTimeoutPolicy(Duration.ofMillis(80), Duration.ofSeconds(5)));
    fakeProvider.stallNext();

    submit(thread.getThreadId(), "timeout", "cid-total-timeout-" + threadId);

    awaitFailed(threadId);
    assertTrue(fakeProvider.latestStreamCancelled());
    assertTrue(
        threadEventMapper
            .findLatestFailure(threadId)
            .getPayloadJson()
            .contains("model call timed out"));
  }

  @Test
  void providerConfiguredIdleTimeoutCancelsAStalledTurn() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-provider-idle-timeout");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.setModelCallTimeoutPolicy(
        new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofMillis(80)));
    fakeProvider.stallNext();

    submit(thread.getThreadId(), "idle timeout", "cid-idle-timeout-" + threadId);

    awaitFailed(threadId);
    assertTrue(fakeProvider.latestStreamCancelled());
    assertTrue(
        threadEventMapper
            .findLatestFailure(threadId)
            .getPayloadJson()
            .contains("model call idle timed out"));
  }

  @Test
  void providerDeltasResetTheConfiguredIdleTimeout() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-provider-idle-activity");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.setModelCallTimeoutPolicy(
        new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)));
    fakeProvider.emitTextDeltas(Duration.ofMillis(200), "a", "b", "c", "d", "e", "f", "g");

    submit(thread.getThreadId(), "stay active", "cid-idle-activity-" + threadId);

    awaitIdle(threadId);
    assertFalse(fakeProvider.latestStreamCancelled());
    HarnessThreadEventDO failure = threadEventMapper.findLatestFailure(threadId);
    assertNull(failure, () -> failure.getPayloadJson());
    assertEquals(1, fakeProvider.requestsSinceReset());
  }

  @Test
  void failedTurnKeepsConcurrentSecondUserQueuedAfterFailure() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-fail-concurrent");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch releaseFail = new CountDownLatch(1);
    // 第一次模型阻塞，期间提交第二条 USER；失败后必须保留而不能自动 harvest。
    fakeProvider.blockThenFail(
        entered, releaseFail, ProviderErrorKind.INVALID_REQUEST, "fail-first");
    submit(thread.getThreadId(), "first-will-fail", "cid-f1-" + threadId);
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    submit(thread.getThreadId(), "second-while-failing", "cid-f2-" + threadId);
    releaseFail.countDown();
    awaitFailed(threadId);

    List<HarnessThreadInputDTO> userInputs =
        userMessageInputs(queryService.listInputs(thread.getThreadId()));
    assertEquals(2, userInputs.size());
    assertNotNull(userInputs.get(0).getAppliedEntryId());
    assertNull(userInputs.get(1).getAppliedEntryId());
    assertEquals(1, fakeProvider.requestsSinceReset());
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  @Test
  void failedTurnRestartsWhenNewUserMessageArrives() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-fail-later");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failNext(ProviderErrorKind.INVALID_REQUEST, "first-fail");
    submit(thread.getThreadId(), "will-fail", "cid-fail1-" + threadId);
    awaitFailed(threadId);
    assertEquals(1, fakeProvider.requestsSinceReset());

    // 用户消息是重新启动信号；它不直接重放失败 turn，而是恢复普通 mailbox loop。
    fakeProvider.reset();
    submit(thread.getThreadId(), "after-fail", "cid-after-fail-" + threadId);
    awaitIdle(threadId);
    assertEquals(1, fakeProvider.requestsSinceReset());
    assertTrue(
        userMessageInputs(queryService.listInputs(thread.getThreadId())).stream()
            .allMatch(input -> input.getAppliedEntryId() != null));
  }

  /** 自动 RETRYING Tool chain 释放 token 但保留 debt，直到 follow-up assistant 完成前不得 harvest 新输入。 */
  @Test
  void automaticRetryRetainsDebtUntilFollowUpAssistantBeforeHarvestingLaterInput()
      throws Exception {
    HarnessThreadDTO thread = createRoot("proc-retry-tool-wait");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    retryPolicyResolver.set(
        new InvocationRetryPolicy(
            1,
            InvocationRetryBackoffStrategy.FIXED,
            Duration.ofMillis(200),
            Duration.ofMillis(200)));
    fakeProvider.failNext(ProviderErrorKind.TRANSIENT, "first-fail");
    fakeProvider.completeNextWithToolCall();
    submit(thread.getThreadId(), "will-fail", "cid-retry-tool-fail-" + threadId);
    awaitRetryScheduled(threadId);

    submit(thread.getThreadId(), "queued-behind-retry-debt", "cid-retry-tool-later-" + threadId);
    awaitRetryingToolWait(threadId);

    List<HarnessThreadInputDTO> userInputs =
        userMessageInputs(queryService.listInputs(thread.getThreadId()));
    assertEquals(2, userInputs.size());
    assertNotNull(userInputs.get(0).getAppliedEntryId());
    assertNull(
        userInputs.get(1).getAppliedEntryId(), "retry debt must block later mailbox harvest");
    ToolInvocationDO invocation = invocationMapper.listByThread(threadId).get(0);
    assertEquals("WAITING_APPROVAL", invocation.getStatus());

    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    assertEquals(
        1,
        invocationMapper.resolvePermission(
            invocation.getId(),
            "WAITING_APPROVAL",
            "DENY",
            "FAILED",
            now,
            "{\"toolCallId\":\"retry-tool-call\",\"contents\":[{\"type\":\"text\",\"text\":\"denied\"}],\"error\":true,\"details\":{}}",
            "denied",
            now,
            now));

    threadProcessor.process(threadId);
    awaitIdle(threadId);
    assertNotNull(
        userMessageInputs(queryService.listInputs(thread.getThreadId())).get(1).getAppliedEntryId(),
        "only the final retry assistant may clear retry debt and harvest later input");
  }

  @Test
  void transientFailureExhaustsAutomaticRetriesThenNewInputRestartsTheLoop() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-retry-exhausted");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    retryPolicyResolver.set(
        new InvocationRetryPolicy(
            1,
            InvocationRetryBackoffStrategy.FIXED,
            Duration.ofMillis(100),
            Duration.ofMillis(100)));
    fakeProvider.failNext(ProviderErrorKind.TRANSIENT, "first-transient");
    fakeProvider.failNext(ProviderErrorKind.TRANSIENT, "second-transient");

    submit(thread.getThreadId(), "retry me", "cid-retry-exhausted-first-" + threadId);
    awaitRetryScheduled(threadId);
    assertFalse(
        queryService.listPathEntries(thread.getThreadId()).stream()
            .map(
                entry ->
                    SESSION_ENTRY_CODEC.decode(
                        SessionEntryType.fromValue(entry.getEntryType()), entry.getPayloadJson()))
            .filter(MessageEntryPayload.class::isInstance)
            .map(MessageEntryPayload.class::cast)
            .anyMatch(payload -> payload.message().role() == AgentMessageRole.ASSISTANT),
        "automatic retry must not materialize a failed assistant entry");
    awaitFailed(threadId);

    assertEquals(2, fakeProvider.requestsSinceReset());
    assertEquals("FAILED", threadStore.find(threadId).orElseThrow().status().name());
    assertEquals(1, threadStore.find(threadId).orElseThrow().retryAttempt());
    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertEquals(
        1,
        events.stream()
            .filter(event -> "thread_retry_scheduled".equals(event.getEventType()))
            .count());
    assertTrue(
        events.stream()
            .filter(event -> "thread_failed".equals(event.getEventType()))
            .anyMatch(event -> event.getPayloadJson().contains("retry_exhausted")));

    fakeProvider.reset();
    submit(thread.getThreadId(), "start again", "cid-retry-exhausted-restart-" + threadId);
    awaitIdle(threadId);
    assertEquals(1, fakeProvider.requestsSinceReset());
    assertEquals(0, threadStore.find(threadId).orElseThrow().retryAttempt());
    assertNull(threadStore.find(threadId).orElseThrow().retryAt());
  }

  @Test
  void waitingReleasesDespitePendingInputWhenToolWaiting() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-wait-input");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    Instant now = Instant.now();
    markRunnable(threadId, now);
    assertTrue(
        threadStore.tryAcquire(threadId, "owner-wait", now, Duration.ofSeconds(30)).isPresent());
    long head = threadStore.find(threadId).orElseThrow().headEntryId();
    insertWaitingApprovalInvocation(8200L + threadId % 1000, threadId, head);
    transactions.submitUserMessage(
        threadId,
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent("queued-behind-tool"))),
        "behind-" + threadId,
        now);
    boolean released =
        transactions.waitForExternal(
            threadId, "owner-wait", "tools_or_permission", now.plusSeconds(1));
    assertTrue(released, "pending user input must not retain token while tool is nonterminal");
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  @Test
  void authenticationFailureStopsWithoutAutomaticRetry() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-fail");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    retryPolicyResolver.set(
        new InvocationRetryPolicy(
            3,
            InvocationRetryBackoffStrategy.EXPONENTIAL,
            Duration.ofMillis(10),
            Duration.ofMillis(100)));
    fakeProvider.failNext(ProviderErrorKind.AUTHENTICATION, "bad credential");
    submit(thread.getThreadId(), "will-fail", "cid-fail-" + threadId);
    awaitFailed(threadId);

    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(events.stream().anyMatch(e -> "assistant_failed".equals(e.getEventType())));
    assertTrue(events.stream().anyMatch(e -> "thread_failed".equals(e.getEventType())));
    assertEquals(
        1, fakeProvider.requestsSinceReset(), "authentication failure must not be retried");
    assertFalse(events.stream().anyMatch(e -> "thread_retry_scheduled".equals(e.getEventType())));
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  /** Turn 资源解析失败必须在 Provider 调用前持久化失败，避免把配置故障伪装成可重试流错误。 */
  @Test
  void turnResourceSetupFailureFailsWithoutCallingProvider() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-resource-setup-fail");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failResourceResolution("resource lookup failed");

    submit(thread.getThreadId(), "requires resources", "cid-resource-fail-" + threadId);
    awaitFailed(threadId);

    assertEquals(0, fakeProvider.requestsSinceReset());
    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(
        events.stream()
            .filter(event -> "thread_failed".equals(event.getEventType()))
            .anyMatch(
                event ->
                    event.getPayloadJson() != null
                        && event.getPayloadJson().contains("turn_setup_failed")));
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  /** Provider 无法建立流时，TurnHandler 必须将引擎边界异常落为持久化失败。 */
  @Test
  void providerStreamSetupFailurePersistsProviderFailure() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-stream-setup-fail");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failStreamSetup("stream setup failed");

    submit(thread.getThreadId(), "requires stream", "cid-stream-fail-" + threadId);
    awaitFailed(threadId);

    assertEquals(1, fakeProvider.requestsSinceReset());
    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(
        events.stream()
            .filter(event -> "assistant_failed".equals(event.getEventType()))
            .anyMatch(
                event ->
                    event.getPayloadJson() != null
                        && event.getPayloadJson().contains("provider stream failed")));
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  /** 已验证的 Provider Tool call 若缺少冻结 binding，必须失败而不能留下半提交 assistant/tool。 */
  @Test
  void missingFrozenToolBindingFailsToolPreparation() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-tool-preparation-fail");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.omitToolBindingNext();
    fakeProvider.completeNextWithToolCall();

    submit(thread.getThreadId(), "requires tool", "cid-tool-prepare-fail-" + threadId);
    awaitFailed(threadId);

    assertEquals(1, fakeProvider.requestsSinceReset());
    assertTrue(invocationMapper.listByThread(threadId).isEmpty());
    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(
        events.stream()
            .filter(event -> "thread_failed".equals(event.getEventType()))
            .anyMatch(
                event ->
                    event.getPayloadJson() != null
                        && event.getPayloadJson().contains("tool_preparation_failed")));
  }

  /** Stop 在流进行中必须撤销本地 handle，并 fence 掉稍后到达的 assistant 回调。 */
  @Test
  void stopCancelsInFlightProviderAndFencesLateAssistantCallback() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-stop-fence");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fakeProvider.blockNext(entered, release, Duration.ofSeconds(5));
    submit(thread.getThreadId(), "cancel in flight", "cid-stop-fence-" + threadId);
    assertTrue(entered.await(3, TimeUnit.SECONDS));

    HarnessThreadStopDTO stop = new HarnessThreadStopDTO();
    stop.setClientRequestId("stop-in-flight-" + threadId);
    commandService.stop(thread.getThreadId(), stop);
    release.countDown();
    awaitProviderStreamCancelled();

    assertEquals(1, fakeProvider.requestsSinceReset());
    assertEquals("IDLE", threadStore.find(threadId).orElseThrow().status().name());
    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(events.stream().anyMatch(event -> "thread_stopped".equals(event.getEventType())));
    assertFalse(
        events.stream().anyMatch(event -> "assistant_completed".equals(event.getEventType())),
        "late provider completion must not commit through the stop fencing boundary");
  }

  /** Compaction service failure follows the permanent Provider failure path and does not retry. */
  @Test
  void overflowWithCompactionFailurePersistsTerminalFailure() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-compaction-fail");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failNext(ProviderErrorKind.OVERFLOW, "context overflow");
    fakeProvider.failCompaction("compaction unavailable");

    submit(thread.getThreadId(), "overflow context", "cid-compaction-fail-" + threadId);
    awaitFailed(threadId);

    assertEquals(1, fakeProvider.requestsSinceReset());
    assertFalse(lifecycleProbe.contains(threadId, CompactionCompleted.class));
    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(
        events.stream()
            .filter(event -> "assistant_failed".equals(event.getEventType()))
            .anyMatch(
                event ->
                    event.getPayloadJson() != null
                        && event.getPayloadJson().contains("compaction failed")));
  }

  @Test
  void overflowWithoutCompactableContextPersistsOriginalProviderFailure() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-compaction-empty");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failNext(ProviderErrorKind.OVERFLOW, "context overflow");
    fakeProvider.returnEmptyCompaction();

    submit(thread.getThreadId(), "uncompactable context", "cid-compaction-empty-" + threadId);
    awaitFailed(threadId);

    assertEquals(1, fakeProvider.requestsSinceReset());
    assertFalse(lifecycleProbe.contains(threadId, CompactionCompleted.class));
    assertTrue(
        queryService.listEvents(thread.getThreadId(), 0, 100).stream()
            .filter(event -> "assistant_failed".equals(event.getEventType()))
            .anyMatch(
                event ->
                    event.getPayloadJson() != null
                        && event.getPayloadJson().contains("context overflow")));
  }

  @Test
  void emptyAssistantResponsePersistsExplicitEmptyTextContent() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-empty-assistant");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.completeNextEmpty();

    submit(thread.getThreadId(), "empty reply", "cid-empty-assistant-" + threadId);
    awaitIdle(threadId);

    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    SessionEntryPayload payload =
        SESSION_ENTRY_CODEC.decode(
            SessionEntryType.MESSAGE, path.get(path.size() - 1).getPayloadJson());
    assertTrue(payload instanceof MessageEntryPayload);
    assertEquals(
        List.of(new TextMessageContent("")), ((MessageEntryPayload) payload).message().contents());
  }

  @Test
  void thinkingOnlyAssistantResponsePersistsThinkingContent() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-thinking-assistant");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.completeNextWithThinking();

    submit(thread.getThreadId(), "thinking reply", "cid-thinking-assistant-" + threadId);
    awaitIdle(threadId);

    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    SessionEntryPayload payload =
        SESSION_ENTRY_CODEC.decode(
            SessionEntryType.MESSAGE, path.get(path.size() - 1).getPayloadJson());
    assertTrue(payload instanceof MessageEntryPayload);
    assertEquals(
        List.of(new ThinkingMessageContent("reasoning")),
        ((MessageEntryPayload) payload).message().contents());
  }

  @Test
  void quiescenceRetainsTokenWhenTerminalToolResultsApplicable() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-external-release");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    Instant now = Instant.now();
    markRunnable(threadId, now);
    assertTrue(
        threadStore.tryAcquire(threadId, "owner-ext", now, Duration.ofSeconds(30)).isPresent());
    long head = threadStore.find(threadId).orElseThrow().headEntryId();
    insertTerminalInvocation(8100L + threadId % 1000, threadId, head);

    ThreadTransactions.QuiescenceResult quiescence =
        transactions.quiesce(threadId, "owner-ext", now.plusSeconds(1));
    assertEquals(
        ThreadTransactions.QuiescenceResult.WORK_REMAINS,
        quiescence,
        "terminal results pending apply must retain processor token");
    assertEquals("owner-ext", threadStore.find(threadId).orElseThrow().processorToken());
    threadStore.release(threadId, "owner-ext", now.plusSeconds(2));
  }

  @Test
  void concurrentClientMessageIdSubmitIsIdempotent() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-idem");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);
    String cid = "race-cid-" + threadId;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<HarnessThreadInputDTO> f1 =
          pool.submit(
              () -> {
                start.await();
                return submitReturn(thread.getThreadId(), "a", cid);
              });
      Future<HarnessThreadInputDTO> f2 =
          pool.submit(
              () -> {
                start.await();
                return submitReturn(thread.getThreadId(), "a", cid);
              });
      start.countDown();
      HarnessThreadInputDTO a = f1.get(10, TimeUnit.SECONDS);
      HarnessThreadInputDTO b = f2.get(10, TimeUnit.SECONDS);
      assertEquals(a.getInputId(), b.getInputId());
      assertEquals(a.getSequence(), b.getSequence());
      assertEquals(
          1,
          queryService.listInputs(thread.getThreadId()).stream()
              .filter(i -> cid.equals(i.getClientMessageId()))
              .count());
      awaitIdle(threadId);
    } finally {
      pool.shutdownNow();
    }
  }

  private void insertTerminalInvocation(long id, long threadId, long assistantEntryId) {
    ToolInvocationDO inv = new ToolInvocationDO();
    inv.setId(id);
    inv.setThreadId(threadId);
    inv.setAssistantEntryId(assistantEntryId);
    inv.setOrdinal(0);
    inv.setToolCallId("call-" + id);
    inv.setToolName("noop");
    inv.setToolVersion("1");
    inv.setLocation("PLATFORM");
    inv.setArgumentsJson("{}");
    inv.setStatus("SUCCEEDED");
    inv.setPermissionAction("ALLOW");
    inv.setSideEffect("READ_ONLY");
    inv.setDeadlineAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    inv.setResultJson(
        "{\"toolCallId\":\"call-"
            + id
            + "\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}],\"error\":false,\"details\":{}}");
    inv.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
    inv.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
    inv.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
    invocationMapper.insert(inv);
  }

  private void insertWaitingApprovalInvocation(long id, long threadId, long assistantEntryId) {
    ToolInvocationDO inv = new ToolInvocationDO();
    inv.setId(id);
    inv.setThreadId(threadId);
    inv.setAssistantEntryId(assistantEntryId);
    inv.setOrdinal(0);
    inv.setToolCallId("call-wait-" + id);
    inv.setToolName("write");
    inv.setToolVersion("1");
    inv.setLocation("PLATFORM");
    inv.setArgumentsJson("{}");
    inv.setStatus("WAITING_APPROVAL");
    inv.setPermissionAction("ASK");
    inv.setSideEffect("WRITE");
    inv.setDeadlineAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    inv.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
    inv.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
    invocationMapper.insert(inv);
  }

  private void awaitFailed(long threadId) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      List<ThreadEventDTO> events = queryService.listEvents(Long.toString(threadId), 0, 100);
      boolean failed = events.stream().anyMatch(e -> "thread_failed".equals(e.getEventType()));
      if (failed && threadStore.find(threadId).orElseThrow().processorToken() == null) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("thread did not fail: " + threadId);
  }

  private void awaitRetryScheduled(long threadId) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      var thread = threadStore.find(threadId).orElseThrow();
      if (thread.status().name().equals("RETRYING")
          && thread.retryAttempt() == 1
          && thread.retryAt() != null
          && thread.processorToken() == null) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("thread did not schedule automatic retry: " + threadId);
  }

  private void awaitRetryingToolWait(long threadId) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline)) {
      var thread = threadStore.find(threadId).orElseThrow();
      List<ToolInvocationDO> invocations = invocationMapper.listByThread(threadId);
      if (thread.status().name().equals("RETRYING")
          && thread.processorToken() == null
          && invocations.size() == 1
          && "WAITING_APPROVAL".equals(invocations.get(0).getStatus())) {
        return;
      }
      Thread.sleep(25);
    }
    var current = threadStore.find(threadId).orElseThrow();
    throw new AssertionError(
        "retrying tool chain did not release token: "
            + threadId
            + ", status="
            + current.status()
            + ", token="
            + current.processorToken()
            + ", retryAt="
            + current.retryAt()
            + ", invocations="
            + invocationMapper.listByThread(threadId).stream()
                .map(invocation -> invocation.getStatus())
                .toList());
  }

  private void awaitProviderStreamCancelled() throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      if (fakeProvider.latestStreamCancelled()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("provider stream was not cancelled");
  }

  private void awaitIdle(long threadId) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline)) {
      var current = threadStore.find(threadId).orElseThrow();
      List<HarnessThreadInputDTO> inputs = queryService.listInputs(Long.toString(threadId));
      boolean allApplied =
          inputs.isEmpty() || inputs.stream().allMatch(in -> in.getAppliedEntryId() != null);
      // 过期 token 视为可被其他 activation 接管；短 lease 测试下不能要求立刻为 null。
      boolean leaseHeld =
          current.processorToken() != null
              && current.processorUntil() != null
              && current.processorUntil().isAfter(Instant.now());
      if (!leaseHeld && allApplied) {
        if (current.processorToken() != null) {
          // 清掉过期 token 以便断言稳定（生产由 tryAcquire 覆盖）。
          threadStore.release(threadId, current.processorToken(), Instant.now());
        }
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "thread did not become idle: "
            + threadId
            + " token="
            + threadStore.find(threadId).orElseThrow().processorToken()
            + " requests="
            + fakeProvider.requestsSinceReset());
  }

  /**
   * agentless Session 创建后显式排队 SET_AGENT，等待 bootstrap 应用完成再返回 Main Thread。
   *
   * <p>后续断言若关心用户/配置输入，应通过 {@link #userMessageInputs(List)} 过滤 bootstrap。
   */
  private HarnessThreadDTO createRoot(String title) {
    HarnessSessionCreateDTO create = new HarnessSessionCreateDTO();
    create.setTitle(title);
    HarnessSessionDTO session = sessionCommandService.createSession(create);
    String threadId = session.getMainThreadId();
    HarnessThreadAgentSetDTO agent = new HarnessThreadAgentSetDTO();
    agent.setAgentDefinitionId("1");
    agent.setClientMessageId("bootstrap-agent-" + title);
    commandService.queueAgent(threadId, agent);
    try {
      awaitIdle(Long.parseLong(threadId));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    return queryService.getThread(threadId);
  }

  /** DTO 暴露枚举名（如 USER_MESSAGE），不是 wire value。 */
  private static List<HarnessThreadInputDTO> userMessageInputs(List<HarnessThreadInputDTO> inputs) {
    return inputs.stream().filter(i -> "USER_MESSAGE".equals(i.getInputType())).toList();
  }

  private void markRunnable(long threadId, Instant now) {
    assertEquals(
        1,
        threadMapper.updateStatusDirect(
            threadId, "RUNNING", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
  }

  private void submit(String threadId, String content, String clientMessageId) {
    submitReturn(threadId, content, clientMessageId);
  }

  private HarnessThreadInputDTO submitReturn(
      String threadId, String content, String clientMessageId) {
    HarnessThreadMessageCreateDTO msg = new HarnessThreadMessageCreateDTO();
    msg.setContent(content);
    msg.setClientMessageId(clientMessageId);
    return commandService.submitUserMessage(threadId, msg);
  }

  @TestConfiguration
  static class FakeProviderConfig {
    @Bean
    @Primary
    ToolSettingsProvider retryToolSettingsProvider() {
      return () ->
          new ToolSettings(
              Map.of("retry_tool", List.of(new PermissionRule("*", PermissionAction.ASK))), false);
    }

    @Bean
    LifecycleProbe lifecycleProbe() {
      return new LifecycleProbe();
    }

    @Bean
    HarnessExtension threadRuntimeProbeExtension(LifecycleProbe probe) {
      return new HarnessExtension() {
        @Override
        public String id() {
          return "test.thread-runtime-probe";
        }

        @Override
        public int priority() {
          return 100;
        }

        @Override
        public void contribute(HarnessExtensionRegistry registry) {
          registry.addContextTransform(
              state -> {
                probe.contextTransformed();
                return state;
              });
          registry.addBeforeCompactionInterceptor(
              context -> {
                probe.beforeCompaction();
                return context.context();
              });
          registry.addLifecycleObserver(probe::observe);
        }
      };
    }

    @Bean
    @Primary
    CompactionService controlledCompactionService(ControlledFakeProvider provider) {
      return (sessionId, headEntryId, context) -> {
        RuntimeException failure = provider.takeCompactionFailure();
        if (failure != null) {
          throw failure;
        }
        if (provider.takeEmptyCompaction()) {
          return Optional.empty();
        }
        return Optional.of(new CompactionEntryPayload("compacted", headEntryId, 1, "{}"));
      };
    }

    @Bean
    ControlledFakeProvider controlledFakeProvider() {
      return new ControlledFakeProvider();
    }

    /** 短 lease 以便验证模型调用中的 heartbeat 续租。 */
    @Bean
    @Primary
    ThreadProcessorConfig shortLeaseProcessorConfig() {
      return new ThreadProcessorConfig(Duration.ofMillis(150), Duration.ofMillis(50), 16 * 1024, 8);
    }

    @Bean
    @Primary
    MutableRetryPolicyResolver retryPolicyResolver() {
      return new MutableRetryPolicyResolver();
    }

    @Bean
    @Primary
    TurnResourceResolver controlledTurnResourceResolver(ControlledFakeProvider provider) {
      return (sessionId, threadId, config) -> {
        RuntimeException failure = provider.takeResourceResolutionFailure();
        if (failure != null) {
          throw failure;
        }
        ModelPricing pricing =
            new ModelPricing(
                "USD",
                "tier",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        ModelDescriptor model =
            new ModelDescriptor(
                1L,
                1L,
                ProviderType.OPENAI,
                "acceptance-stub",
                "acceptance-stub",
                32768,
                4096,
                Set.of(ModelInputModality.TEXT),
                true,
                true,
                List.of(
                    new ModelVariant(
                        "default", 4096, 0.2, null, null, null, null, List.of(), null)),
                pricing,
                PromptCachePolicy.disabled());
        return new TurnResources(
            provider,
            provider.modelCallTimeoutPolicy(),
            model,
            model.variants().get(0),
            List.of(RETRY_TEST_TOOL),
            provider.takeOmitToolBinding() ? List.of() : List.of(ToolBinding.of(RETRY_TEST_TOOL)),
            Path.of("."),
            Path.of("."));
      };
    }
  }

  /**
   * Keeps non-retry tests fast while allowing individual cases to prove durable automatic retry.
   */
  static final class MutableRetryPolicyResolver implements InvocationRetryPolicyResolver {
    private static final InvocationRetryPolicy NO_RETRY =
        new InvocationRetryPolicy(
            0, InvocationRetryBackoffStrategy.FIXED, Duration.ofMillis(10), Duration.ofMillis(10));

    private volatile InvocationRetryPolicy policy = NO_RETRY;

    @Override
    public InvocationRetryPolicy resolve() {
      return policy;
    }

    void reset() {
      policy = NO_RETRY;
    }

    void set(InvocationRetryPolicy value) {
      policy = value;
    }
  }

  static final class LifecycleProbe {
    private final AtomicInteger contextTransforms = new AtomicInteger();
    private final AtomicInteger beforeCompactions = new AtomicInteger();
    private final List<HarnessLifecycleObservation> observations = new CopyOnWriteArrayList<>();

    void reset() {
      contextTransforms.set(0);
      beforeCompactions.set(0);
      observations.clear();
    }

    void contextTransformed() {
      contextTransforms.incrementAndGet();
    }

    void beforeCompaction() {
      beforeCompactions.incrementAndGet();
    }

    void observe(HarnessLifecycleObservation observation) {
      observations.add(observation);
    }

    int contextTransformCount() {
      return contextTransforms.get();
    }

    int beforeCompactionCount() {
      return beforeCompactions.get();
    }

    boolean contains(long threadId, Class<? extends HarnessLifecycleObservation> type) {
      return observations.stream()
          .anyMatch(
              observation -> type.isInstance(observation) && threadId(observation) == threadId);
    }

    private static long threadId(HarnessLifecycleObservation observation) {
      if (observation instanceof TurnStarted started) {
        return started.threadId();
      }
      if (observation instanceof AssistantCompleted completed) {
        return completed.threadId();
      }
      if (observation instanceof ThreadIdle idle) {
        return idle.threadId();
      }
      if (observation instanceof CompactionCompleted completed) {
        return completed.threadId();
      }
      return -1L;
    }
  }

  static final class ControlledFakeProvider implements ModelProvider {
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger resetAt = new AtomicInteger();
    private final List<Integer> completionOrder = new CopyOnWriteArrayList<>();
    private final Queue<ProviderException> pendingFailures = new ConcurrentLinkedQueue<>();
    private volatile CountDownLatch blockEntered;
    private volatile CountDownLatch blockRelease;
    private volatile Duration blockDuration = Duration.ZERO;
    private volatile ProviderException failAfterBlock;
    private volatile List<ProviderToolCall> nextToolCalls = List.of();
    private volatile RuntimeException nextResourceResolutionFailure;
    private volatile RuntimeException nextStreamSetupFailure;
    private volatile RuntimeException nextCompactionFailure;
    private volatile boolean omitToolBinding;
    private volatile boolean nextEmptyCompaction;
    private volatile boolean nextEmptyResponse;
    private volatile boolean nextThinkingResponse;
    private volatile boolean nextStalled;
    private volatile List<String> nextTextDeltas = List.of();
    private volatile Duration textDeltaInterval = Duration.ZERO;
    private volatile AtomicBoolean latestStreamCancellation;
    private volatile ModelCallTimeoutPolicy modelCallTimeoutPolicy = ModelCallTimeoutPolicy.DEFAULT;

    void reset() {
      resetAt.set(requests.get());
      completionOrder.clear();
      pendingFailures.clear();
      blockEntered = null;
      blockRelease = null;
      blockDuration = Duration.ZERO;
      failAfterBlock = null;
      nextToolCalls = List.of();
      nextResourceResolutionFailure = null;
      nextStreamSetupFailure = null;
      nextCompactionFailure = null;
      omitToolBinding = false;
      nextEmptyCompaction = false;
      nextEmptyResponse = false;
      nextThinkingResponse = false;
      nextStalled = false;
      nextTextDeltas = List.of();
      textDeltaInterval = Duration.ZERO;
      latestStreamCancellation = null;
      modelCallTimeoutPolicy = ModelCallTimeoutPolicy.DEFAULT;
    }

    void failNext(ProviderErrorKind kind, String message) {
      pendingFailures.add(new ProviderException(kind, message));
    }

    void failResourceResolution(String message) {
      nextResourceResolutionFailure = new IllegalStateException(message);
    }

    void failStreamSetup(String message) {
      nextStreamSetupFailure = new IllegalStateException(message);
    }

    void omitToolBindingNext() {
      omitToolBinding = true;
    }

    void failCompaction(String message) {
      nextCompactionFailure = new IllegalStateException(message);
    }

    void returnEmptyCompaction() {
      nextEmptyCompaction = true;
    }

    void blockNext(CountDownLatch entered, CountDownLatch release, Duration duration) {
      this.blockEntered = entered;
      this.blockRelease = release;
      this.blockDuration = duration == null ? Duration.ZERO : duration;
      this.failAfterBlock = null;
    }

    void blockThenFail(
        CountDownLatch entered, CountDownLatch release, ProviderErrorKind kind, String message) {
      this.blockEntered = entered;
      this.blockRelease = release;
      this.blockDuration = Duration.ofSeconds(5);
      this.failAfterBlock = new ProviderException(kind, message);
    }

    void completeNextWithToolCall() {
      nextToolCalls = List.of(new ProviderToolCall("retry-tool-call", "retry_tool", "{}"));
    }

    void completeNextEmpty() {
      nextEmptyResponse = true;
    }

    void completeNextWithThinking() {
      nextThinkingResponse = true;
    }

    void stallNext() {
      nextStalled = true;
    }

    void emitTextDeltas(Duration interval, String... deltas) {
      textDeltaInterval = interval;
      nextTextDeltas = List.of(deltas);
    }

    void setModelCallTimeoutPolicy(ModelCallTimeoutPolicy value) {
      modelCallTimeoutPolicy = value;
    }

    ModelCallTimeoutPolicy modelCallTimeoutPolicy() {
      return modelCallTimeoutPolicy;
    }

    RuntimeException takeResourceResolutionFailure() {
      RuntimeException failure = nextResourceResolutionFailure;
      nextResourceResolutionFailure = null;
      return failure;
    }

    boolean takeOmitToolBinding() {
      boolean result = omitToolBinding;
      omitToolBinding = false;
      return result;
    }

    RuntimeException takeCompactionFailure() {
      RuntimeException failure = nextCompactionFailure;
      nextCompactionFailure = null;
      return failure;
    }

    boolean takeEmptyCompaction() {
      boolean result = nextEmptyCompaction;
      nextEmptyCompaction = false;
      return result;
    }

    boolean latestStreamCancelled() {
      AtomicBoolean cancellation = latestStreamCancellation;
      return cancellation != null && cancellation.get();
    }

    int requestsSinceReset() {
      return requests.get() - resetAt.get();
    }

    List<Integer> completionOrder() {
      return List.copyOf(completionOrder);
    }

    @Override
    public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
      int n = requests.incrementAndGet() - resetAt.get();
      RuntimeException setupFailure = nextStreamSetupFailure;
      nextStreamSetupFailure = null;
      if (setupFailure != null) {
        throw setupFailure;
      }
      AtomicBoolean cancellation = new AtomicBoolean();
      latestStreamCancellation = cancellation;
      ProviderStream stream =
          new ProviderStream() {
            @Override
            public void cancel() {
              cancellation.set(true);
            }

            @Override
            public boolean isCancelled() {
              return cancellation.get();
            }
          };
      ProviderException failure = pendingFailures.poll();
      if (failure != null) {
        handler.onError(failure, stream);
        return stream;
      }
      CountDownLatch entered = blockEntered;
      CountDownLatch release = blockRelease;
      Duration duration = blockDuration;
      ProviderException afterBlockFail = failAfterBlock;
      List<ProviderToolCall> toolCalls = nextToolCalls;
      boolean emptyResponse = nextEmptyResponse;
      boolean thinkingResponse = nextThinkingResponse;
      boolean stalled = nextStalled;
      List<String> textDeltas = nextTextDeltas;
      Duration deltaInterval = textDeltaInterval;
      blockEntered = null;
      blockRelease = null;
      blockDuration = Duration.ZERO;
      failAfterBlock = null;
      nextToolCalls = List.of();
      nextEmptyResponse = false;
      nextThinkingResponse = false;
      nextStalled = false;
      nextTextDeltas = List.of();
      textDeltaInterval = Duration.ZERO;
      if (entered != null) {
        entered.countDown();
      }
      if (release != null) {
        try {
          release.await(duration.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      } else if (!duration.isZero() && !duration.isNegative()) {
        try {
          Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      if (afterBlockFail != null) {
        handler.onError(afterBlockFail, stream);
        return stream;
      }
      if (stalled) {
        return stream;
      }
      StringBuilder streamedText = new StringBuilder();
      for (String delta : textDeltas) {
        handler.onEvent(new ProviderStreamEvent.TextDelta(delta), stream);
        streamedText.append(delta);
        if (!deltaInterval.isZero() && !deltaInterval.isNegative()) {
          try {
            Thread.sleep(deltaInterval.toMillis());
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
      String text =
          emptyResponse || thinkingResponse
              ? ""
              : streamedText.isEmpty() ? "reply-" + n : streamedText.toString();
      String thinking = thinkingResponse ? "reasoning" : "";
      if (!text.isEmpty() && textDeltas.isEmpty()) {
        handler.onEvent(new ProviderStreamEvent.TextDelta(text), stream);
      }
      if (!thinking.isEmpty()) {
        handler.onEvent(new ProviderStreamEvent.ThinkingDelta(thinking), stream);
      }
      ModelUsage usage = new ModelUsage(1, 1, 0, 0, 0, 0, 2);
      ModelCost cost = ModelCost.calculate(request.model().pricing(), usage);
      completionOrder.add(n);
      handler.onComplete(
          new ProviderResponse(
              text,
              thinking,
              toolCalls,
              toolCalls.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS,
              usage,
              cost,
              "req-" + n,
              null,
              "{}"),
          stream);
      return stream;
    }
  }
}
