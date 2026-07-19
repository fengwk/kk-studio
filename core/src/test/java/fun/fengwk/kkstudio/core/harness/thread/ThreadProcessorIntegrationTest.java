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
import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
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
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
          ToolExecutionMode.CONTROL,
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

  @BeforeEach
  void resetProvider() {
    fakeProvider.reset();
    lifecycleProbe.reset();
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
    assertEquals(2, inputs.size());
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
    assertEquals(1, inputs.size());
    assertNotNull(inputs.get(0).getAppliedEntryId());

    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    assertTrue(path.size() >= 3, "snapshot + user + assistant expected, got " + path.size());
    assertEquals("message", path.get(path.size() - 1).getEntryType());

    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(events.stream().anyMatch(e -> "assistant_completed".equals(e.getEventType())));
    assertTrue(events.stream().anyMatch(e -> "thread_idle".equals(e.getEventType())));

    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
    assertEquals(1, fakeProvider.requestsSinceReset());
    assertFalse(usageMapper.listByThreadId(threadId).isEmpty());
    assertTrue(lifecycleProbe.contextTransformCount() > 0);
    assertTrue(lifecycleProbe.contains(threadId, TurnStarted.class));
    assertTrue(lifecycleProbe.contains(threadId, AssistantCompleted.class));
    assertTrue(lifecycleProbe.contains(threadId, ThreadIdle.class));
  }

  /** 配置-only harvest 只推进 Entry path，不得制造无来源的 assistant response debt。 */
  @Test
  void configOnlyBatchAppliesWithoutCallingProvider() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-config-only");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    awaitIdle(threadId);

    transactions.submitSetYolo(threadId, true, "cid-yolo-" + threadId, Instant.now());
    threadProcessor.process(threadId);
    awaitIdle(threadId);

    assertEquals(0, fakeProvider.requestsSinceReset());
    List<HarnessThreadInputDTO> inputs = queryService.listInputs(thread.getThreadId());
    assertEquals(1, inputs.size());
    assertNotNull(inputs.get(0).getAppliedEntryId());
    List<HarnessSessionEntryDTO> path = queryService.listPathEntries(thread.getThreadId());
    assertEquals("yolo_change", path.get(path.size() - 1).getEntryType());
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

    List<HarnessThreadInputDTO> inputs = queryService.listInputs(thread.getThreadId());
    assertEquals(2, inputs.size());
    assertNotNull(inputs.get(0).getAppliedEntryId());
    assertNotNull(inputs.get(1).getAppliedEntryId());
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
  void failedTurnKeepsConcurrentSecondUserQueuedUntilExplicitRetry() throws Exception {
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

    List<HarnessThreadInputDTO> inputs = queryService.listInputs(thread.getThreadId());
    assertEquals(2, inputs.size());
    assertNotNull(inputs.get(0).getAppliedEntryId());
    assertNull(inputs.get(1).getAppliedEntryId());
    assertEquals(1, fakeProvider.requestsSinceReset());
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
  }

  @Test
  void failedTurnRequiresRetryBeforeLaterInputCanRun() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-fail-later");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failNext(ProviderErrorKind.INVALID_REQUEST, "first-fail");
    submit(thread.getThreadId(), "will-fail", "cid-fail1-" + threadId);
    awaitFailed(threadId);
    assertEquals(1, fakeProvider.requestsSinceReset());

    // FAILED 期间后续输入仅入队，不能触发模型。
    fakeProvider.reset();
    submit(thread.getThreadId(), "retry-after-fail", "cid-retry-" + threadId);
    Thread.sleep(100);
    assertEquals(0, fakeProvider.requestsSinceReset());
    transactions.retry(threadId, Instant.now());
    threadProcessor.process(threadId);
    awaitIdle(threadId);
    assertTrue(fakeProvider.requestsSinceReset() >= 2, "retry repays debt before harvesting input");
  }

  /** RETRYING Tool chain 释放 token 但保留 debt，直到 follow-up assistant 完成前不得 harvest 新输入。 */
  @Test
  void retryToolWaitRetainsDebtUntilFollowUpAssistantBeforeHarvestingLaterInput() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-retry-tool-wait");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failNext(ProviderErrorKind.INVALID_REQUEST, "first-fail");
    submit(thread.getThreadId(), "will-fail", "cid-retry-tool-fail-" + threadId);
    awaitFailed(threadId);

    submit(thread.getThreadId(), "queued-behind-retry-debt", "cid-retry-tool-later-" + threadId);
    fakeProvider.completeNextWithToolCall();
    transactions.retry(threadId, Instant.now());
    threadProcessor.process(threadId);
    awaitRetryingToolWait(threadId);

    List<HarnessThreadInputDTO> inputs = queryService.listInputs(thread.getThreadId());
    assertEquals(2, inputs.size());
    assertNotNull(inputs.get(0).getAppliedEntryId());
    assertNull(inputs.get(1).getAppliedEntryId(), "retry debt must block later mailbox harvest");
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
        queryService.listInputs(thread.getThreadId()).get(1).getAppliedEntryId(),
        "only the final retry assistant may clear retry debt and harvest later input");
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
  void permanentProviderFailureStopsWithoutImmediateRetry() throws Exception {
    HarnessThreadDTO thread = createRoot("proc-fail");
    long threadId = HarnessIds.parsePositive(thread.getThreadId(), "threadId");
    fakeProvider.failNext(ProviderErrorKind.INVALID_REQUEST, "boom");
    submit(thread.getThreadId(), "will-fail", "cid-fail-" + threadId);
    awaitFailed(threadId);

    List<ThreadEventDTO> events = queryService.listEvents(thread.getThreadId(), 0, 100);
    assertTrue(events.stream().anyMatch(e -> "assistant_failed".equals(e.getEventType())));
    assertTrue(events.stream().anyMatch(e -> "thread_failed".equals(e.getEventType())));
    assertEquals(
        1, fakeProvider.requestsSinceReset(), "must not immediately retry in same activation");
    assertNull(threadStore.find(threadId).orElseThrow().processorToken());
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
    inv.setTargetType("CONTROL");
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
    inv.setTargetType("CONTROL");
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
    throw new AssertionError("retrying tool chain did not release token: " + threadId);
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

  private HarnessThreadDTO createRoot(String title) {
    HarnessSessionCreateDTO create = new HarnessSessionCreateDTO();
    create.setAgentDefinitionId("1");
    create.setTitle(title);
    HarnessSessionDTO session = sessionCommandService.createSession(create);
    return queryService.getThread(session.getMainThreadId());
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
    CompactionService controlledCompactionService() {
      return (sessionId, headEntryId, context) ->
          Optional.of(new CompactionEntryPayload("compacted", headEntryId, 1, "{}"));
    }

    @Bean
    ControlledFakeProvider controlledFakeProvider() {
      return new ControlledFakeProvider();
    }

    /** 短 lease 以便验证模型调用中的 heartbeat 续租。 */
    @Bean
    @Primary
    ThreadProcessorConfig shortLeaseProcessorConfig() {
      return new ThreadProcessorConfig(
          Duration.ofMillis(150), Duration.ofMillis(50), 16 * 1024, Duration.ofMinutes(5), 8);
    }

    @Bean
    @Primary
    TurnResourceResolver controlledTurnResourceResolver(ControlledFakeProvider provider) {
      return (sessionId, threadId, config) -> {
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
                Set.of(ModelCapability.TEXT, ModelCapability.TOOLS),
                List.of(new ModelVariant("default", 4096, 0.2, null, null, null, null, List.of())),
                pricing,
                PromptCachePolicy.disabled());
        return new TurnResources(
            provider,
            model,
            model.variants().get(0),
            List.of(RETRY_TEST_TOOL),
            List.of(ToolBinding.of(RETRY_TEST_TOOL)),
            Path.of("."),
            Path.of("."));
      };
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
    private volatile ProviderException nextFailure;
    private volatile CountDownLatch blockEntered;
    private volatile CountDownLatch blockRelease;
    private volatile Duration blockDuration = Duration.ZERO;
    private volatile ProviderException failAfterBlock;
    private volatile List<ProviderToolCall> nextToolCalls = List.of();

    void reset() {
      resetAt.set(requests.get());
      completionOrder.clear();
      nextFailure = null;
      blockEntered = null;
      blockRelease = null;
      blockDuration = Duration.ZERO;
      failAfterBlock = null;
      nextToolCalls = List.of();
    }

    void failNext(ProviderErrorKind kind, String message) {
      nextFailure = new ProviderException(kind, message);
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

    int requestsSinceReset() {
      return requests.get() - resetAt.get();
    }

    List<Integer> completionOrder() {
      return List.copyOf(completionOrder);
    }

    @Override
    public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
      int n = requests.incrementAndGet() - resetAt.get();
      ProviderStream stream =
          new ProviderStream() {
            private boolean cancelled;

            @Override
            public void cancel() {
              cancelled = true;
            }

            @Override
            public boolean isCancelled() {
              return cancelled;
            }
          };
      ProviderException failure = nextFailure;
      nextFailure = null;
      if (failure != null) {
        handler.onError(failure, stream);
        return stream;
      }
      CountDownLatch entered = blockEntered;
      CountDownLatch release = blockRelease;
      Duration duration = blockDuration;
      ProviderException afterBlockFail = failAfterBlock;
      List<ProviderToolCall> toolCalls = nextToolCalls;
      blockEntered = null;
      blockRelease = null;
      blockDuration = Duration.ZERO;
      failAfterBlock = null;
      nextToolCalls = List.of();
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
      handler.onEvent(new ProviderStreamEvent.TextDelta("reply-" + n), stream);
      ModelUsage usage = new ModelUsage(1, 1, 0, 0, 0, 0, 2);
      ModelCost cost = ModelCost.calculate(request.model().pricing(), usage);
      completionOrder.add(n);
      handler.onComplete(
          new ProviderResponse(
              "reply-" + n,
              "",
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
