package fun.fengwk.kkstudio.core.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProperties;
import fun.fengwk.kkstudio.core.harness.tool.service.HarnessSessionYoloService;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolDecisionConflictException;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolInvocationDecisionService;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolPolicyResolver;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseArtifactStore;
import fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseToolInvocationWorkerStore;
import fun.fengwk.kkstudio.core.harness.tool.worker.ToolInvocationTransactionService;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionTree;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPermissionDecision;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = CoreTestApplication.class)
class ToolInvocationIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");

  @Autowired private ToolSettingsProperties toolSettingsProperties;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIds;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort preparationPort;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private DatabaseToolInvocationWorkerStore workerStore;
  @Autowired private ToolInvocationTransactionService toolTransactions;
  @Autowired private DatabaseArtifactStore artifactStore;
  @Autowired private ToolInvocationDecisionService decisionService;
  @Autowired private HarnessSessionYoloService yoloService;
  @Autowired private ToolPolicyResolver policyResolver;
  @Autowired private SessionTree sessionTree;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    jdbcTemplate.update("delete from tool_artifact");
    jdbcTemplate.update("delete from tool_invocation");
    jdbcTemplate.update("delete from harness_run_event");
    jdbcTemplate.update("delete from harness_run");
    jdbcTemplate.update("delete from harness_session_entry");
    jdbcTemplate.update("delete from harness_session");
  }

  /** 无 UI 参与时 ASK 仍持久 WAITING_APPROVAL、prompt preview 和 WAITING_TOOLS。 */
  @Test
  void persistsAskWithoutUi() {
    configureToolSettings("{\"permission\":{\"write\":\"ask\"}}");
    Claimed claimed = claimedRun(false);

    assertTrue(
        prepare(
            claimed.run(),
            List.of(new ToolCall("call-1", "write", "{\"path\":\"notes.txt\"}")),
            List.of(binding("write"))));

    ToolInvocation invocation = invocationStore.listByRun(claimed.run().id()).get(0);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, invocation.status());
    assertEquals(RunStatus.WAITING_TOOLS, runStore.find(claimed.run().id()).orElseThrow().status());
    assertEquals(
        claimed.run().id(), sessionStore.find(claimed.sessionId()).orElseThrow().activeRunId());
    RunEvent requested =
        runStore.listAfter(claimed.run().id(), 0, 20).stream()
            .filter(event -> event.type() == RunEventType.PERMISSION_REQUESTED)
            .findFirst()
            .orElseThrow();
    assertTrue(requested.payloadJson().contains("\"tool\":\"write\""));
    assertTrue(requested.payloadJson().contains("\"workdir\""));
    assertTrue(requested.payloadJson().contains("\"arguments\""));
  }

  /** allow/deny/yolo 映射到 QUEUED/FAILED/QUEUED；deny 不取消或终止整个 Run。 */
  @Test
  void evaluatesAllowDenyAndYoloWithoutCancellingRun() {
    configureToolSettings("{\"permission\":{\"write\":\"allow\"}}");
    Claimed allow = claimedRun(false);
    prepare(
        allow.run(),
        List.of(new ToolCall("allow", "write", "{\"path\":\"a.txt\"}")),
        List.of(binding("write")));
    assertEquals(
        ToolInvocationStatus.QUEUED, invocationStore.listByRun(allow.run().id()).get(0).status());

    configureToolSettings("{\"permission\":{\"write\":\"deny\"}}");
    Claimed deny = claimedRun(false);
    prepare(
        deny.run(),
        List.of(new ToolCall("deny", "write", "{\"path\":\"d.txt\"}")),
        List.of(binding("write")));
    ToolInvocation denied = invocationStore.listByRun(deny.run().id()).get(0);
    assertEquals(ToolInvocationStatus.FAILED, denied.status());
    assertNotNull(denied.resultJson());
    assertTrue(denied.resultJson().contains("Permission denied for write."));
    assertEquals(RunStatus.WAITING_TOOLS, runStore.find(deny.run().id()).orElseThrow().status());
    assertFalse(
        runStore.listAfter(deny.run().id(), 0, 20).stream()
            .map(RunEvent::type)
            .anyMatch(
                type -> type == RunEventType.RUN_CANCELLED || type == RunEventType.RUN_FAILED));

    configureToolSettings("{\"permission\":{\"write\":\"deny\"}}");
    Claimed yolo = claimedRun(true);
    prepare(
        yolo.run(),
        List.of(new ToolCall("yolo", "write", "{\"path\":\"y.txt\"}")),
        List.of(binding("write")));
    assertEquals(
        ToolInvocationStatus.QUEUED, invocationStore.listByRun(yolo.run().id()).get(0).status());

    configureToolSettings("{}");
    Claimed environment = claimedRun(false);
    prepare(
        environment.run(),
        List.of(new ToolCall("environment", "read", "{\"path\":\"remote.txt\"}")),
        List.of(environmentBinding("read", 42L)));
    ToolInvocation remote = invocationStore.listByRun(environment.run().id()).get(0);
    assertEquals(ToolTargetType.ENVIRONMENT, remote.targetType());
    assertEquals(42L, remote.environmentId());
  }

  /** 多调用严格按 Assistant source order 持久；事件失败回滚 Entry、Invocation 与 Run 状态。 */
  @Test
  void preservesSourceOrdinalAndRollsBackWholeBarrier() {
    configureToolSettings("{}");
    Claimed claimed = claimedRun(false);
    assertTrue(
        prepare(
            claimed.run(),
            List.of(
                new ToolCall("second-id", "read", "{\"path\":\"b\"}"),
                new ToolCall("first-id", "write", "{\"path\":\"a\"}")),
            List.of(binding("read"), binding("write"))));
    assertEquals(
        List.of("second-id", "first-id"),
        invocationStore.listByRun(claimed.run().id()).stream()
            .map(ToolInvocation::toolCallId)
            .toList());
    assertEquals(
        List.of(0, 1),
        invocationStore.listByRun(claimed.run().id()).stream()
            .map(ToolInvocation::ordinal)
            .toList());

    Claimed rollback = claimedRun(false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preparationPort.prepare(
                rollback.run(),
                assistant(List.of(new ToolCall("rollback", "read", "{\"path\":\"x\"}"))),
                List.of(new ToolCall("rollback", "read", "{\"path\":\"x\"}")),
                List.of(binding("read")),
                Path.of("/tmp/workspace"),
                Path.of("/tmp/workspace"),
                List.of(new RunEventDraft(RunEventType.ASSISTANT_COMPLETED, "{}")),
                NOW.plusSeconds(1)));
    assertTrue(invocationStore.listByRun(rollback.run().id()).isEmpty());
    assertEquals(RunStatus.RUNNING, runStore.find(rollback.run().id()).orElseThrow().status());
    assertEquals(
        rollback.run().triggerEntryId(),
        sessionStore.find(rollback.sessionId()).orElseThrow().leafEntryId());
  }

  /** Decision 拒绝未知 Invocation。 */
  @Test
  void rejectsUnknownInvocation() {
    configureToolSettings("{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> decisionService.decide(Long.MAX_VALUE, ToolPermissionDecision.ALLOW));
  }

  /** decision 相同请求幂等；冲突决定、终态 Run 与非 ASK Invocation 均拒绝。 */
  @Test
  void resolvesPermissionIdempotentlyAndRejectsConflictsGlobally() {
    configureToolSettings("{\"permission\":{\"write\":\"ask\"}}");
    Claimed allowClaimed = claimedRun(false);
    prepare(
        allowClaimed.run(),
        List.of(new ToolCall("allow-decision", "write", "{\"path\":\"a\"}")),
        List.of(binding("write")));
    ToolInvocation pending = invocationStore.listByRun(allowClaimed.run().id()).get(0);

    ToolInvocation allowed = decisionService.decide(pending.id(), ToolPermissionDecision.ALLOW);
    ToolInvocation repeated = decisionService.decide(pending.id(), ToolPermissionDecision.ALLOW);
    assertEquals(ToolInvocationStatus.QUEUED, allowed.status());
    assertEquals(
        Duration.ofSeconds(30), Duration.between(allowed.updatedAt(), allowed.deadlineAt()));
    assertEquals(ToolPermissionDecision.ALLOW, repeated.permissionDecision());
    assertThrows(
        ToolDecisionConflictException.class,
        () -> decisionService.decide(pending.id(), ToolPermissionDecision.DENY));
    assertEquals(
        1,
        runStore.listAfter(allowClaimed.run().id(), 0, 30).stream()
            .filter(event -> event.type() == RunEventType.PERMISSION_RESOLVED)
            .count());

    Claimed denyClaimed = claimedRun(false);
    prepare(
        denyClaimed.run(),
        List.of(new ToolCall("deny-decision", "write", "{\"path\":\"d\"}")),
        List.of(binding("write")));
    ToolInvocation denied =
        decisionService.decide(
            invocationStore.listByRun(denyClaimed.run().id()).get(0).id(),
            ToolPermissionDecision.DENY);
    assertEquals(ToolInvocationStatus.FAILED, denied.status());
    assertTrue(denied.resultJson().contains("Permission denied by user for write."));
    assertFalse(denied.resultJson().contains("\"terminate\""));
    assertEquals(
        RunStatus.WAITING_TOOLS, runStore.find(denyClaimed.run().id()).orElseThrow().status());

    Claimed settledClaimed = claimedRun(false);
    prepare(
        settledClaimed.run(),
        List.of(new ToolCall("settled", "write", "{\"path\":\"s\"}")),
        List.of(binding("write")));
    ToolInvocation settled = invocationStore.listByRun(settledClaimed.run().id()).get(0);
    jdbcTemplate.update(
        "update harness_run set status = 'CANCELLED' where id = ?", settledClaimed.run().id());
    assertThrows(
        ToolDecisionConflictException.class,
        () -> decisionService.decide(settled.id(), ToolPermissionDecision.ALLOW));

    configureToolSettings("{\"permission\":{\"write\":\"allow\"}}");
    Claimed automatic = claimedRun(false);
    prepare(
        automatic.run(),
        List.of(new ToolCall("automatic", "write", "{\"path\":\"a\"}")),
        List.of(binding("write")));
    ToolInvocation alreadyQueued = invocationStore.listByRun(automatic.run().id()).get(0);
    assertThrows(
        ToolDecisionConflictException.class,
        () -> decisionService.decide(alreadyQueued.id(), ToolPermissionDecision.ALLOW));
  }

  /** 决策事务拒绝损坏的 ASK action 与非正 timeout budget，避免把坏快照继续推进。 */
  @Test
  void rejectsCorruptedApprovalState() {
    configureToolSettings("{\"permission\":{\"write\":\"ask\"}}");

    ToolInvocation invalidPending = askInvocation("invalid-pending");
    jdbcTemplate.update(
        "update tool_invocation set permission_action = 'ALLOW' where id = ?", invalidPending.id());
    assertThrows(
        IllegalStateException.class,
        () -> decisionService.decide(invalidPending.id(), ToolPermissionDecision.ALLOW));

    ToolInvocation invalidTimeout = askInvocation("invalid-timeout");
    jdbcTemplate.update(
        "update tool_invocation set deadline_at = gmt_create where id = ?", invalidTimeout.id());
    assertThrows(
        IllegalStateException.class,
        () -> decisionService.decide(invalidTimeout.id(), ToolPermissionDecision.ALLOW));

    ToolInvocation decided = askInvocation("decided-corruption");
    decisionService.decide(decided.id(), ToolPermissionDecision.ALLOW);
    jdbcTemplate.update(
        "update tool_invocation set permission_action = 'ALLOW' where id = ?", decided.id());
    assertThrows(
        IllegalStateException.class,
        () -> decisionService.decide(decided.id(), ToolPermissionDecision.ALLOW));
  }

  /** Policy resolver 拒绝没有 Root 标识的孤儿 Session。 */
  @Test
  void rejectsOrphanSessionForPolicyResolution() {
    HarnessSessionDO orphan = new HarnessSessionDO();
    orphan.setId(99L);
    assertThrows(IllegalStateException.class, () -> policyResolver.resolve(orphan));
  }

  /** Policy resolver 只接受合法 Root 拓扑，不能因悬空或伪 Root 引用而静默降级权限。 */
  @Test
  void rejectsInvalidPermissionPolicyReferences() {
    HarnessSessionDO missingRoot = new HarnessSessionDO();
    missingRoot.setId(1L);
    missingRoot.setRootSessionId(Long.MAX_VALUE);
    assertThrows(IllegalStateException.class, () -> policyResolver.resolve(missingRoot));

    HarnessSessionDO invalidRoot = new HarnessSessionDO();
    invalidRoot.setId(2L);
    invalidRoot.setRootSessionId(2L);
    invalidRoot.setParentSessionId(1L);
    assertThrows(IllegalStateException.class, () -> policyResolver.resolve(invalidRoot));
  }

  /** YOLO 服务拒绝损坏的 Root 拓扑与悬空 activeRunId，不发布错误运行事件。 */
  @Test
  void rejectsCorruptedYoloState() {
    assertThrows(IllegalArgumentException.class, () -> yoloService.get(Long.MAX_VALUE));

    configureToolSettings("{}");
    Session invalidRoot = sessionTree.create(null, "invalid-root");
    jdbcTemplate.update(
        "update harness_session set parent_session_id = ? where id = ?",
        invalidRoot.id() + 1,
        invalidRoot.id());
    assertThrows(IllegalStateException.class, () -> yoloService.get(invalidRoot.id()));

    Session missingRun = sessionTree.create(null, "missing-run");
    jdbcTemplate.update(
        "update harness_session set active_run_id = ? where id = ?",
        Long.MAX_VALUE,
        missingRun.id());
    assertThrows(IllegalStateException.class, () -> yoloService.set(missingRun.id(), true));
  }

  /** YOLO set 在 active run 不属于当前 Session 时拒绝写。 */
  @Test
  void rejectsYoloSetWhenActiveRunBelongsToAnotherSession() {
    configureToolSettings("{}");
    Session root = sessionTree.create(null, "root");
    assertEquals(root.id(), yoloService.set(root.id(), true).rootSessionId());

    Session otherRoot = sessionTree.create(null, "other");
    AgentRun foreignRun =
        transactions.submitUserMessage(otherRoot.id(), null, user(), NOW.plusSeconds(2));
    AgentRun foreignClaimed =
        runStore.claimDue("yolo-worker", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(foreignRun.id(), foreignClaimed.id());
    jdbcTemplate.update(
        "update harness_session set active_run_id = ? where id = ?",
        foreignClaimed.id(),
        root.id());
    assertThrows(IllegalStateException.class, () -> yoloService.set(root.id(), false));
  }

  /** YOLO set 在 active run 状态异常时拒绝写。 */
  @Test
  void rejectsYoloSetOnTerminalOrForeignActiveRun() {
    configureToolSettings("{}");
    Session root = sessionTree.create(null, "root");
    Session child = sessionTree.fork(root.id(), null);
    AgentRun queued = transactions.submitUserMessage(child.id(), null, user(), NOW.plusSeconds(2));
    AgentRun claimed =
        runStore.claimDue("yolo-worker", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(queued.id(), claimed.id());

    jdbcTemplate.update("update harness_run set status = 'CANCELLED' where id = ?", claimed.id());
    assertThrows(IllegalStateException.class, () -> yoloService.set(child.id(), true));

    Session stray = sessionTree.create(null, "stray");
    jdbcTemplate.update(
        "update harness_session set active_run_id = ? where id = ?", Long.MAX_VALUE, stray.id());
    assertThrows(IllegalStateException.class, () -> yoloService.set(stray.id(), true));
  }

  /** Root 创建继承 defaultYolo；Child 动态读取 Root，且无 active Run 时 set/get 仍持久可观察。 */
  @Test
  void inheritsAndDynamicallyUpdatesRootYoloForChildren() {
    configureToolSettings("{\"defaultYolo\":true,\"permission\":{\"write\":\"deny\"}}");
    Session root = sessionTree.create(null, "root");
    Session child = sessionTree.fork(root.id(), null);

    assertTrue(root.yoloEnabled());
    assertTrue(yoloService.get(child.id()).enabled());
    HarnessSessionYoloService.YoloState disabled = yoloService.set(child.id(), false);
    assertEquals(root.id(), disabled.rootSessionId());
    assertFalse(yoloService.get(root.id()).enabled());
    assertFalse(yoloService.get(child.id()).enabled());
    assertFalse(sessionStore.find(root.id()).orElseThrow().yoloEnabled());

    AgentRun childQueued =
        transactions.submitUserMessage(child.id(), null, user(), NOW.plusSeconds(2));
    AgentRun childRun =
        runStore.claimDue("child-worker", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(childQueued.id(), childRun.id());
    yoloService.set(root.id(), true);
    prepare(
        childRun,
        List.of(new ToolCall("child-yolo", "write", "{\"path\":\"child.txt\"}")),
        List.of(binding("write")));
    assertEquals(
        ToolInvocationStatus.QUEUED, invocationStore.listByRun(childRun.id()).get(0).status());
    yoloService.set(child.id(), false);
    assertEquals(
        2,
        runStore.listAfter(childRun.id(), 0, 10).stream()
            .filter(event -> event.type() == RunEventType.RUNTIME_STATE_CHANGED)
            .count());

    Session activeRoot = sessionTree.create(null, "active-root");
    Session idleChild = sessionTree.fork(activeRoot.id(), null);
    AgentRun rootQueued =
        transactions.submitUserMessage(activeRoot.id(), null, user(), NOW.plusSeconds(3));
    AgentRun rootRun =
        runStore.claimDue("root-worker", NOW.plusSeconds(3), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(rootQueued.id(), rootRun.id());
    yoloService.set(idleChild.id(), false);
    assertEquals(
        1,
        runStore.listAfter(rootRun.id(), 0, 10).stream()
            .filter(event -> event.type() == RunEventType.RUNTIME_STATE_CHANGED)
            .count());

    jdbcTemplate.update("update harness_run set status = 'CANCELLED' where id = ?", childRun.id());
    assertThrows(IllegalStateException.class, () -> yoloService.set(child.id(), true));
  }

  /**
   * Claims only Cloud work, preserves cancellation, and rejects a callback after lease ownership
   * changes.
   */
  @Test
  void claimsHeartbeatsCancelsAndReclaimsExpiredCloudInvocations() {
    configureToolSettings("{}");
    Claimed run = claimedRun(false);
    List<ToolCall> calls =
        List.of(
            new ToolCall("cancelled", "read", "{\"path\":\"a\"}"),
            new ToolCall("reclaimed", "write", "{\"path\":\"b\"}"));
    assertTrue(prepare(run.run(), calls, List.of(binding("read"), binding("write"))));

    ClaimedToolInvocation first =
        workerStore.claimDue("tool-a", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(ToolInvocationStatus.RUNNING, first.invocation().status());
    assertTrue(workerStore.heartbeat(first, NOW.plusSeconds(3), Duration.ofMinutes(1)));
    assertTrue(toolTransactions.requestCancel(first.invocation().id(), NOW.plusSeconds(4)));
    assertFalse(workerStore.heartbeat(first, NOW.plusSeconds(5), Duration.ofMinutes(1)));

    // An active cancellation remains owned by tool-a; the other queued invocation is selected.
    ClaimedToolInvocation original =
        workerStore.claimDue("tool-b", NOW.plusSeconds(5), Duration.ofMinutes(1)).orElseThrow();
    assertEquals("reclaimed", original.invocation().toolCallId());
    ToolInvocation activeCancel = workerStore.find(first.invocation().id()).orElseThrow();
    assertEquals(ToolInvocationStatus.CANCEL_REQUESTED, activeCancel.status());
    assertEquals("tool-a", activeCancel.leaseOwner());

    ClaimedToolInvocation cancelled =
        workerStore.claimDue("tool-b", NOW.plusSeconds(64), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(ToolInvocationStatus.CANCEL_REQUESTED, cancelled.invocation().status());
    assertTrue(
        toolTransactions.terminate(
            cancelled,
            ToolInvocationStatus.CANCELLED,
            ToolResult.error("cancelled", "cancelled"),
            "cancelled",
            NOW.plusSeconds(64)));

    ClaimedToolInvocation reclaimed =
        workerStore.claimDue("tool-d", NOW.plusSeconds(66), Duration.ofMinutes(1)).orElseThrow();
    assertTrue(reclaimed.recoveredLease());
    assertTrue(reclaimed.invocation().deadlineAt().isBefore(NOW.plusSeconds(66)));
    assertFalse(workerStore.heartbeat(original, NOW.plusSeconds(66), Duration.ofMinutes(1)));
    assertFalse(
        toolTransactions.terminate(
            original,
            ToolInvocationStatus.FAILED,
            ToolResult.error("reclaimed", "late"),
            "late callback",
            NOW.plusSeconds(66)));
    assertTrue(workerStore.find(reclaimed.invocation().id()).isPresent());
    assertFalse(toolTransactions.start(original, NOW.plusSeconds(126)));
    assertFalse(
        toolTransactions.appendPartial(
            original,
            List.of(
                new ToolResult(
                    "reclaimed", List.of(new TextToolContent("late")), false, "{}", false)),
            NOW.plusSeconds(126)));
    assertFalse(toolTransactions.requestCancel(Long.MAX_VALUE, NOW));
    assertFalse(toolTransactions.coordinate(Long.MAX_VALUE, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> workerStore.claimDue("", NOW, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class, () -> workerStore.heartbeat(reclaimed, NOW, Duration.ZERO));
    assertThrows(
        NullPointerException.class, () -> workerStore.heartbeat(null, NOW, Duration.ofSeconds(1)));
  }

  /** Queue scans only dispatch invocations while their parent Run remains WAITING_TOOLS. */
  @Test
  void doesNotClaimInvocationWhoseRunIsNotWaitingTools() {
    configureToolSettings("{}");
    Claimed run = claimedRun(false);
    assertTrue(
        prepare(
            run.run(),
            List.of(new ToolCall("not-waiting", "read", "{\"path\":\"a\"}")),
            List.of(binding("read"))));
    jdbcTemplate.update("update harness_run set status = 'QUEUED' where id = ?", run.run().id());

    assertTrue(workerStore.claimDue("tool-a", NOW.plusSeconds(2), Duration.ofMinutes(1)).isEmpty());
  }

  /**
   * Callback transactions lock and validate the Run before the invocation, so a moved Run cannot
   * receive a stale event or terminal result.
   */
  @Test
  void rejectsCallbackWhenRunIsNoLongerWaitingTools() {
    configureToolSettings("{}");
    Claimed run = claimedRun(false);
    assertTrue(
        prepare(
            run.run(),
            List.of(new ToolCall("moved-run", "read", "{\"path\":\"a\"}")),
            List.of(binding("read"))));
    ClaimedToolInvocation claimed =
        workerStore.claimDue("tool-a", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    jdbcTemplate.update("update harness_run set status = 'QUEUED' where id = ?", run.run().id());

    assertFalse(toolTransactions.start(claimed, NOW.plusSeconds(2)));
    assertFalse(
        toolTransactions.appendPartial(
            claimed,
            List.of(
                new ToolResult(
                    "moved-run", List.of(new TextToolContent("partial")), false, "{}", false)),
            NOW.plusSeconds(2)));
    assertFalse(
        toolTransactions.terminate(
            claimed,
            ToolInvocationStatus.SUCCEEDED,
            new ToolResult("moved-run", List.of(new TextToolContent("done")), false, "{}", false),
            null,
            NOW.plusSeconds(2)));
    assertTrue(
        runStore.listAfter(run.run().id(), 0, 20).stream()
            .noneMatch(
                event ->
                    event.type() == RunEventType.TOOL_STARTED
                        || event.type() == RunEventType.TOOL_DELTA_BATCH
                        || event.type() == RunEventType.TOOL_COMPLETED));
    assertEquals(
        ToolInvocationStatus.RUNNING, invocationStore.listByRun(run.run().id()).get(0).status());
  }

  /**
   * Callback journaling and coordination both acquire the Run lock first, so concurrent work on one
   * Run completes without lock inversion.
   */
  @Test
  void callbackAndCoordinatorUseConsistentRunFirstLockOrder() throws Exception {
    configureToolSettings("{}");
    Claimed run = claimedRun(false);
    assertTrue(
        prepare(
            run.run(),
            List.of(new ToolCall("lock-order", "read", "{\"path\":\"a\"}")),
            List.of(binding("read"))));
    ClaimedToolInvocation claimed =
        workerStore.claimDue("tool-a", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Boolean> callback =
          workers.submit(
              () -> {
                start.await();
                return toolTransactions.start(claimed, NOW.plusSeconds(2));
              });
      Future<Integer> coordinator =
          workers.submit(
              () -> {
                start.await();
                return toolTransactions.coordinateReadyRuns(NOW.plusSeconds(2));
              });
      start.countDown();
      assertTrue(callback.get(1, TimeUnit.SECONDS));
      assertEquals(0, coordinator.get(1, TimeUnit.SECONDS));
    } finally {
      workers.shutdownNow();
    }
  }

  /** Artifact reads are global and preserve complete bytes plus the immutable SHA-256 digest. */
  @Test
  void storesGloballyAddressableArtifactsWithStableTransportId() {
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);

    ArtifactRef ref = artifactStore.save("text/plain", "utf-8", content);
    content[0] = 'x';

    var artifact = artifactStore.find(ref.artifactId()).orElseThrow();
    assertEquals("abc", new String(artifact.content(), StandardCharsets.UTF_8));
    artifact.content()[0] = 'z';
    assertEquals("abc", new String(artifact.content(), StandardCharsets.UTF_8));
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", artifact.sha256());
    assertEquals("text/plain", ref.mediaType());
    assertEquals(3, ref.sizeBytes());
    assertTrue(artifactStore.find(null).isEmpty());
    assertTrue(artifactStore.find(" ").isEmpty());
    assertTrue(artifactStore.find("not-a-snowflake").isEmpty());
    assertTrue(artifactStore.find("0").isEmpty());
    assertTrue(artifactStore.find("9223372036854775808").isEmpty());
    String wrongId = Long.toString(Math.addExact(Long.parseLong(ref.artifactId()), 1));
    assertTrue(artifactStore.find(wrongId).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> artifactStore.save("", "utf-8", new byte[0]));
    assertThrows(NullPointerException.class, () -> artifactStore.save("text/plain", "utf-8", null));
  }

  /**
   * Partial journal data is append-only, and malformed terminal state rolls its coordinator
   * transaction back.
   */
  @Test
  void journalsPartialAndRollsBackCoordinatorOnCorruptTerminalResult() {
    configureToolSettings("{}");
    Claimed run = claimedRun(false);
    List<ToolCall> calls = List.of(new ToolCall("partial", "read", "{\"path\":\"a\"}"));
    assertTrue(prepare(run.run(), calls, List.of(binding("read"))));
    ClaimedToolInvocation claimed =
        workerStore.claimDue("tool-a", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    assertTrue(toolTransactions.start(claimed, NOW.plusSeconds(2)));
    assertTrue(toolTransactions.appendPartial(claimed, List.of(), NOW.plusSeconds(2)));
    assertTrue(
        toolTransactions.appendPartial(
            claimed,
            List.of(
                new ToolResult(
                    "partial", List.of(new TextToolContent("progress")), false, "{}", false)),
            NOW.plusSeconds(2)));
    RunEvent delta =
        runStore.listAfter(run.run().id(), 0, 20).stream()
            .filter(event -> event.type() == RunEventType.TOOL_DELTA_BATCH)
            .findFirst()
            .orElseThrow();
    assertToolEventAttempt(delta, run.run());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            toolTransactions.appendPartial(
                claimed,
                List.of(
                    new ToolResult(
                        "wrong", List.of(new TextToolContent("wrong")), false, "{}", false)),
                NOW.plusSeconds(2)));
    assertTrue(
        toolTransactions.terminate(
            claimed,
            ToolInvocationStatus.SUCCEEDED,
            new ToolResult("partial", List.of(new TextToolContent("done")), false, "{}", false),
            null,
            NOW.plusSeconds(3)));
    assertFalse(
        toolTransactions.terminate(
            claimed,
            ToolInvocationStatus.SUCCEEDED,
            new ToolResult("partial", List.of(new TextToolContent("late")), false, "{}", false),
            null,
            NOW.plusSeconds(3)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            toolTransactions.terminate(
                claimed,
                ToolInvocationStatus.RUNNING,
                ToolResult.error("partial", "bad"),
                null,
                NOW));
    assertThrows(IllegalArgumentException.class, () -> toolTransactions.requestCancel(0, NOW));
    Long leafBefore = sessionStore.find(run.sessionId()).orElseThrow().leafEntryId();
    jdbcTemplate.update(
        "update tool_invocation set result_json = ? where id = ?",
        "{bad",
        claimed.invocation().id());

    assertThrows(
        IllegalArgumentException.class,
        () -> toolTransactions.coordinateReadyRuns(NOW.plusSeconds(4)));
    assertEquals(leafBefore, sessionStore.find(run.sessionId()).orElseThrow().leafEntryId());
    assertEquals(RunStatus.WAITING_TOOLS, runStore.find(run.run().id()).orElseThrow().status());
  }

  /**
   * Terminal callbacks are materialized once in source ordinal order before the Run is requeued.
   */
  @Test
  void coordinatesTerminalResultsInOrdinalOrderIdempotently() throws Exception {
    configureToolSettings("{}");
    Claimed run = claimedRun(false);
    List<ToolCall> calls =
        List.of(
            new ToolCall("second-completes-first", "read", "{\"path\":\"a\"}"),
            new ToolCall("first-completes-second", "write", "{\"path\":\"b\"}"));
    assertTrue(prepare(run.run(), calls, List.of(binding("read"), binding("write"))));

    ClaimedToolInvocation first =
        workerStore.claimDue("tool-a", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    assertTrue(toolTransactions.start(first, NOW.plusSeconds(2)));
    assertTrue(
        toolTransactions.terminate(
            first,
            ToolInvocationStatus.SUCCEEDED,
            new ToolResult(
                "second-completes-first",
                List.of(
                    new JsonToolContent("{\"result\":1}"),
                    new ArtifactToolContent(new ArtifactRef("99", "text/plain", 12))),
                false,
                "{}",
                true),
            null,
            NOW.plusSeconds(2)));
    ClaimedToolInvocation second =
        workerStore.claimDue("tool-b", NOW.plusSeconds(3), Duration.ofMinutes(1)).orElseThrow();
    assertTrue(toolTransactions.start(second, NOW.plusSeconds(3)));
    assertTrue(
        toolTransactions.terminate(
            second,
            ToolInvocationStatus.SUCCEEDED,
            new ToolResult(
                "first-completes-second",
                List.of(new TextToolContent("second terminal")),
                false,
                "{}",
                true),
            null,
            NOW.plusSeconds(3)));

    ExecutorService coordinators = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Integer> one =
          coordinators.submit(
              () -> {
                start.await();
                return toolTransactions.coordinateReadyRuns(NOW.plusSeconds(4));
              });
      Future<Integer> two =
          coordinators.submit(
              () -> {
                start.await();
                return toolTransactions.coordinateReadyRuns(NOW.plusSeconds(4));
              });
      start.countDown();
      assertEquals(1, one.get() + two.get());
    } finally {
      coordinators.shutdownNow();
    }
    assertEquals(0, toolTransactions.coordinateReadyRuns(NOW.plusSeconds(5)));
    assertEquals(RunStatus.QUEUED, runStore.find(run.run().id()).orElseThrow().status());
    runStore.listAfter(run.run().id(), 0, 20).stream()
        .filter(
            event ->
                event.type() == RunEventType.TOOL_STARTED
                    || event.type() == RunEventType.TOOL_COMPLETED
                    || event.type() == RunEventType.TOOL_REQUEUED)
        .forEach(event -> assertToolEventAttempt(event, run.run()));
    Session session = sessionStore.find(run.sessionId()).orElseThrow();
    List<SessionEntry> path = sessionStore.loadPath(run.sessionId(), session.leafEntryId());
    List<String> toolCallIds =
        path.stream()
            .map(SessionEntry::payload)
            .filter(MessageEntryPayload.class::isInstance)
            .map(MessageEntryPayload.class::cast)
            .filter(payload -> payload.message().role() == AgentMessageRole.TOOL)
            .map(
                payload ->
                    ((ToolResultMessageContent) payload.message().contents().get(0)).toolCallId())
            .toList();
    assertEquals(List.of("second-completes-first", "first-completes-second"), toolCallIds);
    ToolResultMessageContent firstResult =
        path.stream()
            .map(SessionEntry::payload)
            .filter(MessageEntryPayload.class::isInstance)
            .map(MessageEntryPayload.class::cast)
            .filter(payload -> payload.message().role() == AgentMessageRole.TOOL)
            .map(payload -> (ToolResultMessageContent) payload.message().contents().get(0))
            .findFirst()
            .orElseThrow();
    assertTrue(firstResult.contents().get(0) instanceof JsonMessageContent);
    assertTrue(firstResult.contents().get(1) instanceof ArtifactMessageContent);
  }

  private void assertToolEventAttempt(RunEvent event, AgentRun expectedRun) {
    AgentRun lockedRun = runStore.find(event.runId()).orElseThrow();
    assertEquals(expectedRun.id(), lockedRun.id());
    assertTrue(
        event.payloadJson().contains("\"attempt\":" + lockedRun.attempt()), event.payloadJson());
    assertTrue(
        event.payloadJson().contains("\"turnIndex\":" + lockedRun.turnIndex()),
        event.payloadJson());
  }

  private ToolInvocation askInvocation(String toolCallId) {
    Claimed claimed = claimedRun(false);
    prepare(
        claimed.run(),
        List.of(new ToolCall(toolCallId, "write", "{\"path\":\"notes.txt\"}")),
        List.of(binding("write")));
    return invocationStore.listByRun(claimed.run().id()).get(0);
  }

  private boolean prepare(AgentRun run, List<ToolCall> calls, List<ToolBinding> bindings) {
    return preparationPort.prepare(
        run,
        assistant(calls),
        calls,
        bindings,
        Path.of("/tmp/workspace"),
        Path.of("/tmp/workspace"),
        List.of(
            new RunEventDraft(RunEventType.ASSISTANT_COMPLETED, RunEventPayloads.forAttempt(run))),
        NOW.plusSeconds(1));
  }

  private Claimed claimedRun(boolean yoloEnabled) {
    long sessionId = sessionIds.newSessionId();
    Session root = Session.root(sessionId, null, "session", yoloEnabled, NOW);
    sessionStore.create(root);
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload snapshot =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, snapshot.type(), snapshot, NOW),
        null,
        0L);
    AgentRun queued =
        transactions.submitUserMessage(sessionId, snapshotId, user(), NOW.plusMillis(1));
    AgentRun claimed =
        runStore
            .claimDue("worker-" + sessionId, NOW.plusMillis(1), Duration.ofMinutes(1))
            .orElseThrow();
    assertEquals(queued.id(), claimed.id());
    return new Claimed(sessionId, claimed);
  }

  private void configureToolSettings(String settingsJson) {
    toolSettingsProperties.setSettingsJson(settingsJson);
  }

  private static AgentMessage user() {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("go")));
  }

  private static MessageEntryPayload assistant(List<ToolCall> calls) {
    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new TextMessageContent("calling"));
    calls.forEach(
        call ->
            contents.add(
                new ToolCallMessageContent(call.id(), call.toolName(), call.argumentsJson())));
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(
            ProviderStopReason.TOOL_CALLS,
            new ModelUsage(1, 1, 0, 0, 0),
            new ModelCost("USD", BigDecimal.ZERO)));
  }

  private static ToolBinding binding(String name) {
    return ToolBinding.of(descriptor(name, ToolExecutionMode.CLOUD));
  }

  private static ToolBinding environmentBinding(String name, long environmentId) {
    return new ToolBinding(
        descriptor(name, ToolExecutionMode.ENVIRONMENT), ToolTargetType.ENVIRONMENT, environmentId);
  }

  private static ToolDescriptor descriptor(String name, ToolExecutionMode executionMode) {
    return new ToolDescriptor(
        name,
        "1",
        name,
        null,
        new ToolParamsSchema(
            "", Map.of("path", new ToolStringSchema("path")), Set.of("path"), false),
        executionMode,
        ToolSideEffect.IDEMPOTENT,
        Duration.ofSeconds(30));
  }

  private record Claimed(long sessionId, AgentRun run) {}
}
