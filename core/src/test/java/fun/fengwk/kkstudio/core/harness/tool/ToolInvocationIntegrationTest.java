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
import fun.fengwk.kkstudio.core.harness.tool.service.HarnessSessionYoloService;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolDecisionConflictException;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolInvocationDecisionService;
import fun.fengwk.kkstudio.core.harness.tool.service.WorkspaceToolPolicyResolver;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.workspace.service.WorkspaceService;
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
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionTree;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPermissionDecision;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import java.math.BigDecimal;
import java.nio.file.Path;
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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = CoreTestApplication.class)
class ToolInvocationIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");

  @Autowired private WorkspaceService workspaceService;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIds;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort preparationPort;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private ToolInvocationDecisionService decisionService;
  @Autowired private HarnessSessionYoloService yoloService;
  @Autowired private WorkspaceToolPolicyResolver policyResolver;
  @Autowired private SessionTree sessionTree;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    jdbcTemplate.update("delete from tool_invocation");
    jdbcTemplate.update("delete from harness_run_event");
    jdbcTemplate.update("delete from harness_run");
    jdbcTemplate.update("delete from harness_session_entry");
    jdbcTemplate.update("delete from harness_session");
    jdbcTemplate.update("delete from workspace");
  }

  /** 无 UI 参与时 ASK 仍持久 WAITING_APPROVAL、prompt preview 和 WAITING_TOOLS。 */
  @Test
  void persistsAskWithoutUi() {
    long workspaceId = workspace("{\"permission\":{\"write\":\"ask\"}}");
    Claimed claimed = claimedRun(workspaceId, false);

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
    long allowWorkspace = workspace("{\"permission\":{\"write\":\"allow\"}}");
    Claimed allow = claimedRun(allowWorkspace, false);
    prepare(
        allow.run(),
        List.of(new ToolCall("allow", "write", "{\"path\":\"a.txt\"}")),
        List.of(binding("write")));
    assertEquals(
        ToolInvocationStatus.QUEUED, invocationStore.listByRun(allow.run().id()).get(0).status());

    long denyWorkspace = workspace("{\"permission\":{\"write\":\"deny\"}}");
    Claimed deny = claimedRun(denyWorkspace, false);
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

    long yoloWorkspace = workspace("{\"permission\":{\"write\":\"deny\"}}");
    Claimed yolo = claimedRun(yoloWorkspace, true);
    prepare(
        yolo.run(),
        List.of(new ToolCall("yolo", "write", "{\"path\":\"y.txt\"}")),
        List.of(binding("write")));
    assertEquals(
        ToolInvocationStatus.QUEUED, invocationStore.listByRun(yolo.run().id()).get(0).status());

    long environmentWorkspace = workspace("{}");
    Claimed environment = claimedRun(environmentWorkspace, false);
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
    long workspaceId = workspace("{}");
    Claimed claimed = claimedRun(workspaceId, false);
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

    Claimed rollback = claimedRun(workspaceId, false);
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

  /** decision 相同请求幂等；冲突决定 409 领域冲突，跨 Workspace 拒绝。 */
  @Test
  void resolvesPermissionIdempotentlyAndRejectsConflictsAndCrossWorkspace() {
    long workspaceId = workspace("{\"permission\":{\"write\":\"ask\"}}");
    long otherWorkspaceId = workspace("{}");
    Claimed allowClaimed = claimedRun(workspaceId, false);
    prepare(
        allowClaimed.run(),
        List.of(new ToolCall("allow-decision", "write", "{\"path\":\"a\"}")),
        List.of(binding("write")));
    ToolInvocation pending = invocationStore.listByRun(allowClaimed.run().id()).get(0);

    ToolInvocation allowed =
        decisionService.decide(workspaceId, pending.id(), ToolPermissionDecision.ALLOW);
    ToolInvocation repeated =
        decisionService.decide(workspaceId, pending.id(), ToolPermissionDecision.ALLOW);
    assertEquals(ToolInvocationStatus.QUEUED, allowed.status());
    assertEquals(
        Duration.ofSeconds(30), Duration.between(allowed.updatedAt(), allowed.deadlineAt()));
    assertEquals(ToolPermissionDecision.ALLOW, repeated.permissionDecision());
    assertThrows(
        ToolDecisionConflictException.class,
        () -> decisionService.decide(workspaceId, pending.id(), ToolPermissionDecision.DENY));
    assertThrows(
        IllegalArgumentException.class,
        () -> decisionService.decide(otherWorkspaceId, pending.id(), ToolPermissionDecision.ALLOW));
    assertEquals(
        1,
        runStore.listAfter(allowClaimed.run().id(), 0, 30).stream()
            .filter(event -> event.type() == RunEventType.PERMISSION_RESOLVED)
            .count());

    Claimed denyClaimed = claimedRun(workspaceId, false);
    prepare(
        denyClaimed.run(),
        List.of(new ToolCall("deny-decision", "write", "{\"path\":\"d\"}")),
        List.of(binding("write")));
    ToolInvocation denied =
        decisionService.decide(
            workspaceId,
            invocationStore.listByRun(denyClaimed.run().id()).get(0).id(),
            ToolPermissionDecision.DENY);
    assertEquals(ToolInvocationStatus.FAILED, denied.status());
    assertTrue(denied.resultJson().contains("Permission denied by user for write."));
    assertFalse(denied.resultJson().contains("\"terminate\""));
    assertEquals(
        RunStatus.WAITING_TOOLS, runStore.find(denyClaimed.run().id()).orElseThrow().status());

    Claimed settledClaimed = claimedRun(workspaceId, false);
    prepare(
        settledClaimed.run(),
        List.of(new ToolCall("settled", "write", "{\"path\":\"s\"}")),
        List.of(binding("write")));
    ToolInvocation settled = invocationStore.listByRun(settledClaimed.run().id()).get(0);
    jdbcTemplate.update(
        "update harness_run set status = 'CANCELLED' where id = ?", settledClaimed.run().id());
    assertThrows(
        ToolDecisionConflictException.class,
        () -> decisionService.decide(workspaceId, settled.id(), ToolPermissionDecision.ALLOW));

    long automaticWorkspaceId = workspace("{\"permission\":{\"write\":\"allow\"}}");
    Claimed automatic = claimedRun(automaticWorkspaceId, false);
    prepare(
        automatic.run(),
        List.of(new ToolCall("automatic", "write", "{\"path\":\"a\"}")),
        List.of(binding("write")));
    ToolInvocation alreadyQueued = invocationStore.listByRun(automatic.run().id()).get(0);
    assertThrows(
        ToolDecisionConflictException.class,
        () ->
            decisionService.decide(
                automaticWorkspaceId, alreadyQueued.id(), ToolPermissionDecision.ALLOW));
  }

  /** 决策事务拒绝损坏的 ASK action 与非正 timeout budget，避免把坏快照继续推进。 */
  @Test
  void rejectsCorruptedApprovalState() {
    long workspaceId = workspace("{\"permission\":{\"write\":\"ask\"}}");

    ToolInvocation invalidPending = askInvocation(workspaceId, "invalid-pending");
    jdbcTemplate.update(
        "update tool_invocation set permission_action = 'ALLOW' where id = ?", invalidPending.id());
    assertThrows(
        IllegalStateException.class,
        () ->
            decisionService.decide(workspaceId, invalidPending.id(), ToolPermissionDecision.ALLOW));

    ToolInvocation invalidTimeout = askInvocation(workspaceId, "invalid-timeout");
    jdbcTemplate.update(
        "update tool_invocation set deadline_at = gmt_create where id = ?", invalidTimeout.id());
    assertThrows(
        IllegalStateException.class,
        () ->
            decisionService.decide(workspaceId, invalidTimeout.id(), ToolPermissionDecision.ALLOW));

    ToolInvocation decided = askInvocation(workspaceId, "decided-corruption");
    decisionService.decide(workspaceId, decided.id(), ToolPermissionDecision.ALLOW);
    jdbcTemplate.update(
        "update tool_invocation set permission_action = 'ALLOW' where id = ?", decided.id());
    assertThrows(
        IllegalStateException.class,
        () -> decisionService.decide(workspaceId, decided.id(), ToolPermissionDecision.ALLOW));
  }

  /** Policy resolver 明确拒绝缺失 Workspace 与缺失 Root，不能静默降级权限。 */
  @Test
  void rejectsInvalidPermissionPolicyReferences() {
    HarnessSessionDO missingWorkspace = new HarnessSessionDO();
    missingWorkspace.setId(1L);
    missingWorkspace.setWorkspaceId(Long.MAX_VALUE);
    missingWorkspace.setRootSessionId(1L);
    assertThrows(IllegalStateException.class, () -> policyResolver.resolve(missingWorkspace));

    long workspaceId = workspace("{}");
    HarnessSessionDO missingRoot = new HarnessSessionDO();
    missingRoot.setId(2L);
    missingRoot.setWorkspaceId(workspaceId);
    missingRoot.setParentSessionId(1L);
    missingRoot.setRootSessionId(Long.MAX_VALUE - 1);
    assertThrows(IllegalStateException.class, () -> policyResolver.resolve(missingRoot));
  }

  /** YOLO 服务拒绝损坏的 Root 拓扑与悬空 activeRunId，不发布错误运行事件。 */
  @Test
  void rejectsCorruptedYoloState() {
    long workspaceId = workspace("{}");
    Session invalidRoot = sessionTree.create(workspaceId, null, "invalid-root");
    jdbcTemplate.update(
        "update harness_session set parent_session_id = ? where id = ?",
        invalidRoot.id() + 1,
        invalidRoot.id());
    assertThrows(IllegalStateException.class, () -> yoloService.get(workspaceId, invalidRoot.id()));

    Session missingRun = sessionTree.create(workspaceId, null, "missing-run");
    jdbcTemplate.update(
        "update harness_session set active_run_id = ? where id = ?",
        Long.MAX_VALUE,
        missingRun.id());
    assertThrows(
        IllegalStateException.class, () -> yoloService.set(workspaceId, missingRun.id(), true));
  }

  /** Root 创建继承 defaultYolo；Child 动态读取 Root，且无 active Run 时 set/get 仍持久可观察。 */
  @Test
  void inheritsAndDynamicallyUpdatesRootYoloForChildren() {
    long workspaceId = workspace("{\"defaultYolo\":true,\"permission\":{\"write\":\"deny\"}}");
    long otherWorkspaceId = workspace("{}");
    Session root = sessionTree.create(workspaceId, null, "root");
    Session child = sessionTree.fork(root.id(), null);

    assertTrue(root.yoloEnabled());
    assertTrue(yoloService.get(workspaceId, child.id()).enabled());
    HarnessSessionYoloService.YoloState disabled = yoloService.set(workspaceId, child.id(), false);
    assertEquals(root.id(), disabled.rootSessionId());
    assertFalse(yoloService.get(workspaceId, root.id()).enabled());
    assertFalse(yoloService.get(workspaceId, child.id()).enabled());
    assertFalse(sessionStore.find(root.id()).orElseThrow().yoloEnabled());
    assertThrows(
        IllegalArgumentException.class, () -> yoloService.set(otherWorkspaceId, child.id(), true));

    AgentRun childQueued =
        transactions.submitUserMessage(child.id(), null, user(), NOW.plusSeconds(2));
    AgentRun childRun =
        runStore.claimDue("child-worker", NOW.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(childQueued.id(), childRun.id());
    yoloService.set(workspaceId, root.id(), true);
    prepare(
        childRun,
        List.of(new ToolCall("child-yolo", "write", "{\"path\":\"child.txt\"}")),
        List.of(binding("write")));
    assertEquals(
        ToolInvocationStatus.QUEUED, invocationStore.listByRun(childRun.id()).get(0).status());
    yoloService.set(workspaceId, child.id(), false);
    assertEquals(
        2,
        runStore.listAfter(childRun.id(), 0, 10).stream()
            .filter(event -> event.type() == RunEventType.RUNTIME_STATE_CHANGED)
            .count());

    Session activeRoot = sessionTree.create(workspaceId, null, "active-root");
    Session idleChild = sessionTree.fork(activeRoot.id(), null);
    AgentRun rootQueued =
        transactions.submitUserMessage(activeRoot.id(), null, user(), NOW.plusSeconds(3));
    AgentRun rootRun =
        runStore.claimDue("root-worker", NOW.plusSeconds(3), Duration.ofMinutes(1)).orElseThrow();
    assertEquals(rootQueued.id(), rootRun.id());
    yoloService.set(workspaceId, idleChild.id(), false);
    assertEquals(
        1,
        runStore.listAfter(rootRun.id(), 0, 10).stream()
            .filter(event -> event.type() == RunEventType.RUNTIME_STATE_CHANGED)
            .count());

    jdbcTemplate.update("update harness_run set status = 'CANCELLED' where id = ?", childRun.id());
    assertThrows(IllegalStateException.class, () -> yoloService.set(workspaceId, child.id(), true));
  }

  private ToolInvocation askInvocation(long workspaceId, String toolCallId) {
    Claimed claimed = claimedRun(workspaceId, false);
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

  private Claimed claimedRun(long workspaceId, boolean yoloEnabled) {
    long sessionId = sessionIds.newSessionId();
    Session root = Session.root(sessionId, workspaceId, null, "session", yoloEnabled, NOW);
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

  private long workspace(String settingsJson) {
    WorkspaceCreateDTO request = new WorkspaceCreateDTO();
    request.setName("tool-workspace-" + System.nanoTime());
    request.setSettingsJson(settingsJson);
    return Long.parseLong(workspaceService.createWorkspace(request).getId());
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
