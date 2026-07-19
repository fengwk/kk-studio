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
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ModelChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolsetChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.YoloChangeEntryPayload;
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

  @Autowired private ThreadTransactions transactions;
  @Autowired private MysqlHarnessThreadStore threadStore;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadInputMapper inputMapper;
  @Autowired private HarnessSessionEntryMapper entryMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private ToolInvocationMapper invocationMapper;

  /** Creation persists both root shapes and rejects unknown Session/Entry branch origins. */
  @Test
  void createsBothRootShapesAndRejectsUnknownBranchOrigins() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    ThreadTransactions.SessionCreateResult plain =
        transactions.createSession(101L, "plain", snapshot("plain"), false, now);
    ThreadTransactions.SessionCreateResult yolo =
        transactions.createSession(102L, "yolo", snapshot("yolo"), true, now.plusSeconds(1));

    List<HarnessSessionEntryDO> plainEntries = entryMapper.listBySession(plain.sessionId());
    assertEquals(
        List.of("agent_snapshot"),
        plainEntries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    assertEquals(
        plainEntries.get(0).getId(), threadMapper.find(plain.mainThread().id()).getHeadEntryId());

    List<HarnessSessionEntryDO> yoloEntries = entryMapper.listBySession(yolo.sessionId());
    assertEquals(
        List.of("agent_snapshot", "yolo_change"),
        yoloEntries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    assertEquals(yoloEntries.get(0).getId(), yoloEntries.get(1).getParentEntryId());
    assertEquals(
        yoloEntries.get(1).getId(), threadMapper.find(yolo.mainThread().id()).getHeadEntryId());

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

  /** Typed config inputs fold into a single durable cutoff with no synthetic message debt. */
  @Test
  void harvestsTypedConfigInputsIdempotentlyAndPersistsTheirFold() {
    Instant now = Instant.parse("2026-02-02T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession(201L, "config", snapshot("root"), false, now);
    long threadId = created.mainThread().id();
    AgentSnapshot replacement = snapshot("replacement");

    ThreadInput agent = transactions.submitSetAgent(threadId, 202L, replacement, "agent", now);
    assertEquals(
        agent.id(),
        transactions.submitSetAgent(threadId, 202L, replacement, "agent", now.plusSeconds(1)).id());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.submitSetAgent(threadId, 203L, replacement, "agent", now.plusSeconds(2)));

    ThreadInput model = transactions.submitSetModel(threadId, "model-2", "variant-2", "model", now);
    assertEquals(
        model.id(),
        transactions
            .submitSetModel(threadId, "model-2", "variant-2", "model", now.plusSeconds(1))
            .id());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.submitSetModel(
                threadId, "model-3", "variant-2", "model", now.plusSeconds(2)));
    ThreadInput toolset =
        transactions.submitSetToolset(threadId, List.of("read", "write"), "toolset", now);
    assertEquals(
        toolset.id(),
        transactions
            .submitSetToolset(threadId, List.of("read", "write"), "toolset", now.plusSeconds(1))
            .id());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.submitSetToolset(
                threadId, List.of("read"), "toolset", now.plusSeconds(2)));
    ThreadInput yolo = transactions.submitSetYolo(threadId, true, "yolo", now);
    assertEquals(
        yolo.id(), transactions.submitSetYolo(threadId, true, "yolo", now.plusSeconds(1)).id());
    assertThrows(
        IllegalStateException.class,
        () -> transactions.submitSetYolo(threadId, false, "yolo", now.plusSeconds(2)));

    assertEquals("RUNNING", threadMapper.find(threadId).getStatus());
    assertTrue(
        threadStore.tryAcquire(threadId, "config-owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult harvest =
        transactions.harvestQueuedInputs(threadId, "config-owner", now.plusSeconds(3));

    assertTrue(harvest.harvested());
    assertFalse(harvest.hasMessage());
    assertEquals(
        List.of(agent.id(), model.id(), toolset.id(), yolo.id()),
        harvest.applied().stream().map(ThreadInput::id).toList());
    assertEquals(harvest.newHeadEntryId(), threadMapper.find(threadId).getHeadEntryId());

    List<HarnessThreadInputDO> inputs = inputMapper.listByThread(threadId);
    assertEquals(
        List.of("set_agent", "set_model", "set_toolset", "set_yolo"),
        inputs.stream().map(HarnessThreadInputDO::getInputType).toList());
    assertTrue(
        inputs.stream()
            .allMatch(
                input ->
                    "applied".equals(input.getStatus())
                        && input.getAppliedEntryId() != null
                        && input.getResolvedAt() != null));

    List<HarnessSessionEntryDO> entries = entryMapper.listBySession(created.sessionId());
    List<HarnessSessionEntryDO> folded = entries.subList(entries.size() - 4, entries.size());
    assertEquals(
        List.of("agent_snapshot", "model_change", "toolset_change", "yolo_change"),
        folded.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    assertEquals(created.mainThread().headEntryId(), folded.get(0).getParentEntryId());
    assertEquals(folded.get(0).getId(), folded.get(1).getParentEntryId());
    assertEquals(folded.get(1).getId(), folded.get(2).getParentEntryId());
    assertEquals(folded.get(2).getId(), folded.get(3).getParentEntryId());
    assertEquals(replacement, payload(folded.get(0), AgentSnapshotEntryPayload.class).snapshot());
    assertEquals("model-2", payload(folded.get(1), ModelChangeEntryPayload.class).modelId());
    assertEquals(
        List.of("read", "write"), payload(folded.get(2), ToolsetChangeEntryPayload.class).tools());
    assertTrue(payload(folded.get(3), YoloChangeEntryPayload.class).yoloEnabled());
    assertFalse(entries.stream().anyMatch(entry -> "message".equals(entry.getEntryType())));
  }

  /**
   * A user/custom batch advances one continuous cursor, while later in-flight input waits for next
   * harvest.
   */
  @Test
  void harvestsMessageBatchAndDefersInputSubmittedAfterTheCutoff() {
    Instant now = Instant.parse("2026-02-03T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession(301L, "messages", snapshot("messages"), false, now);
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
    assertEquals(
        List.of(user.id(), custom.id()), first.applied().stream().map(ThreadInput::id).toList());
    assertEquals(first.newHeadEntryId(), threadMapper.find(threadId).getHeadEntryId());
    assertEquals("queued", inputMapper.find(later.id()).getStatus());

    List<HarnessSessionEntryDO> entries = entryMapper.listBySession(created.sessionId());
    HarnessSessionEntryDO userEntry =
        entryMapper.find(created.sessionId(), first.applied().get(0).appliedEntryId());
    HarnessSessionEntryDO customEntry =
        entryMapper.find(created.sessionId(), first.applied().get(1).appliedEntryId());
    assertEquals(rootHead, userEntry.getParentEntryId());
    assertEquals(userEntry.getId(), customEntry.getParentEntryId());
    assertEquals(
        AgentMessageRole.USER, payload(userEntry, MessageEntryPayload.class).message().role());
    assertEquals(
        AgentMessageRole.SYSTEM,
        payload(customEntry, CustomMessageEntryPayload.class).message().role());
    assertEquals(3, entries.size());

    ThreadTransactions.HarvestResult second =
        transactions.harvestQueuedInputs(threadId, "message-owner", now.plusSeconds(3));
    assertTrue(second.hasMessage());
    assertEquals(List.of(later.id()), second.applied().stream().map(ThreadInput::id).toList());
    HarnessSessionEntryDO laterEntry =
        entryMapper.find(created.sessionId(), second.newHeadEntryId());
    assertEquals(customEntry.getId(), laterEntry.getParentEntryId());
    assertEquals("applied", inputMapper.find(later.id()).getStatus());
    assertEquals(second.newHeadEntryId(), threadMapper.find(threadId).getHeadEntryId());
  }

  /**
   * Stop is replay-safe, cancels all input kinds, and Retry is legal only after a durable failure.
   */
  @Test
  void stopsInInputOrderAndRetriesOnlyFailedThreads() {
    Instant now = Instant.parse("2026-02-04T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession(401L, "stop", snapshot("stop"), false, now);
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

  /**
   * Fencing protects durable writes; wait, compaction, failure, and quiescence preserve lifecycle
   * facts.
   */
  @Test
  void fencesLostOwnersAndPersistsWaitFailureCompactionAndQuiescenceStates() {
    Instant now = Instant.parse("2026-02-05T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession(501L, "lifecycle", snapshot("lifecycle"), false, now);
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

    ThreadTransactions.SessionCreateResult work =
        transactions.createSession(502L, "work", snapshot("work"), false, now);
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

  /** Invalid mailbox operations and stale processors leave durable thread rows unchanged. */
  @Test
  void rejectsInvalidInputsAndDoesNotLetLostOwnersWrite() {
    Instant now = Instant.parse("2026-02-06T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession(601L, "validation", snapshot("validation"), false, now);
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
        () -> transactions.submitSetToolset(threadId, null, "null-tools", now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.submitSetToolset(threadId, List.of(""), "blank-tool", now));
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

  private static AgentSnapshot snapshot(String value) {
    return new AgentSnapshot(
        "system-" + value,
        "model-" + value,
        "variant-" + value,
        List.of("tool-" + value),
        List.of(),
        List.of(),
        "{}");
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
