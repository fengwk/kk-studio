package fun.fengwk.kkstudio.core.harness.thread.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.thread.store.MysqlHarnessThreadStore;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.session.AgentChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventDraft;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Durable transaction contracts for the Thread mailbox, cursor, lifecycle, and fencing boundaries.
 */
@SpringBootTest
@Transactional
class HarnessThreadTransactionServiceIntegrationTest {
  private static final SessionEntryJsonCodec ENTRY_CODEC = new SessionEntryJsonCodec();
  private static final long SEED_AGENT_ID = 1L;
  private static final String SEED_AGENT_NAME = "default-assistant";

  @Autowired private ThreadTransactions transactions;
  @Autowired private MysqlHarnessThreadStore threadStore;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadInputMapper inputMapper;
  @Autowired private HarnessSessionEntryMapper entryMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private ToolInvocationMapper invocationMapper;

  @Test
  void createsAgentlessRootAndRejectsUnknownBranchOrigins() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    ThreadTransactions.SessionCreateResult plain = transactions.createSession("plain", false, now);
    ThreadTransactions.SessionCreateResult yolo =
        transactions.createSession("yolo", true, now.plusSeconds(1));

    List<HarnessSessionEntryDO> plainEntries = entryMapper.listBySession(plain.sessionId());
    assertEquals(
        List.of("root"), plainEntries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    HarnessThreadDO plainThread = threadMapper.find(plain.mainThread().id());
    assertEquals(plainEntries.get(0).getId(), plainThread.getHeadEntryId());
    assertNull(plainThread.getActiveAgentDefinitionId());
    assertFalse(Boolean.TRUE.equals(plainThread.getYoloEnabled()));

    List<HarnessSessionEntryDO> yoloEntries = entryMapper.listBySession(yolo.sessionId());
    assertEquals(
        List.of("root"), yoloEntries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    HarnessThreadDO yoloThread = threadMapper.find(yolo.mainThread().id());
    assertEquals(yoloEntries.get(0).getId(), yoloThread.getHeadEntryId());
    assertTrue(Boolean.TRUE.equals(yoloThread.getYoloEnabled()));

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.createThreadFromEntry(9_999_999L, plainEntries.get(0).getId(), now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.createThreadFromEntry(plain.sessionId(), 9_999_998L, now));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.createThreadFromEntry(plain.sessionId(), yoloEntries.get(0).getId(), now));
  }

