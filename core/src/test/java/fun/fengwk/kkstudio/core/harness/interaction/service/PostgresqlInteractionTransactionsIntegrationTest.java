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

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCreate;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerAction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerDirective;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionRequest;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResolution;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

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

  /** Allowing a Tool re-signals that Tool only; its suspended owning Thread must remain dormant. */
  @Test
  void resumesQueuedToolWithoutActivatingOwningThread() throws SQLException {
    seedQueuedTool();
    Interaction created = createTool(null);

    assertEquals(InteractionStatus.OPEN, created.status());
    assertFalse(threadRunnable(OWNER.id()));
    assertEquals("QUEUED", toolState().status());
    assertEquals(created.id(), transactions.findOpenByOwner(TOOL_OWNER).orElseThrow().id());

    InteractionTransition transition =
        transactions.resolve(
            created.id(),
            0L,
            new InteractionResponse("{\"allowed\":true}"),
            new InteractionResolution(resumeTool(), TOOL_OWNER),
            CREATED_AT.plusSeconds(1));

    assertEquals(TOOL_OWNER, transition.nextTarget());
    assertEquals(InteractionStatus.RESOLVED, transition.interaction().status());
    assertFalse(threadRunnable(OWNER.id()));
    assertEquals("QUEUED", toolState().status());
    assertNull(toolState().errorJson());
    assertTrue(transactions.findOpenByOwner(TOOL_OWNER).isEmpty());
  }

  /** Rejecting a Tool completes its durable owner transition before reactivating its Thread. */
  @Test
  void rejectsQueuedToolAndActivatesOwningThread() throws SQLException {
    seedQueuedTool();
    Interaction created = createTool(null);

    InteractionTransition transition =
        transactions.resolve(
            created.id(),
            0L,
            new InteractionResponse("{\"allowed\":false}"),
            new InteractionResolution(rejectTool(), OWNER),
            CREATED_AT.plusSeconds(1));

    assertEquals(OWNER, transition.nextTarget());
    assertEquals(InteractionStatus.RESOLVED, transition.interaction().status());
    assertTrue(threadRunnable(OWNER.id()));
    ToolState tool = toolState();
    assertEquals("FAILED", tool.status());
    assertJsonEquals(
        PostgresqlInteractionTransactions.INTERACTION_REJECTED_ERROR_JSON, tool.errorJson());
    assertTrue(tool.finished());
    assertNull(tool.resultJson());
  }

  /** Expiration has the same reject-and-reactivate owner effect without retaining a response. */
  @Test
  void expiresQueuedToolAsFailedAndActivatesOwningThread() throws SQLException {
    Instant expiresAt = CREATED_AT.plusSeconds(10);
    seedQueuedTool();
    Interaction created = createTool(expiresAt);

    InteractionTransition transition = transactions.expire(created.id(), 0L, expiresAt);

    assertEquals(OWNER, transition.nextTarget());
    assertEquals(InteractionStatus.EXPIRED, transition.interaction().status());
    assertNull(transition.interaction().response());
    assertTrue(threadRunnable(OWNER.id()));
    ToolState tool = toolState();
    assertEquals("FAILED", tool.status());
    assertJsonEquals(
        PostgresqlInteractionTransactions.INTERACTION_REJECTED_ERROR_JSON, tool.errorJson());
  }

  /** A stale terminal race rolls back the owner mutation and retains the first terminal outcome. */
  @Test
  void rollsBackToolOwnerMutationWhenTerminalVersionIsStale() throws SQLException {
    seedQueuedTool();
    Interaction created = createTool(null);
    transactions.resolve(
        created.id(),
        0L,
        new InteractionResponse("true"),
        new InteractionResolution(resumeTool(), TOOL_OWNER),
        CREATED_AT.plusSeconds(1));

    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.resolve(
                created.id(),
                0L,
                new InteractionResponse("false"),
                new InteractionResolution(rejectTool(), OWNER),
                CREATED_AT.plusSeconds(2)));
    assertFalse(threadRunnable(OWNER.id()));
    assertEquals("QUEUED", toolState().status());
    assertNull(toolState().errorJson());
    assertEquals(
        InteractionStatus.RESOLVED, transactions.find(created.id()).orElseThrow().status());
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

  private Interaction createTool(Instant expiresAt) {
    return transactions.create(
        new InteractionCreate(
            TOOL_OWNER,
            "tool-permission",
            new InteractionRequest("{\"question\":true}"),
            new InteractionOwnerDirective(InteractionOwnerAction.SUSPEND_TOOL_INVOCATION),
            expiresAt,
            CREATED_AT));
  }

  private InteractionOwnerDirective resumeThread() {
    return new InteractionOwnerDirective(InteractionOwnerAction.RESUME_THREAD);
  }

  private InteractionOwnerDirective resumeTool() {
    return new InteractionOwnerDirective(InteractionOwnerAction.RESUME_TOOL_TO_QUEUED);
  }

  private InteractionOwnerDirective rejectTool() {
    return new InteractionOwnerDirective(InteractionOwnerAction.REJECT_TOOL_TO_FAILED);
  }

  private void seedQueuedTool() throws SQLException {
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id, ordinal,"
              + " tool_call_id, descriptor, arguments, location, execution_epoch, status, attempt, created_at)"
              + " values (800, 700, 600, 601, 0, 'call-800', '{}'::jsonb, '{}'::jsonb, 'PLATFORM',"
              + " 0, 'QUEUED', 1, '2026-01-01T00:00:00Z')");
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

  private ToolState toolState() {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, error::text, result::text, finished_at is not null"
                    + " from harness_tool_invocation where id = ?")) {
      statement.setLong(1, TOOL_OWNER.id());
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return new ToolState(
            result.getString(1), result.getString(2), result.getString(3), result.getBoolean(4));
      }
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

  private record ToolState(String status, String errorJson, String resultJson, boolean finished) {}
}
