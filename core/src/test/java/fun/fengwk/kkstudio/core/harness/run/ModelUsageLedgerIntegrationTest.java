package fun.fengwk.kkstudio.core.harness.run;

import static fun.fengwk.kkstudio.core.harness.HarnessUsageFixtures.completedUsageDraft;
import static fun.fengwk.kkstudio.core.harness.HarnessUsageFixtures.toolCallsUsageDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.usage.store.MysqlModelUsageRecordStore;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordIdGenerator;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Assistant Entry 与模型 usage ledger 的真实数据库事务契约。 */
@SpringBootTest(classes = CoreTestApplication.class)
class ModelUsageLedgerIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");

  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort toolPreparation;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private MysqlModelUsageRecordStore usageRecordStore;
  @Autowired private ModelUsageRecordIdGenerator usageRecordIds;
  @Autowired private ToolInvocationIdGenerator invocationIds;
  @Autowired private SnowflakeSessionIdGenerator sessionIds;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from model_usage_record");
    jdbc.update("delete from harness_run_control_message");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
  }

  /** complete 只提交一次 Assistant Entry 与完整 usage 快照，重复完成不产生副本。 */
  @Test
  void completePersistsFullLedgerExactlyOnce() {
    Claimed claimed = claimedRun("complete-worker");
    ModelUsageDraft draft = completedUsageDraft();
    MessageEntryPayload assistant = assistant("answer", List.of(), draft);

    assertTrue(
        transactions.complete(
            claimed.run(),
            assistant,
            draft,
            assistantCompleted(claimed.run()),
            NOW.plusSeconds(1)));
    assertFalse(
        transactions.complete(
            claimed.run(),
            assistant,
            draft,
            assistantCompleted(claimed.run()),
            NOW.plusSeconds(2)));

    Session session = sessionStore.find(claimed.sessionId()).orElseThrow();
    List<SessionEntry> assistantEntries =
        sessionStore.listChildren(claimed.sessionId(), claimed.run().triggerEntryId());
    assertEquals(1, assistantEntries.size());
    assertEquals(session.leafEntryId(), assistantEntries.get(0).id());
    assertEquals(
        AgentMessageRole.ASSISTANT,
        ((MessageEntryPayload) assistantEntries.get(0).payload()).message().role());

    List<ModelUsageRecord> records = usageRecordStore.listByRunId(claimed.run().id());
    assertEquals(1, records.size());
    assertLedger(
        records.get(0),
        draft,
        claimed.sessionId(),
        claimed.run().id(),
        session.leafEntryId(),
        claimed.run().attempt(),
        claimed.run().turnIndex(),
        NOW.plusSeconds(1));
    assertEquals(RunStatus.SUCCEEDED, runStore.find(claimed.run().id()).orElseThrow().status());
    assertNull(session.activeRunId());
  }

  /** prepareTools 让 ledger 与 Invocation 引用同一条 Assistant Entry。 */
  @Test
  void prepareToolsSharesAssistantEntryWithInvocation() {
    Claimed claimed = claimedRun("tools-worker");
    ModelUsageDraft draft = toolCallsUsageDraft();
    ToolCall call = new ToolCall("call-1", "read", "{}");

    assertTrue(
        toolPreparation.prepare(
            claimed.run(),
            assistant("calling", List.of(call), draft),
            draft,
            List.of(call),
            List.of(readBinding()),
            Path.of("."),
            Path.of("."),
            List.of(assistantCompleted(claimed.run())),
            NOW.plusSeconds(1)));

    ModelUsageRecord record = usageRecordStore.listByRunId(claimed.run().id()).get(0);
    ToolInvocation invocation = invocationStore.listByRun(claimed.run().id()).get(0);
    assertEquals(record.assistantEntryId(), invocation.assistantEntryId());
    assertEquals(
        sessionStore.find(claimed.sessionId()).orElseThrow().leafEntryId(),
        record.assistantEntryId());
    assertEquals(RunStatus.WAITING_TOOLS, runStore.find(claimed.run().id()).orElseThrow().status());
  }

  /** cancel 在两种 Assistant 提交路径中均先胜出，因而不会生成 ledger。 */
  @Test
  void cancelWinsBeforeCompleteAndPrepareToolsLedgerInsert() {
    Claimed complete = claimedRun("complete-cancel-worker");
    assertTrue(runStore.requestCancel(complete.run().id(), NOW.plusSeconds(1)));
    AgentRun completeWithCancel =
        runStore
            .claimDue("complete-cancel-reclaim", NOW.plusSeconds(31), Duration.ofSeconds(30))
            .orElseThrow();
    ModelUsageDraft completedDraft = completedUsageDraft();

    assertTrue(
        transactions.complete(
            completeWithCancel,
            assistant("ignored", List.of(), completedDraft),
            completedDraft,
            assistantCompleted(completeWithCancel),
            NOW.plusSeconds(32)));
    assertTrue(usageRecordStore.listByRunId(complete.run().id()).isEmpty());
    assertEquals(2, countEntries(complete.sessionId()));

    Claimed tools = claimedRun("tools-cancel-worker");
    assertTrue(runStore.requestCancel(tools.run().id(), NOW.plusSeconds(1)));
    AgentRun toolsWithCancel =
        runStore
            .claimDue("tools-cancel-reclaim", NOW.plusSeconds(31), Duration.ofSeconds(30))
            .orElseThrow();
    ModelUsageDraft toolDraft = toolCallsUsageDraft();
    ToolCall call = new ToolCall("cancelled-call", "read", "{}");

    assertTrue(
        toolPreparation.prepare(
            toolsWithCancel,
            assistant("ignored", List.of(call), toolDraft),
            toolDraft,
            List.of(call),
            List.of(readBinding()),
            Path.of("."),
            Path.of("."),
            List.of(assistantCompleted(toolsWithCancel)),
            NOW.plusSeconds(32)));
    assertTrue(usageRecordStore.listByRunId(tools.run().id()).isEmpty());
    assertEquals(2, countEntries(tools.sessionId()));
    assertTrue(invocationStore.listByRun(tools.run().id()).isEmpty());
  }

  /** 过期 ownership 的 complete/prepareTools 都返回 false，且不写 Assistant 或 ledger。 */
  @Test
  void staleOwnershipReturnsFalseWithoutLedger() {
    Claimed claimed = claimedRun("stale-worker");
    runStore.claimDue("new-owner", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();
    ModelUsageDraft completedDraft = completedUsageDraft();
    ModelUsageDraft toolDraft = toolCallsUsageDraft();
    ToolCall call = new ToolCall("stale-call", "read", "{}");

    assertFalse(
        transactions.complete(
            claimed.run(),
            assistant("stale", List.of(), completedDraft),
            completedDraft,
            assistantCompleted(claimed.run()),
            NOW.plusSeconds(32)));
    assertFalse(
        toolPreparation.prepare(
            claimed.run(),
            assistant("stale", List.of(call), toolDraft),
            toolDraft,
            List.of(call),
            List.of(readBinding()),
            Path.of("."),
            Path.of("."),
            List.of(assistantCompleted(claimed.run())),
            NOW.plusSeconds(32)));

    assertTrue(usageRecordStore.listByRunId(claimed.run().id()).isEmpty());
    assertEquals(2, countEntries(claimed.sessionId()));
    assertTrue(invocationStore.listByRun(claimed.run().id()).isEmpty());
  }

  /** ledger unique 冲突会回滚此前追加的 Assistant Entry，不推进 Run 或创建 Invocation。 */
  @Test
  void ledgerConflictRollsBackAssistantAndRunState() {
    Claimed claimed = claimedRun("ledger-conflict-worker");
    ModelUsageDraft draft = toolCallsUsageDraft();
    usageRecordStore.insert(
        new ModelUsageRecord(
            usageRecordIds.newModelUsageRecordId(),
            claimed.sessionId(),
            claimed.run().id(),
            sessionIds.newEntryId(),
            claimed.run().attempt(),
            claimed.run().turnIndex(),
            draft,
            NOW.plusMillis(1)));
    ToolCall call = new ToolCall("ledger-conflict", "read", "{}");

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            toolPreparation.prepare(
                claimed.run(),
                assistant("calling", List.of(call), draft),
                draft,
                List.of(call),
                List.of(readBinding()),
                Path.of("."),
                Path.of("."),
                List.of(assistantCompleted(claimed.run())),
                NOW.plusSeconds(1)));

    assertEquals(1, usageRecordStore.listByRunId(claimed.run().id()).size());
    assertRunningWithoutAssistantOrInvocation(claimed);
  }

  /** ledger 插入成功后 Invocation unique 冲突仍回滚 ledger、Assistant Entry 与 Run transition。 */
  @Test
  void invocationConflictAfterLedgerInsertRollsBackWholeTransaction() {
    Claimed claimed = claimedRun("invocation-conflict-worker");
    ModelUsageDraft draft = toolCallsUsageDraft();
    ToolCall call = new ToolCall("duplicate-call", "read", "{}");
    insertConflictingInvocation(claimed.run().id(), call.id());

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            toolPreparation.prepare(
                claimed.run(),
                assistant("calling", List.of(call), draft),
                draft,
                List.of(call),
                List.of(readBinding()),
                Path.of("."),
                Path.of("."),
                List.of(assistantCompleted(claimed.run())),
                NOW.plusSeconds(1)));

    assertTrue(usageRecordStore.listByRunId(claimed.run().id()).isEmpty());
    assertEquals(1, invocationStore.listByRun(claimed.run().id()).size());
    assertEquals(call.id(), invocationStore.listByRun(claimed.run().id()).get(0).toolCallId());
    assertRunningWithoutAssistant(claimed);
  }

  /** usageDraft 的空值检查必须先于 Assistant、ownership 和其它参数校验。 */
  @Test
  void rejectsMissingUsageDraftFirst() {
    NullPointerException completeError =
        assertThrows(
            NullPointerException.class, () -> transactions.complete(null, null, null, null, null));
    NullPointerException toolsError =
        assertThrows(
            NullPointerException.class,
            () -> transactions.prepareTools(null, null, null, null, null, null, null, null, null));

    assertEquals("usageDraft", completeError.getMessage());
    assertEquals("usageDraft", toolsError.getMessage());
  }

  private Claimed claimedRun(String owner) {
    long sessionId = sessionIds.newSessionId();
    sessionStore.create(Session.root(sessionId, null, "usage-ledger-test", false, NOW));
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload snapshot =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, snapshot.type(), snapshot, NOW),
        null,
        0L);
    AgentRun queued =
        transactions.submitUserMessage(
            sessionId,
            snapshotId,
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("go"))),
            NOW);
    AgentRun claimed = runStore.claimDue(owner, NOW, Duration.ofSeconds(30)).orElseThrow();
    assertEquals(queued.id(), claimed.id());
    return new Claimed(sessionId, claimed);
  }

  private void insertConflictingInvocation(long runId, String toolCallId) {
    jdbc.update(
        "insert into tool_invocation (id, run_id, assistant_entry_id, ordinal, tool_call_id,"
            + " tool_name, tool_version, target_type, arguments_json, status, permission_action,"
            + " deadline_at, gmt_create, gmt_modified) values (?, ?, ?, 0, ?, 'read', '1',"
            + " 'CLOUD', '{}', 'QUEUED', 'ALLOW', ?, ?, ?)",
        invocationIds.newInvocationId(),
        runId,
        sessionIds.newEntryId(),
        toolCallId,
        Timestamp.from(NOW.plusSeconds(30)),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private void assertRunningWithoutAssistantOrInvocation(Claimed claimed) {
    assertTrue(invocationStore.listByRun(claimed.run().id()).isEmpty());
    assertRunningWithoutAssistant(claimed);
  }

  private void assertRunningWithoutAssistant(Claimed claimed) {
    AgentRun stored = runStore.find(claimed.run().id()).orElseThrow();
    Session session = sessionStore.find(claimed.sessionId()).orElseThrow();
    assertEquals(RunStatus.RUNNING, stored.status());
    assertEquals(0, stored.turnIndex());
    assertEquals(0L, stored.eventSequence());
    assertEquals(claimed.run().triggerEntryId(), session.leafEntryId());
    assertEquals(claimed.run().id(), session.activeRunId());
    assertEquals(2, countEntries(claimed.sessionId()));
    assertTrue(runStore.listAfter(claimed.run().id(), 0, 10).isEmpty());
  }

  private int countEntries(long sessionId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from harness_session_entry where session_id = ?",
            Long.class,
            sessionId);
    return count == null ? 0 : count.intValue();
  }

  private static MessageEntryPayload assistant(
      String text, List<ToolCall> calls, ModelUsageDraft usageDraft) {
    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new TextMessageContent(text));
    calls.forEach(
        call ->
            contents.add(
                new ToolCallMessageContent(call.id(), call.toolName(), call.argumentsJson())));
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(
            usageDraft.stopReason(), usageDraft.usage(), usageDraft.cost()));
  }

  private static ToolBinding readBinding() {
    return ToolBinding.of(
        new ToolDescriptor(
            "read",
            "1",
            "read",
            null,
            new ToolParamsSchema("", Map.of(), Set.of(), true),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30)));
  }

  private static RunEventDraft assistantCompleted(AgentRun run) {
    return new RunEventDraft(RunEventType.ASSISTANT_COMPLETED, RunEventPayloads.forAttempt(run));
  }

  private static void assertLedger(
      ModelUsageRecord record,
      ModelUsageDraft expected,
      long sessionId,
      long runId,
      long assistantEntryId,
      int attempt,
      int turnIndex,
      Instant createdAt) {
    assertTrue(record.id() > 0);
    assertEquals(sessionId, record.sessionId());
    assertEquals(runId, record.runId());
    assertEquals(assistantEntryId, record.assistantEntryId());
    assertEquals(attempt, record.attempt());
    assertEquals(turnIndex, record.turnIndex());
    assertEquals(createdAt, record.createdAt());

    ModelUsageDraft actual = record.draft();
    assertEquals(expected.providerResourceId(), actual.providerResourceId());
    assertEquals(expected.modelResourceId(), actual.modelResourceId());
    assertEquals(expected.providerType(), actual.providerType());
    assertEquals(expected.providerModelId(), actual.providerModelId());
    assertEquals(expected.promptCacheMode(), actual.promptCacheMode());
    assertEquals(expected.promptCacheRetention(), actual.promptCacheRetention());
    assertEquals(expected.cacheEligible(), actual.cacheEligible());
    assertEquals(expected.cacheAffinityKey(), actual.cacheAffinityKey());
    assertEquals(expected.stopReason(), actual.stopReason());

    ModelUsage expectedUsage = expected.usage();
    ModelUsage actualUsage = actual.usage();
    assertEquals(expectedUsage.inputTokens(), actualUsage.inputTokens());
    assertEquals(expectedUsage.outputTokens(), actualUsage.outputTokens());
    assertEquals(expectedUsage.cacheReadTokens(), actualUsage.cacheReadTokens());
    assertEquals(expectedUsage.cacheWriteTokens(), actualUsage.cacheWriteTokens());
    assertEquals(expectedUsage.cacheWriteLongTokens(), actualUsage.cacheWriteLongTokens());
    assertEquals(expectedUsage.reasoningTokens(), actualUsage.reasoningTokens());
    assertEquals(expectedUsage.providerTotalTokens(), actualUsage.providerTotalTokens());

    ModelPricing expectedPricing = expected.pricing();
    ModelPricing actualPricing = actual.pricing();
    assertEquals(expectedPricing.currency(), actualPricing.currency());
    assertEquals(expectedPricing.pricingTier(), actualPricing.pricingTier());
    assertEquals(expectedPricing.serviceTier(), actualPricing.serviceTier());
    assertEquals(expectedPricing.serviceTierMultiplier(), actualPricing.serviceTierMultiplier());
    assertEquals(expectedPricing.version(), actualPricing.version());
    assertEquals(expectedPricing.inputPerMillionTokens(), actualPricing.inputPerMillionTokens());
    assertEquals(expectedPricing.outputPerMillionTokens(), actualPricing.outputPerMillionTokens());
    assertEquals(
        expectedPricing.cacheReadPerMillionTokens(), actualPricing.cacheReadPerMillionTokens());
    assertEquals(
        expectedPricing.cacheWritePerMillionTokens(), actualPricing.cacheWritePerMillionTokens());
    assertEquals(
        expectedPricing.cacheWriteLongPerMillionTokens(),
        actualPricing.cacheWriteLongPerMillionTokens());
    assertEquals(
        expectedPricing.reasoningPerMillionTokens(), actualPricing.reasoningPerMillionTokens());

    ModelCost expectedCost = expected.cost();
    ModelCost actualCost = actual.cost();
    assertEquals(expectedCost.currency(), actualCost.currency());
    assertEquals(expectedCost.input(), actualCost.input());
    assertEquals(expectedCost.output(), actualCost.output());
    assertEquals(expectedCost.cacheRead(), actualCost.cacheRead());
    assertEquals(expectedCost.cacheWrite(), actualCost.cacheWrite());
    assertEquals(expectedCost.cacheWriteLong(), actualCost.cacheWriteLong());
    assertEquals(expectedCost.reasoning(), actualCost.reasoning());
    assertEquals(expectedCost.total(), actualCost.total());

    assertEquals(expected.requestId(), actual.requestId());
    assertEquals(expected.reportedServiceTier(), actual.reportedServiceTier());
    assertEquals(expected.rawUsageJson(), actual.rawUsageJson());
  }

  private record Claimed(long sessionId, AgentRun run) {}
}