  @Test
  void harvestsConfigInputsOntoThreadStateWithoutFullAgentSecrets() {
    Instant now = Instant.parse("2026-02-02T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("config", false, now);
    long threadId = created.mainThread().id();

    ThreadInput agent =
        transactions.submitSetAgent(threadId, SEED_AGENT_ID, SEED_AGENT_NAME, "agent", now);
    assertEquals(
        agent.id(),
        transactions
            .submitSetAgent(threadId, SEED_AGENT_ID, SEED_AGENT_NAME, "agent", now.plusSeconds(1))
            .id());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.submitSetAgent(
                threadId, SEED_AGENT_ID, "other-name", "agent", now.plusSeconds(2)));

    ThreadInput model = transactions.submitSetModel(threadId, "model-2", "variant-2", "model", now);
    assertEquals(
        model.id(),
        transactions
            .submitSetModel(threadId, "model-2", "variant-2", "model", now.plusSeconds(1))
            .id());
    ThreadInput yolo = transactions.submitSetYolo(threadId, true, "yolo", now);
    assertEquals(
        yolo.id(), transactions.submitSetYolo(threadId, true, "yolo", now.plusSeconds(1)).id());

    assertTrue(
        threadStore.tryAcquire(threadId, "config-owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult harvest =
        transactions.harvestQueuedInputs(threadId, "config-owner", now.plusSeconds(3));

    assertTrue(harvest.harvested());
    assertFalse(harvest.hasMessage());
    assertEquals(
        List.of(agent.id(), model.id(), yolo.id()),
        harvest.applied().stream().map(ThreadInput::id).toList());

    HarnessThreadDO thread = threadMapper.find(threadId);
    assertEquals(SEED_AGENT_ID, thread.getActiveAgentDefinitionId());
    assertEquals(SEED_AGENT_NAME, thread.getActiveAgentName());
    assertEquals("model-2", thread.getModelId());
    assertEquals("variant-2", thread.getVariant());
    assertTrue(Boolean.TRUE.equals(thread.getYoloEnabled()));

    List<HarnessSessionEntryDO> entries = entryMapper.listBySession(created.sessionId());
    assertEquals(
        List.of("root", "agent_change"),
        entries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    AgentChangeEntryPayload change = payload(entries.get(1), AgentChangeEntryPayload.class);
    assertEquals(SEED_AGENT_ID, change.agentDefinitionId());
    assertEquals(SEED_AGENT_NAME, change.agentName());
    assertFalse(entries.get(1).getPayloadJson().contains("systemPrompt"));
    assertFalse(entries.get(1).getPayloadJson().contains("snapshot"));
    assertFalse(entries.get(1).getPayloadJson().contains("tools"));
    assertFalse(entries.get(1).getPayloadJson().contains("executionPolicy"));

    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.AGENT_CHANGED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.MODEL_CHANGED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.YOLO_CHANGED.value()));
  }

  @Test
  void harvestsOneMessagePerBatchAndDefersLaterConfigAfterMessage() {
    Instant now = Instant.parse("2026-02-03T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("messages", false, now);
    long threadId = created.mainThread().id();
    long rootHead = created.mainThread().headEntryId();
    ThreadInput user = transactions.submitUserMessage(threadId, userMessage("user"), "user", now);
    ThreadInput custom =
        transactions.submitCustomMessage(threadId, customMessage("custom"), "custom", now);

    assertTrue(
        threadStore.tryAcquire(threadId, "message-owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult first =
        transactions.harvestQueuedInputs(threadId, "message-owner", now.plusSeconds(1));
    ThreadInput later =
        transactions.submitUserMessage(threadId, userMessage("later"), "later", now.plusSeconds(2));

    assertTrue(first.hasMessage());
    assertEquals(List.of(user.id()), first.applied().stream().map(ThreadInput::id).toList());
    assertEquals("queued", inputMapper.find(custom.id()).getStatus());
    assertEquals("queued", inputMapper.find(later.id()).getStatus());

    HarnessSessionEntryDO userEntry =
        entryMapper.find(created.sessionId(), first.applied().get(0).appliedEntryId());
    assertEquals(rootHead, userEntry.getParentEntryId());

    ThreadTransactions.HarvestResult second =
        transactions.harvestQueuedInputs(threadId, "message-owner", now.plusSeconds(3));
    assertTrue(second.hasMessage());
    assertEquals(List.of(custom.id()), second.applied().stream().map(ThreadInput::id).toList());
    assertEquals("queued", inputMapper.find(later.id()).getStatus());
  }

  /** M1 -> SET_AGENT(B) -> M2：M1 使用旧状态，SET_AGENT 与 M2 在后续安全边界应用。 */
  @Test
  void messageBoundaryKeepsLaterAgentChangeOffEarlierTurn() {
    Instant now = Instant.parse("2026-02-07T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("order", false, now);
    long threadId = created.mainThread().id();
    ThreadInput m1 = transactions.submitUserMessage(threadId, userMessage("m1"), "m1", now);
    ThreadInput setAgent =
        transactions.submitSetAgent(
            threadId, SEED_AGENT_ID, SEED_AGENT_NAME, "set-agent", now.plusSeconds(1));
    ThreadInput m2 =
        transactions.submitUserMessage(threadId, userMessage("m2"), "m2", now.plusSeconds(2));

    assertTrue(
        threadStore.tryAcquire(threadId, "order-owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult first =
        transactions.harvestQueuedInputs(threadId, "order-owner", now.plusSeconds(3));
    assertEquals(List.of(m1.id()), first.applied().stream().map(ThreadInput::id).toList());
    assertNull(threadMapper.find(threadId).getActiveAgentDefinitionId());
    assertEquals("queued", inputMapper.find(setAgent.id()).getStatus());
    assertEquals("queued", inputMapper.find(m2.id()).getStatus());

    ThreadTransactions.HarvestResult second =
        transactions.harvestQueuedInputs(threadId, "order-owner", now.plusSeconds(4));
    assertEquals(
        List.of(setAgent.id(), m2.id()), second.applied().stream().map(ThreadInput::id).toList());
    HarnessThreadDO after = threadMapper.find(threadId);
    assertEquals(SEED_AGENT_ID, after.getActiveAgentDefinitionId());
    assertEquals(SEED_AGENT_NAME, after.getActiveAgentName());
    assertEquals("1", after.getModelId());
    assertEquals("default", after.getVariant());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.AGENT_CHANGED.value()));
    List<HarnessThreadEventDO> agentEvents =
        eventMapper.listAfter(threadId, 0L, 100).stream()
            .filter(e -> ThreadEventType.AGENT_CHANGED.value().equals(e.getEventType()))
            .toList();
    assertEquals(1, agentEvents.size());
  }

  @Test
  void stopsInInputOrderAndRetriesOnlyFailedThreads() {
    Instant now = Instant.parse("2026-02-04T00:00:00Z");
    ThreadTransactions.SessionCreateResult created = transactions.createSession("stop", false, now);
    long threadId = created.mainThread().id();
    transactions.submitUserMessage(threadId, userMessage("first"), "first", now);
    transactions.submitSetModel(threadId, "stop-model", "stop-variant", "config", now);
    transactions.submitCustomMessage(threadId, customMessage("second"), "second", now);

    ThreadTransactions.StopResult stopped =
        transactions.stop(threadId, "stop-request", now.plusSeconds(1));
    ThreadTransactions.StopResult replayed =
        transactions.stop(threadId, "stop-request", now.plusSeconds(2));

    assertEquals(stopped.stop().id(), replayed.stop().id());
    assertEquals(List.of("first", "second"), stopped.restoredMessages());
    assertEquals(
        stopped.cancelledInputs().stream().map(ThreadInput::id).toList(),
        replayed.cancelledInputs().stream().map(ThreadInput::id).toList());
    List<HarnessThreadInputDO> cancelled = inputMapper.listByThread(threadId);
    assertEquals(3, cancelled.size());
    assertTrue(
        cancelled.stream()
            .allMatch(
                input ->
                    "cancelled".equals(input.getStatus())
                        && input.getResolvedAt() != null
                        && stopped.stop().id() == input.getCancelledByStopId()));
    assertEquals("IDLE", threadMapper.find(threadId).getStatus());
    assertNull(threadMapper.find(threadId).getProcessorToken());
    assertEquals(3, eventMapper.countByType(threadId, ThreadEventType.INPUT_CANCELLED.value()));

    assertThrows(
        IllegalStateException.class, () -> transactions.retry(threadId, now.plusSeconds(3)));
    threadMapper.updateStatusDirect(threadId, "FAILED", utc(now.plusSeconds(4)));
    assertEquals("RETRYING", transactions.retry(threadId, now.plusSeconds(5)).status().name());
    assertEquals("RETRYING", threadMapper.find(threadId).getStatus());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.THREAD_RETRYING.value()));
    assertThrows(
        IllegalStateException.class, () -> transactions.retry(threadId, now.plusSeconds(6)));
  }

  @Test
  void fencesLostOwnersAndPersistsWaitFailureCompactionAndQuiescenceStates() {
    Instant now = Instant.parse("2026-02-05T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("lifecycle", false, now);
    long threadId = created.mainThread().id();
    long originalHead = created.mainThread().headEntryId();

    assertFalse(
        transactions.compact(
            threadId,
            "lost",
            new CompactionEntryPayload("summary", originalHead, 1, "{}"),
            List.of(),
            now));
    assertFalse(
        transactions.appendEvents(
            threadId, "lost", List.of(event(ThreadEventType.ASSISTANT_STARTED)), now));
    assertEquals(
        ThreadTransactions.QuiescenceResult.LOST_OWNERSHIP,
        transactions.quiesce(threadId, "lost", now));
    assertEquals(originalHead, threadMapper.find(threadId).getHeadEntryId());

    threadMapper.updateStatusDirect(threadId, "RUNNING", utc(now));
    assertTrue(threadStore.tryAcquire(threadId, "owner-a", now, Duration.ofMinutes(1)).isPresent());
    assertFalse(transactions.waitForExternal(threadId, "owner-a", "no-tools", now.plusSeconds(1)));
    assertEquals("owner-a", threadMapper.find(threadId).getProcessorToken());
    insertWaitingInvocation(threadId, originalHead, now);
    assertTrue(transactions.waitForExternal(threadId, "owner-a", "permission", now.plusSeconds(2)));
    assertEquals("WAITING", threadMapper.find(threadId).getStatus());
    assertNull(threadMapper.find(threadId).getProcessorToken());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.THREAD_WAITING.value()));

    assertTrue(
        threadStore
            .tryAcquire(threadId, "owner-b", now.plusSeconds(3), Duration.ofMinutes(1))
            .isPresent());
    assertEquals(
        ThreadTransactions.QuiescenceResult.WORK_REMAINS,
        transactions.quiesce(threadId, "owner-b", now.plusSeconds(4)));
    assertEquals("owner-b", threadMapper.find(threadId).getProcessorToken());
    assertTrue(threadStore.release(threadId, "owner-b", now.plusSeconds(5)));

    ThreadTransactions.SessionCreateResult work = transactions.createSession("work", false, now);
    long workThreadId = work.mainThread().id();
    threadMapper.updateStatusDirect(workThreadId, "RUNNING", utc(now));
    assertTrue(
        threadStore.tryAcquire(workThreadId, "owner-c", now, Duration.ofMinutes(1)).isPresent());
    assertTrue(
        transactions.appendEvents(
            workThreadId, "owner-c", List.of(event(ThreadEventType.ASSISTANT_STARTED)), now));
    assertTrue(
        transactions.compact(
            workThreadId,
            "owner-c",
            new CompactionEntryPayload("summary", work.mainThread().headEntryId(), 1, "{}"),
            List.of(event(ThreadEventType.COMPACTION_COMPLETED)),
            now.plusSeconds(1)));
    long compactedHead = threadMapper.find(workThreadId).getHeadEntryId();
    assertEquals("compaction", entryMapper.find(work.sessionId(), compactedHead).getEntryType());
    assertTrue(
        transactions.fail(
            workThreadId,
            "owner-c",
            List.of(event(ThreadEventType.THREAD_FAILED)),
            now.plusSeconds(2)));
    assertEquals("FAILED", threadMapper.find(workThreadId).getStatus());
    assertNull(threadMapper.find(workThreadId).getProcessorToken());
    assertFalse(transactions.fail(workThreadId, "owner-c", List.of(), now.plusSeconds(3)));

    transactions.retry(workThreadId, now.plusSeconds(4));
    assertTrue(
        threadStore
            .tryAcquire(workThreadId, "owner-d", now.plusSeconds(5), Duration.ofMinutes(1))
            .isPresent());
    assertTrue(transactions.completeRetriedTurn(workThreadId, "owner-d", now.plusSeconds(6)));
    assertEquals("RUNNING", threadMapper.find(workThreadId).getStatus());
    assertEquals(
        ThreadTransactions.QuiescenceResult.IDLE,
        transactions.quiesce(workThreadId, "owner-d", now.plusSeconds(7)));
    assertEquals("IDLE", threadMapper.find(workThreadId).getStatus());
    assertNull(threadMapper.find(workThreadId).getProcessorToken());
  }

  @Test
  void rejectsInvalidInputsAndDoesNotLetLostOwnersWrite() {
    Instant now = Instant.parse("2026-02-06T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("validation", false, now);
    long threadId = created.mainThread().id();
    long initialHead = created.mainThread().headEntryId();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.submitUserMessage(
                threadId,
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("not-user"))),
                "wrong-role",
                now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.submitSetModel(threadId, " ", "v", "blank-model", now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.harvestQueuedInputs(9_999_997L, "missing", now));
    assertThrows(
        IllegalArgumentException.class, () -> transactions.stop(9_999_996L, "missing-stop", now));
    assertThrows(IllegalArgumentException.class, () -> transactions.retry(9_999_995L, now));

    assertFalse(
        transactions.commitFinalAssistant(
            threadId, "lost", 9_999_994L, null, null, List.of(), now.plusSeconds(1)));
    assertFalse(transactions.applyTerminalToolResults(threadId, "lost", now.plusSeconds(1)));
    threadMapper.updateStatusDirect(threadId, "RUNNING", utc(now));
    assertTrue(threadStore.tryAcquire(threadId, "owner", now, Duration.ofMinutes(1)).isPresent());
    assertFalse(transactions.applyTerminalToolResults(threadId, "owner", now.plusSeconds(1)));

    assertEquals(initialHead, threadMapper.find(threadId).getHeadEntryId());
    assertTrue(inputMapper.listByThread(threadId).isEmpty());
    assertEquals(0, eventMapper.countByType(threadId, ThreadEventType.INPUT_APPLIED.value()));
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage customMessage(String text) {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)));
  }

