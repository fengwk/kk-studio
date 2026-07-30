package fun.fengwk.kkstudio.core.harness.interaction.service;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCreate;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandlerRegistry;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerAction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerDirective;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionRequest;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResolution;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionInteraction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Real PostgreSQL 17 coverage for generic Interaction fact creation and terminal CAS transitions.
 */
class PostgresqlInteractionTransactionsIntegrationTest extends PostgresSpringTestSupport {
  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final ExecutionTarget OWNER =
      new ExecutionTarget(ExecutionTargetKind.THREAD, 700L);
  private static final ExecutionTarget NEXT = OWNER;
  private static final ExecutionTarget TOOL_OWNER =
      new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 800L);

  @Autowired private PostgresqlInteractionTransactions transactions;
  @Autowired private InteractionHandlerRegistry handlerRegistry;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @BeforeEach
  void seedThreadOwner() throws SQLException {
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("set constraints all deferred");
      statement.executeUpdate(
          "insert into harness_session (id, title, created_at)"
              + " values (600, 'test', '2026-01-01T00:00:00Z')");
      statement.executeUpdate(
          "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
              + " values (601, 600, null, 'ROOT', '{}'::jsonb, '2026-01-01T00:00:00Z')");
      statement.executeUpdate(
          "insert into harness_thread (id, head_entry_id, input_sequence, runnable, execution_epoch,"
              + " created_at, updated_at) values (700, 601, 0, true, 0,"
              + " '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
      connection.commit();
    }
  }

  /**
   * OPEN creation uses the existing sequence/table and query by owner returns the single durable
   * fact.
   */
  @Test
  void createsAndFindsOpenInteractionByOwner() {
    Interaction created = create(null);

    assertTrue(created.id() > 0L);
    assertEquals(InteractionStatus.OPEN, created.status());
    assertFalse(threadRunnable(OWNER.id()));
    assertEquals(" {\"question\":true} ", created.request().json());
    Interaction loaded = transactions.find(created.id()).orElseThrow();
    assertEquals(created.id(), loaded.id());
    assertEquals(created.owner(), loaded.owner());
    assertEquals("{\"question\": true}", loaded.request().json());
    assertEquals(loaded, transactions.findOpenByOwner(OWNER).orElseThrow());
  }

  /** Resolve atomically persists response/version and exposes the handler-selected next target. */
  @Test
  void resolvesExactlyOnceWithExpectedVersion() {
    Interaction created = create(null);
    Instant resolvedAt = CREATED_AT.plusSeconds(1);

    InteractionTransition transition =
        transactions.resolve(
            created.id(),
            0L,
            new InteractionResponse("{\"confirmed\":true}"),
            new InteractionResolution(resumeThread(), NEXT),
            resolvedAt);

    assertEquals(NEXT, transition.nextTarget());
    assertEquals(InteractionStatus.RESOLVED, transition.interaction().status());
    assertEquals(1L, transition.interaction().version());
    assertTrue(threadRunnable(OWNER.id()));
    assertEquals("{\"confirmed\":true}", transition.interaction().response().json());
    assertFalse(transactions.findOpenByOwner(OWNER).isPresent());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                created.id(),
                0L,
                new InteractionResponse("false"),
                new InteractionResolution(resumeThread(), NEXT),
                resolvedAt.plusSeconds(1)));
    assertEquals(
        InteractionStatus.RESOLVED, transactions.find(created.id()).orElseThrow().status());
  }

  /**
   * Response received at expiry is deterministically terminalized as EXPIRED without persistence of
   * it.
   */
  @Test
  void resolvesAtExpiryAsExpiredAndDiscardsResponse() {
    Instant expiresAt = CREATED_AT.plusSeconds(10);
    Interaction created = create(expiresAt);

    InteractionTransition transition =
        transactions.resolve(
            created.id(),
            0L,
            new InteractionResponse("{\"late\":true}"),
            new InteractionResolution(resumeThread(), NEXT),
            expiresAt);

    assertEquals(OWNER, transition.nextTarget());
    assertEquals(InteractionStatus.EXPIRED, transition.interaction().status());
    assertNull(transition.interaction().response());
    assertEquals(1L, transition.interaction().version());
  }

  /**
   * Cancel and explicit expiration reject stale versions and premature expiry without changing OPEN
   * state.
   */
  @Test
  void validatesVersionAndExpiryBeforeTerminalizing() {
    Interaction created = create(CREATED_AT.plusSeconds(10));

    assertThrows(
        IllegalStateException.class,
        () -> transactions.expire(created.id(), 0L, CREATED_AT.plusSeconds(9)));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.cancel(
                created.id(), 1L, resumeThread(), OWNER, CREATED_AT.plusSeconds(1)));
    assertEquals(InteractionStatus.OPEN, transactions.find(created.id()).orElseThrow().status());

    InteractionTransition cancelled =
        transactions.cancel(created.id(), 0L, resumeThread(), OWNER, CREATED_AT.plusSeconds(1));
    assertEquals(InteractionStatus.CANCELLED, cancelled.interaction().status());
    assertEquals(OWNER, cancelled.nextTarget());
  }

  /**
   * Approval turns WAITING_INTERACTION/ASKED into QUEUED/ALLOWED and enables only the Tool target;
   * its owning Thread remains suspended until a Tool terminal result is applied.
   */
  @Test
  void approvesParkedPlatformToolWithoutActivatingOwningThread() throws SQLException {
    seedWaitingPermissionTool(TOOL_OWNER.id(), "PLATFORM", null, 0);
    Interaction created = createToolPermissionInteraction(null);

    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            transactions, handlerRegistry, Clock.fixed(CREATED_AT.plusSeconds(1), ZoneOffset.UTC));
    InteractionCoordinator.InteractionRespondResult transition =
        coordinator.respond(created.id(), 0L, new InteractionResponse("{\"approved\":true}"));

    assertEquals(TOOL_OWNER, transition.nextTarget());
    assertEquals(InteractionStatus.RESOLVED, transition.interaction().status());
    assertFalse(threadRunnable(OWNER.id()));
    ToolState tool = toolState(TOOL_OWNER.id());
    assertEquals("QUEUED", tool.status());
    assertEquals("ALLOWED", tool.permissionState());
    assertNull(tool.errorJson());
    assertFalse(tool.finished());
    assertTrue(toolTargetState(TOOL_OWNER.id()).dispatchEnabled());
    assertNull(toolTargetState(TOOL_OWNER.id()).routeKey());
    assertTrue(transactions.findOpenByOwner(TOOL_OWNER).isEmpty());
  }

  /**
   * Approval re-enters an environment route only through its existing FIFO head activation gate.
   */
  @Test
  void approvalActivatesWaitingEnvironmentHeadWithoutBypassingItsSibling() throws SQLException {
    seedWaitingPermissionTool(TOOL_OWNER.id(), "ENVIRONMENT", "env-a", 0);
    seedQueuedEnvironmentTool(801L, "env-a", 1);
    Interaction created = createToolPermissionInteraction(null);

    transactions.resolve(
        created.id(),
        0L,
        new InteractionResponse("{\"approved\":true}"),
        new InteractionResolution(approveToolPermission(), TOOL_OWNER),
        CREATED_AT.plusSeconds(1));

    assertTrue(toolTargetState(TOOL_OWNER.id()).dispatchEnabled());
    assertFalse(toolTargetState(801L).dispatchEnabled());
  }

  /**
   * Rejection terminalizes the Tool, deletes its target, and makes its Thread durable target due.
   */
  @Test
  void rejectsWaitingToolAndActivatesOwningThread() throws SQLException {
    seedWaitingPermissionTool(TOOL_OWNER.id(), "PLATFORM", null, 0);
    Interaction created = createToolPermissionInteraction(null);

    InteractionTransition transition =
        transactions.resolve(
            created.id(),
            0L,
            new InteractionResponse("{\"approved\":false}"),
            new InteractionResolution(denyToolPermission(), OWNER),
            CREATED_AT.plusSeconds(1));

    assertEquals(OWNER, transition.nextTarget());
    assertEquals(InteractionStatus.RESOLVED, transition.interaction().status());
    assertTrue(threadRunnable(OWNER.id()));
    ToolState tool = toolState(TOOL_OWNER.id());
    assertEquals("FAILED", tool.status());
    assertEquals("DENIED", tool.permissionState());
    assertJsonEquals(
        PostgresqlInteractionTransactions.TOOL_PERMISSION_DENIED_ERROR_JSON, tool.errorJson());
    assertTrue(tool.finished());
    assertNull(tool.resultJson());
    assertFalse(targetExists(ExecutionTargetKind.TOOL_INVOCATION, TOOL_OWNER.id()));
    assertTrue(targetExists(ExecutionTargetKind.THREAD, OWNER.id()));
  }

  /**
   * Rejection removes the waiting route head, then re-runs the shared FIFO activation query for the
   * next environment member.
   */
  @Test
  void rejectionActivatesNextEnvironmentRouteHead() throws SQLException {
    seedWaitingPermissionTool(TOOL_OWNER.id(), "ENVIRONMENT", "env-a", 0);
    seedQueuedEnvironmentTool(801L, "env-a", 1);
    Interaction created = createToolPermissionInteraction(null);

    transactions.resolve(
        created.id(),
        0L,
        new InteractionResponse("{\"approved\":false}"),
        new InteractionResolution(denyToolPermission(), OWNER),
        CREATED_AT.plusSeconds(1));

    assertFalse(targetExists(ExecutionTargetKind.TOOL_INVOCATION, TOOL_OWNER.id()));
    assertTrue(toolTargetState(801L).dispatchEnabled());
  }

  /**
   * Expiration follows the same fail-closed Tool permission terminal path without persisting input.
   */
  @Test
  void expiresWaitingToolAsDeniedAndActivatesOwningThread() throws SQLException {
    Instant expiresAt = CREATED_AT.plusSeconds(10);
    seedWaitingPermissionTool(TOOL_OWNER.id(), "PLATFORM", null, 0);
    Interaction created = createToolPermissionInteraction(expiresAt);

    InteractionTransition transition = transactions.expire(created.id(), 0L, expiresAt);

    assertEquals(OWNER, transition.nextTarget());
    assertEquals(InteractionStatus.EXPIRED, transition.interaction().status());
    assertNull(transition.interaction().response());
    assertTrue(threadRunnable(OWNER.id()));
    ToolState tool = toolState(TOOL_OWNER.id());
    assertEquals("FAILED", tool.status());
    assertEquals("DENIED", tool.permissionState());
    assertJsonEquals(
        PostgresqlInteractionTransactions.TOOL_PERMISSION_DENIED_ERROR_JSON, tool.errorJson());
    assertFalse(targetExists(ExecutionTargetKind.TOOL_INVOCATION, TOOL_OWNER.id()));
  }

  /**
   * Expiration uses the same denial transition and cannot leave the following environment route
   * head parked behind an interaction that is no longer open.
   */
  @Test
  void expirationActivatesNextEnvironmentRouteHead() throws SQLException {
    Instant expiresAt = CREATED_AT.plusSeconds(10);
    seedWaitingPermissionTool(TOOL_OWNER.id(), "ENVIRONMENT", "env-a", 0);
    seedQueuedEnvironmentTool(801L, "env-a", 1);
    Interaction created = createToolPermissionInteraction(expiresAt);

    transactions.expire(created.id(), 0L, expiresAt);

    assertFalse(targetExists(ExecutionTargetKind.TOOL_INVOCATION, TOOL_OWNER.id()));
    assertTrue(toolTargetState(801L).dispatchEnabled());
  }

  /** A stale terminal race rolls back the owner mutation and retains the first durable outcome. */
  @Test
  void rollsBackToolPermissionMutationWhenTerminalVersionIsStale() throws SQLException {
    seedWaitingPermissionTool(TOOL_OWNER.id(), "PLATFORM", null, 0);
    Interaction created = createToolPermissionInteraction(null);
    transactions.resolve(
        created.id(),
        0L,
        new InteractionResponse("{\"approved\":true}"),
        new InteractionResolution(approveToolPermission(), TOOL_OWNER),
        CREATED_AT.plusSeconds(1));

    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                created.id(),
                0L,
                new InteractionResponse("{\"approved\":false}"),
                new InteractionResolution(denyToolPermission(), OWNER),
                CREATED_AT.plusSeconds(2)));
    assertFalse(threadRunnable(OWNER.id()));
    assertEquals("QUEUED", toolState(TOOL_OWNER.id()).status());
    assertEquals("ALLOWED", toolState(TOOL_OWNER.id()).permissionState());
    assertTrue(toolTargetState(TOOL_OWNER.id()).dispatchEnabled());
    assertEquals(
        InteractionStatus.RESOLVED, transactions.find(created.id()).orElseThrow().status());
  }

  /** A missing parked target is an invariant breach and rolls back the interaction response. */
  @Test
  void missingToolTargetRollsBackPermissionApproval() throws SQLException {
    seedWaitingPermissionTool(TOOL_OWNER.id(), "PLATFORM", null, 0);
    Interaction created = createToolPermissionInteraction(null);
    deleteTarget(ExecutionTargetKind.TOOL_INVOCATION, TOOL_OWNER.id());

    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                created.id(),
                0L,
                new InteractionResponse("{\"approved\":true}"),
                new InteractionResolution(approveToolPermission(), TOOL_OWNER),
                CREATED_AT.plusSeconds(1)));

    assertEquals(InteractionStatus.OPEN, transactions.find(created.id()).orElseThrow().status());
    assertEquals("WAITING_INTERACTION", toolState(TOOL_OWNER.id()).status());
    assertEquals("ASKED", toolState(TOOL_OWNER.id()).permissionState());
    assertFalse(threadRunnable(OWNER.id()));
  }

  /** A unique OPEN conflict rolls back the suspension made before the interaction insert. */
  @Test
  void rollsBackThreadSuspensionWhenOpenInteractionAlreadyExists() throws SQLException {
    insertOpenInteraction(900L, OWNER);

    assertThrows(RuntimeException.class, () -> create(null));
    assertTrue(threadRunnable(OWNER.id()));
    assertEquals(InteractionStatus.OPEN, transactions.find(900L).orElseThrow().status());
  }

  private Interaction create(Instant expiresAt) {
    return transactions.create(
        new InteractionCreate(
            OWNER,
            "confirm",
            new InteractionRequest(" {\"question\":true} "),
            new InteractionOwnerDirective(InteractionOwnerAction.SUSPEND_THREAD),
            expiresAt,
            CREATED_AT));
  }

  private InteractionOwnerDirective resumeThread() {
    return new InteractionOwnerDirective(InteractionOwnerAction.RESUME_THREAD);
  }

  private InteractionOwnerDirective approveToolPermission() {
    return new InteractionOwnerDirective(InteractionOwnerAction.APPROVE_TOOL_PERMISSION);
  }

  private InteractionOwnerDirective denyToolPermission() {
    return new InteractionOwnerDirective(InteractionOwnerAction.DENY_TOOL_PERMISSION);
  }

  private Interaction createToolPermissionInteraction(Instant expiresAt) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                insert into harness_interaction (
                    id, owner_kind, owner_id, handler_type, request, status, expires_at, version, created_at
                ) values (
                    ?, 'TOOL_INVOCATION', ?, ?, cast(? as jsonb), 'OPEN', ?, 0, ?
                )
                """)) {
      statement.setLong(1, 900L);
      statement.setLong(2, TOOL_OWNER.id());
      statement.setString(3, ToolPermissionInteraction.HANDLER_TYPE);
      statement.setString(
          4,
          """
          {"invocationId":800,"threadId":700,"tool":"platform-tool","workdir":"/work","arguments":"{}"}
          """);
      if (expiresAt == null) {
        statement.setObject(5, null);
      } else {
        statement.setTimestamp(5, Timestamp.from(expiresAt));
      }
      statement.setTimestamp(6, Timestamp.from(CREATED_AT));
      assertEquals(1, statement.executeUpdate());
    }
    return transactions.find(900L).orElseThrow();
  }

  private void seedWaitingPermissionTool(
      long id, String location, String environmentName, int ordinal) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement tool =
            connection.prepareStatement(
                """
                insert into harness_tool_invocation (
                    id, thread_id, session_id, assistant_entry_id, ordinal, tool_call_id, descriptor,
                    arguments, location, environment_name, execution_epoch, status, attempt,
                    permission_state, yolo_enabled, created_at
                ) values (
                    ?, 700, 600, 601, ?, ?, '{}'::jsonb, '{}'::jsonb, ?, ?, 0,
                    'WAITING_INTERACTION', 1, 'ASKED', false, ?
                )
                """);
        PreparedStatement target =
            connection.prepareStatement(
                """
                insert into harness_execution_target (
                    target_kind, target_id, route_key, dispatch_enabled, available_at
                ) values ('TOOL_INVOCATION', ?, ?, false, ?)
                """);
        PreparedStatement suspendThread =
            connection.prepareStatement(
                "update harness_thread set runnable = false, updated_at = ? where id = 700")) {
      tool.setLong(1, id);
      tool.setInt(2, ordinal);
      tool.setString(3, "call-" + id);
      tool.setString(4, location);
      tool.setString(5, environmentName);
      tool.setTimestamp(6, Timestamp.from(CREATED_AT));
      assertEquals(1, tool.executeUpdate());
      target.setLong(1, id);
      target.setString(2, environmentName);
      target.setTimestamp(3, Timestamp.from(CREATED_AT));
      assertEquals(1, target.executeUpdate());
      suspendThread.setTimestamp(1, Timestamp.from(CREATED_AT));
      assertEquals(1, suspendThread.executeUpdate());
    }
  }

  private void seedQueuedEnvironmentTool(long id, String environmentName, int ordinal)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement tool =
            connection.prepareStatement(
                """
                insert into harness_tool_invocation (
                    id, thread_id, session_id, assistant_entry_id, ordinal, tool_call_id, descriptor,
                    arguments, location, environment_name, execution_epoch, status, attempt,
                    permission_state, yolo_enabled, created_at
                ) values (
                    ?, 700, 600, 601, ?, ?, '{}'::jsonb, '{}'::jsonb, 'ENVIRONMENT', ?, 0,
                    'QUEUED', 1, 'PENDING', false, ?
                )
                """);
        PreparedStatement target =
            connection.prepareStatement(
                """
                insert into harness_execution_target (
                    target_kind, target_id, route_key, dispatch_enabled, available_at
                ) values ('TOOL_INVOCATION', ?, ?, false, ?)
                """)) {
      tool.setLong(1, id);
      tool.setInt(2, ordinal);
      tool.setString(3, "call-" + id);
      tool.setString(4, environmentName);
      tool.setTimestamp(5, Timestamp.from(CREATED_AT));
      assertEquals(1, tool.executeUpdate());
      target.setLong(1, id);
      target.setString(2, environmentName);
      target.setTimestamp(3, Timestamp.from(CREATED_AT));
      assertEquals(1, target.executeUpdate());
    }
  }

  private void insertOpenInteraction(long interactionId, ExecutionTarget owner)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_interaction (id, owner_kind, owner_id, handler_type, request, status,"
                    + " version, created_at) values (?, ?, ?, 'existing', '{}'::jsonb, 'OPEN', 0, ?)")) {
      statement.setLong(1, interactionId);
      statement.setString(2, owner.kind().name());
      statement.setLong(3, owner.id());
      statement.setTimestamp(4, Timestamp.from(CREATED_AT));
      statement.executeUpdate();
    }
  }

  private ToolState toolState(long toolId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, permission_state, error::text, result::text, finished_at is not null"
                    + " from harness_tool_invocation where id = ?")) {
      statement.setLong(1, toolId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return new ToolState(
            result.getString(1),
            result.getString(2),
            result.getString(3),
            result.getString(4),
            result.getBoolean(5));
      }
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private TargetState toolTargetState(long toolId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select route_key, dispatch_enabled
                from harness_execution_target
                where target_kind = 'TOOL_INVOCATION' and target_id = ?
                """)) {
      statement.setLong(1, toolId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return new TargetState(result.getString(1), result.getBoolean(2));
      }
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private boolean targetExists(ExecutionTargetKind kind, long id) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select 1 from harness_execution_target where target_kind = ? and target_id = ?")) {
      statement.setString(1, kind.name());
      statement.setLong(2, id);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private void deleteTarget(ExecutionTargetKind kind, long id) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "delete from harness_execution_target where target_kind = ? and target_id = ?")) {
      statement.setString(1, kind.name());
      statement.setLong(2, id);
      assertEquals(1, statement.executeUpdate());
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private boolean threadRunnable(long threadId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement("select runnable from harness_thread where id = ?")) {
      statement.setLong(1, threadId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getBoolean(1);
      }
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private static void assertJsonEquals(String expected, String actual) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode expectedNode = mapper.readTree(expected);
      JsonNode actualNode = mapper.readTree(actual);
      assertEquals(expectedNode, actualNode);
    } catch (Exception error) {
      throw new AssertionError(
          "json compare failed: expected=" + expected + " actual=" + actual, error);
    }
  }

  private record ToolState(
      String status,
      String permissionState,
      String errorJson,
      String resultJson,
      boolean finished) {}

  private record TargetState(String routeKey, boolean dispatchEnabled) {}
}
