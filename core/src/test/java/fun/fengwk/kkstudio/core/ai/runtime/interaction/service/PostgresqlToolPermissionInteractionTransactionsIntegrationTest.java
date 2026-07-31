package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;
import fun.fengwk.kkstudio.harness.runtime.interaction.ToolPermissionDecision;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** PostgreSQL contracts for Tool permission Interaction lookup and atomic response transactions. */
class PostgresqlToolPermissionInteractionTransactionsIntegrationTest
    extends PostgresSpringTestSupport {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant BASE = Instant.parse("2026-07-31T00:00:00Z");

  @Autowired private PostgresqlInteractionTransactions transactions;
  @Autowired private JdbcTemplate jdbc;

  private final AtomicLong ids = new AtomicLong(70_000_000L);

  @Test
  void findsOpenToolPermissionInteractionWithItsExplicitDurableFacts() {
    ThreadContext unrelated = newThread(false);
    WaitingInteraction fixture =
        newWaiting(ToolExecutionLocation.PLATFORM, null, BASE, unrelated.threadId());

    Interaction found = transactions.find(fixture.interactionId()).orElseThrow();

    assertEquals(fixture.interactionId(), found.id());
    assertEquals(fixture.toolId(), found.toolInvocationId());
    assertJsonEquals(fixture.requestJson(), found.request().json());
    assertEquals(InteractionStatus.OPEN, found.status());
    assertNull(found.response());
    assertEquals(0L, found.version());
    assertEquals(BASE, found.createdAt());
    assertNull(found.resolvedAt());
    assertEquals(found, transactions.findOpenByToolInvocation(fixture.toolId()).orElseThrow());
    assertTrue(transactions.find(fixture.interactionId() + 1_000L).isEmpty());
    assertTrue(transactions.findOpenByToolInvocation(fixture.toolId() + 1_000L).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> transactions.find(0L));
    assertThrows(IllegalArgumentException.class, () -> transactions.findOpenByToolInvocation(0L));
  }

  @Test
  void approvesPlatformToolFromDatabaseOwnerAndActivatesItsParkedTarget() {
    ThreadContext unrelated = newThread(false);
    WaitingInteraction fixture =
        newWaiting(ToolExecutionLocation.PLATFORM, null, BASE, unrelated.threadId());
    Instant resolvedAt = BASE.plusSeconds(1);

    InteractionTransition transition =
        transactions.resolve(
            fixture.interactionId(),
            0L,
            new InteractionResponse("{\"approved\":true}"),
            ToolPermissionDecision.APPROVE,
            resolvedAt);

    assertResolved(transition.interaction(), fixture, "{\"approved\":true}", resolvedAt);
    assertResolved(
        transactions.find(fixture.interactionId()).orElseThrow(),
        fixture,
        "{\"approved\":true}",
        resolvedAt);
    assertTrue(transactions.findOpenByToolInvocation(fixture.toolId()).isEmpty());
    assertEquals(new ToolState("QUEUED", "ALLOWED", null), toolState(fixture.toolId()));
    TargetState target = targetState(ExecutionTargetKind.TOOL_INVOCATION, fixture.toolId());
    assertNull(target.routeKey());
    assertTrue(target.dispatchEnabled());
    assertEquals(resolvedAt, target.availableAt());
    assertFalse(threadRunnable(fixture.ownerThreadId()));
    assertFalse(threadRunnable(unrelated.threadId()));
    assertFalse(targetExists(ExecutionTargetKind.THREAD, unrelated.threadId()));
  }

  @Test
  void approvalUsesEnvironmentFifoHeadInsteadOfBypassingOlderPermissionTool() {
    WaitingInteraction first =
        newWaiting(ToolExecutionLocation.ENVIRONMENT, "env-a", BASE, newThread(false).threadId());
    QueuedTool second = newQueuedEnvironment("env-a", BASE.plusSeconds(1));

    transactions.resolve(
        first.interactionId(),
        0L,
        new InteractionResponse("{\"approved\":true}"),
        ToolPermissionDecision.APPROVE,
        BASE.plusSeconds(2));

    assertEquals(new ToolState("QUEUED", "ALLOWED", null), toolState(first.toolId()));
    assertTrue(targetState(ExecutionTargetKind.TOOL_INVOCATION, first.toolId()).dispatchEnabled());
    assertFalse(
        targetState(ExecutionTargetKind.TOOL_INVOCATION, second.toolId()).dispatchEnabled(),
        "the later environment member must remain behind the durable FIFO head");
  }

  @Test
  void denialUsesDatabaseOwnerThenSchedulesOwningThreadAndNextEnvironmentHead() {
    ThreadContext unrelated = newThread(false);
    WaitingInteraction first =
        newWaiting(ToolExecutionLocation.ENVIRONMENT, "env-a", BASE, unrelated.threadId());
    QueuedTool second = newQueuedEnvironment("env-a", BASE.plusSeconds(1));
    Instant resolvedAt = BASE.plusSeconds(2);

    InteractionTransition transition =
        transactions.resolve(
            first.interactionId(),
            0L,
            new InteractionResponse("{\"approved\":false}"),
            ToolPermissionDecision.DENY,
            resolvedAt);

    assertResolved(transition.interaction(), first, "{\"approved\":false}", resolvedAt);
    ToolState denied = toolState(first.toolId());
    assertEquals("FAILED", denied.status());
    assertEquals("DENIED", denied.permissionState());
    assertEquals("PERMISSION_DENIED", errorKind(denied.errorJson()));
    assertFalse(targetExists(ExecutionTargetKind.TOOL_INVOCATION, first.toolId()));
    assertTrue(threadRunnable(first.ownerThreadId()));
    assertTrue(targetState(ExecutionTargetKind.THREAD, first.ownerThreadId()).dispatchEnabled());
    assertFalse(threadRunnable(unrelated.threadId()));
    assertFalse(targetExists(ExecutionTargetKind.THREAD, unrelated.threadId()));
    assertTrue(
        targetState(ExecutionTargetKind.TOOL_INVOCATION, second.toolId()).dispatchEnabled(),
        "removing the denied FIFO head must activate the next queued environment Tool");
  }

  @Test
  void rejectsStaleOrInvalidOwnerTransitionsWithoutPartialWrites() {
    WaitingInteraction stale =
        newWaiting(ToolExecutionLocation.PLATFORM, null, BASE, newThread(false).threadId());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                stale.interactionId(),
                1L,
                new InteractionResponse("{\"approved\":true}"),
                ToolPermissionDecision.APPROVE,
                BASE.plusSeconds(1)));
    assertOpenWaitingAndParked(stale);

    WaitingInteraction resolved =
        newWaiting(
            ToolExecutionLocation.PLATFORM,
            null,
            BASE.plusSeconds(10),
            newThread(false).threadId());
    transactions.resolve(
        resolved.interactionId(),
        0L,
        new InteractionResponse("{\"approved\":true}"),
        ToolPermissionDecision.APPROVE,
        BASE.plusSeconds(11));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                resolved.interactionId(),
                1L,
                new InteractionResponse("{\"approved\":false}"),
                ToolPermissionDecision.DENY,
                BASE.plusSeconds(12)));
    Interaction resolvedAfterStaleRetry = transactions.find(resolved.interactionId()).orElseThrow();
    assertEquals(InteractionStatus.RESOLVED, resolvedAfterStaleRetry.status());
    assertJsonEquals("{\"approved\":true}", resolvedAfterStaleRetry.response().json());
    assertEquals(1L, resolvedAfterStaleRetry.version());
    assertEquals(BASE.plusSeconds(10), resolvedAfterStaleRetry.createdAt());
    assertEquals(BASE.plusSeconds(11), resolvedAfterStaleRetry.resolvedAt());
    assertEquals(new ToolState("QUEUED", "ALLOWED", null), toolState(resolved.toolId()));
    assertTrue(
        targetState(ExecutionTargetKind.TOOL_INVOCATION, resolved.toolId()).dispatchEnabled());

    WaitingInteraction invalidOwner =
        newWaiting(
            ToolExecutionLocation.PLATFORM,
            null,
            BASE.plusSeconds(20),
            newThread(false).threadId());
    jdbc.update(
        "update harness_tool_invocation set status = 'QUEUED', permission_state = 'ALLOWED' where id = ?",
        invalidOwner.toolId());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                invalidOwner.interactionId(),
                0L,
                new InteractionResponse("{\"approved\":true}"),
                ToolPermissionDecision.APPROVE,
                BASE.plusSeconds(21)));
    assertEquals(
        InteractionStatus.OPEN,
        transactions.find(invalidOwner.interactionId()).orElseThrow().status());
    assertEquals(new ToolState("QUEUED", "ALLOWED", null), toolState(invalidOwner.toolId()));
    assertFalse(
        targetState(ExecutionTargetKind.TOOL_INVOCATION, invalidOwner.toolId()).dispatchEnabled());

    WaitingInteraction missingTarget =
        newWaiting(
            ToolExecutionLocation.PLATFORM,
            null,
            BASE.plusSeconds(30),
            newThread(false).threadId());
    jdbc.update(
        "delete from harness_execution_target where target_kind = 'TOOL_INVOCATION' and target_id = ?",
        missingTarget.toolId());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                missingTarget.interactionId(),
                0L,
                new InteractionResponse("{\"approved\":true}"),
                ToolPermissionDecision.APPROVE,
                BASE.plusSeconds(31)));
    assertEquals(
        InteractionStatus.OPEN,
        transactions.find(missingTarget.interactionId()).orElseThrow().status());
    assertEquals(
        new ToolState("WAITING_INTERACTION", "ASKED", null), toolState(missingTarget.toolId()));
    assertFalse(threadRunnable(missingTarget.ownerThreadId()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.resolve(
                ids.incrementAndGet(),
                0L,
                new InteractionResponse("{\"approved\":true}"),
                ToolPermissionDecision.APPROVE,
                BASE.plusSeconds(40)));
  }

  @Test
  void databaseAllowsOnlyOneOpenInteractionPerToolAndAllowsAnotherAfterResolution() {
    WaitingInteraction fixture =
        newWaiting(ToolExecutionLocation.PLATFORM, null, BASE, newThread(false).threadId());

    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertOpenInteraction(fixture.toolId(), fixture.requestJson(), BASE.plusSeconds(1)));

    transactions.resolve(
        fixture.interactionId(),
        0L,
        new InteractionResponse("{\"approved\":true}"),
        ToolPermissionDecision.APPROVE,
        BASE.plusSeconds(2));
    long nextInteractionId =
        insertOpenInteraction(fixture.toolId(), fixture.requestJson(), BASE.plusSeconds(3));

    assertEquals(
        nextInteractionId,
        transactions.findOpenByToolInvocation(fixture.toolId()).orElseThrow().id());
  }

  @Test
  void rejectsDispatchableOrMisroutedPermissionTargetsWithoutResolvingInteraction() {
    WaitingInteraction dispatchable =
        newWaiting(ToolExecutionLocation.PLATFORM, null, BASE, newThread(false).threadId());
    jdbc.update(
        "update harness_execution_target set dispatch_enabled = true"
            + " where target_kind = 'TOOL_INVOCATION' and target_id = ?",
        dispatchable.toolId());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                dispatchable.interactionId(),
                0L,
                new InteractionResponse("{\"approved\":true}"),
                ToolPermissionDecision.APPROVE,
                BASE.plusSeconds(1)));
    assertEquals(
        InteractionStatus.OPEN,
        transactions.find(dispatchable.interactionId()).orElseThrow().status());

    WaitingInteraction misrouted =
        newWaiting(
            ToolExecutionLocation.ENVIRONMENT,
            "env-a",
            BASE.plusSeconds(10),
            newThread(false).threadId());
    jdbc.update(
        "update harness_execution_target set route_key = 'wrong-env'"
            + " where target_kind = 'TOOL_INVOCATION' and target_id = ?",
        misrouted.toolId());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                misrouted.interactionId(),
                0L,
                new InteractionResponse("{\"approved\":true}"),
                ToolPermissionDecision.APPROVE,
                BASE.plusSeconds(11)));
    assertOpenWaitingAndParked(misrouted);
  }

  private WaitingInteraction newWaiting(
      ToolExecutionLocation location,
      String environmentName,
      Instant createdAt,
      long requestThreadId) {
    ThreadContext owner = newThread(false);
    long toolId = ids.incrementAndGet();
    insertTool(toolId, owner, location, environmentName, "WAITING_INTERACTION", "ASKED", createdAt);
    insertTarget(ExecutionTargetKind.TOOL_INVOCATION, toolId, environmentName, false, createdAt);
    String requestJson =
        """
        {"invocationId":%d,"threadId":%d,"tool":"fixture-tool","workdir":"/workspace","arguments":"{}"}
        """
            .formatted(toolId, requestThreadId);
    long interactionId = insertOpenInteraction(toolId, requestJson, createdAt);
    return new WaitingInteraction(interactionId, toolId, owner.threadId(), requestJson);
  }

  private QueuedTool newQueuedEnvironment(String environmentName, Instant createdAt) {
    ThreadContext owner = newThread(false);
    long toolId = ids.incrementAndGet();
    insertTool(
        toolId,
        owner,
        ToolExecutionLocation.ENVIRONMENT,
        environmentName,
        "QUEUED",
        "PENDING",
        createdAt);
    insertTarget(ExecutionTargetKind.TOOL_INVOCATION, toolId, environmentName, false, createdAt);
    return new QueuedTool(toolId);
  }

  private ThreadContext newThread(boolean runnable) {
    long sessionId = ids.incrementAndGet();
    long entryId = ids.incrementAndGet();
    long threadId = ids.incrementAndGet();
    jdbc.update(
        "insert into harness_session (id, title, created_at) values (?, 'fixture', ?)",
        sessionId,
        timestamp(BASE));
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{}'::jsonb, ?)",
        entryId,
        sessionId,
        timestamp(BASE));
    jdbc.update(
        "insert into harness_thread (id, head_entry_id, input_sequence, runnable, execution_epoch,"
            + " created_at, updated_at) values (?, ?, 0, ?, 0, ?, ?)",
        threadId,
        entryId,
        runnable,
        timestamp(BASE),
        timestamp(BASE));
    return new ThreadContext(sessionId, entryId, threadId);
  }

  private void insertTool(
      long toolId,
      ThreadContext owner,
      ToolExecutionLocation location,
      String environmentName,
      String status,
      String permissionState,
      Instant createdAt) {
    jdbc.update(
        """
        insert into harness_tool_invocation (
            id, thread_id, session_id, assistant_entry_id, ordinal, tool_call_id, descriptor,
            arguments, location, environment_name, execution_epoch, status, attempt,
            permission_state, yolo_enabled, created_at
        ) values (?, ?, ?, ?, 0, ?, '{}'::jsonb, '{}'::jsonb, ?, ?, 0, ?, 1, ?, false, ?)
        """,
        toolId,
        owner.threadId(),
        owner.sessionId(),
        owner.entryId(),
        "call-" + toolId,
        location.name(),
        environmentName,
        status,
        permissionState,
        timestamp(createdAt));
  }

  private void insertTarget(
      ExecutionTargetKind kind,
      long targetId,
      String routeKey,
      boolean dispatchEnabled,
      Instant availableAt) {
    jdbc.update(
        "insert into harness_execution_target (target_kind, target_id, route_key, dispatch_enabled,"
            + " available_at) values (?, ?, ?, ?, ?)",
        kind.name(),
        targetId,
        routeKey,
        dispatchEnabled,
        timestamp(availableAt));
  }

  private long insertOpenInteraction(long toolId, String requestJson, Instant createdAt) {
    long interactionId = ids.incrementAndGet();
    jdbc.update(
        "insert into harness_interaction (id, tool_invocation_id, request, status, version, created_at)"
            + " values (?, ?, cast(? as jsonb), 'OPEN', 0, ?)",
        interactionId,
        toolId,
        requestJson,
        timestamp(createdAt));
    return interactionId;
  }

  private ToolState toolState(long toolId) {
    return jdbc.queryForObject(
        "select status, permission_state, error::text from harness_tool_invocation where id = ?",
        (resultSet, rowNum) ->
            new ToolState(
                resultSet.getString("status"),
                resultSet.getString("permission_state"),
                resultSet.getString("error")),
        toolId);
  }

  private TargetState targetState(ExecutionTargetKind kind, long targetId) {
    return jdbc.queryForObject(
        "select route_key, dispatch_enabled, available_at from harness_execution_target"
            + " where target_kind = ? and target_id = ?",
        (resultSet, rowNum) ->
            new TargetState(
                resultSet.getString("route_key"),
                resultSet.getBoolean("dispatch_enabled"),
                resultSet.getObject("available_at", OffsetDateTime.class).toInstant()),
        kind.name(),
        targetId);
  }

  private boolean targetExists(ExecutionTargetKind kind, long targetId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from harness_execution_target where target_kind = ? and target_id = ?",
            Integer.class,
            kind.name(),
            targetId);
    return count != null && count == 1;
  }

  private boolean threadRunnable(long threadId) {
    Boolean runnable =
        jdbc.queryForObject(
            "select runnable from harness_thread where id = ?", Boolean.class, threadId);
    return Boolean.TRUE.equals(runnable);
  }

  private void assertOpenWaitingAndParked(WaitingInteraction fixture) {
    assertEquals(
        InteractionStatus.OPEN, transactions.find(fixture.interactionId()).orElseThrow().status());
    assertEquals(new ToolState("WAITING_INTERACTION", "ASKED", null), toolState(fixture.toolId()));
    assertFalse(
        targetState(ExecutionTargetKind.TOOL_INVOCATION, fixture.toolId()).dispatchEnabled());
    assertFalse(threadRunnable(fixture.ownerThreadId()));
  }

  private static void assertResolved(
      Interaction interaction,
      WaitingInteraction fixture,
      String responseJson,
      Instant resolvedAt) {
    assertEquals(fixture.interactionId(), interaction.id());
    assertEquals(fixture.toolId(), interaction.toolInvocationId());
    assertEquals(InteractionStatus.RESOLVED, interaction.status());
    assertJsonEquals(responseJson, interaction.response().json());
    assertEquals(1L, interaction.version());
    assertEquals(BASE, interaction.createdAt());
    assertEquals(resolvedAt, interaction.resolvedAt());
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static void assertJsonEquals(String expected, String actual) {
    try {
      JsonNode expectedNode = JSON.readTree(expected);
      JsonNode actualNode = JSON.readTree(actual);
      assertEquals(expectedNode, actualNode);
    } catch (Exception error) {
      throw new AssertionError("JSON comparison failed", error);
    }
  }

  private static String errorKind(String errorJson) {
    try {
      return JSON.readTree(errorJson).path("kind").asText();
    } catch (Exception error) {
      throw new AssertionError("Tool error must be JSON", error);
    }
  }

  private record ThreadContext(long sessionId, long entryId, long threadId) {}

  private record WaitingInteraction(
      long interactionId, long toolId, long ownerThreadId, String requestJson) {}

  private record QueuedTool(long toolId) {}

  private record ToolState(String status, String permissionState, String errorJson) {}

  private record TargetState(String routeKey, boolean dispatchEnabled, Instant availableAt) {}
}