  private static ThreadEventDraft event(ThreadEventType type) {
    return new ThreadEventDraft(type, "{\"schemaVersion\":1}");
  }

  private <T extends SessionEntryPayload> T payload(HarnessSessionEntryDO entry, Class<T> type) {
    SessionEntryPayload payload =
        ENTRY_CODEC.decode(
            SessionEntryType.fromValue(entry.getEntryType()), entry.getPayloadJson());
    return assertInstanceOf(type, payload);
  }

  private void insertWaitingInvocation(long threadId, long assistantEntryId, Instant now) {
    ToolInvocationDO invocation = new ToolInvocationDO();
    invocation.setId(threadId + 1_000_000L);
    invocation.setThreadId(threadId);
    invocation.setAssistantEntryId(assistantEntryId);
    invocation.setOrdinal(0);
    invocation.setToolCallId("waiting-" + threadId);
    invocation.setToolName("write");
    invocation.setToolVersion("1");
    invocation.setTargetType("CONTROL");
    invocation.setArgumentsJson("{}");
    invocation.setStatus("WAITING_APPROVAL");
    invocation.setPermissionAction("ASK");
    invocation.setSideEffect("WRITE");
    invocation.setDeadlineAt(utc(now.plusSeconds(60)));
    invocation.setCreateTime(utc(now));
    invocation.setUpdateTime(utc(now));
    assertEquals(1, invocationMapper.insert(invocation));
    assertNotNull(invocationMapper.find(invocation.getId()));
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
