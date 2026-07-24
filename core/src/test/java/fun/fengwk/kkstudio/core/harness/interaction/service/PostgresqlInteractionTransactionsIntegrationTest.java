package fun.fengwk.kkstudio.core.harness.interaction.service;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
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
import java.time.Instant;

/**
 * Real PostgreSQL 17 coverage for generic Interaction fact creation and terminal CAS transitions.
 */
class PostgresqlInteractionTransactionsIntegrationTest extends PostgresSpringTestSupport {
  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final ExecutionTarget OWNER =
      new ExecutionTarget(ExecutionTargetKind.THREAD, 700L);
  private static final ExecutionTarget NEXT = OWNER;

  @Autowired private PostgresqlInteractionTransactions transactions;

  @BeforeEach
  void seedThreadOwner() throws SQLException {
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("set constraints all deferred");
      statement.executeUpdate(
          "insert into harness_session (id, main_thread_id, title, created_at, updated_at)"
              + " values (600, 700, 'test', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
      statement.executeUpdate(
          "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
              + " values (601, 600, null, 'ROOT', '{}'::jsonb, '2026-01-01T00:00:00Z')");
      statement.executeUpdate(
          "insert into harness_thread (id, session_id, head_entry_id, input_sequence, runnable, execution_epoch,"
              + " created_at, updated_at) values (700, 600, 601, 0, true, 0,"
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
}
