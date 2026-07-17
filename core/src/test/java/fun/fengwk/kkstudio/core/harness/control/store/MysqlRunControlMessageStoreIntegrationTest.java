package fun.fengwk.kkstudio.core.harness.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.runtime.control.ControlConsumptionMode;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 真实 H2 schema 上的端到端覆盖：独立 ID namespace、nullable originalRunId、insert/find、按 kind/run/session 隔离、id
 * asc 排序、 markConsumed/Promoted/Cleared 的 CAS 幂等性、clearPendingByRun 批量回写，以及 schema 没有
 * workspace/tenant 列的断言。
 */
@SpringBootTest(classes = CoreTestApplication.class)
class MysqlRunControlMessageStoreIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

  @Autowired private MysqlRunControlMessageStore store;
  @Autowired private RunControlIdGenerator idGenerator;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_run_control_message");
  }

  /** 每个控制消息 id 必须来自独立 namespace，不能与 harness_run 等其它表冲突。 */
  @Test
  void generatesIdsFromIndependentNamespace() {
    long idA = idGenerator.newControlMessageId();
    long idB = idGenerator.newControlMessageId();
    assertTrue(idA > 0);
    assertTrue(idB > 0);
    assertTrue(idA != idB);
  }

  @Test
  void insertAndFindRoundTripsAllFields() {
    AgentMessage message = userMessage("hello");
    RunControlMessage original =
        new RunControlMessage(
            idGenerator.newControlMessageId(),
            11L,
            21L,
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME,
            message,
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null);

    assertEquals(1, store.insert(original));

    Optional<RunControlMessage> loaded = store.find(original.id());
    assertTrue(loaded.isPresent());
    RunControlMessage found = loaded.get();
    assertEquals(original.id(), found.id());
    assertEquals(11L, found.sessionId());
    assertEquals(21L, found.originalRunId());
    assertEquals(RunControlKind.STEER, found.kind());
    assertEquals(ControlConsumptionMode.ONE_AT_A_TIME, found.consumptionMode());
    assertEquals(message, found.message());
    assertEquals(RunControlStatus.PENDING, found.status());
    assertNull(found.consumedRunId());
    assertNull(found.consumedEntryId());
    assertNull(found.consumedAt());
    assertEquals(NOW, found.createdAt());
  }

  /** FOLLOW_UP 在无 active run 时 originalRunId=null 必须可持久化与恢复。 */
  @Test
  void insertAndFindSupportsNullOriginalRunId() {
    RunControlMessage original =
        new RunControlMessage(
            idGenerator.newControlMessageId(),
            11L,
            null,
            RunControlKind.FOLLOW_UP,
            ControlConsumptionMode.ALL,
            userMessage("promote me"),
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null);
    assertEquals(1, store.insert(original));

    RunControlMessage found = store.find(original.id()).orElseThrow();
    assertNull(found.originalRunId());
    assertEquals(RunControlKind.FOLLOW_UP, found.kind());
    assertEquals(ControlConsumptionMode.ALL, found.consumptionMode());
  }

  /** 必须按 id asc 拉 PENDING；listPendingByRun 与 listPendingBySession 互相隔离其它 run/session 的 PENDING。 */
  @Test
  void pendingListOrdersByIdAscAndIsolatesByRunAndSession() {
    long first = insertPending(11L, 21L, RunControlKind.STEER);
    long second = insertPending(11L, 21L, RunControlKind.STEER);
    long third = insertPending(11L, 21L, RunControlKind.FOLLOW_UP);
    long otherRunSteer = insertPending(11L, 22L, RunControlKind.STEER);
    long otherSessionSteer = insertPending(12L, 21L, RunControlKind.STEER);
    long followUpAgain = insertPending(11L, 21L, RunControlKind.FOLLOW_UP);

    // run=21 STEER 包含 first、second、otherSessionSteer (session=12, run=21)
    List<Long> byRunSteer = ids(store.listPendingByRun(21L, RunControlKind.STEER));
    assertEquals(List.of(first, second, otherSessionSteer), byRunSteer);
    // run=21 FOLLOW_UP 包含 third、followUpAgain
    List<Long> byRunFollowUp = ids(store.listPendingByRun(21L, RunControlKind.FOLLOW_UP));
    assertEquals(List.of(third, followUpAgain), byRunFollowUp);
    // run=22 STEER 仅含 otherRunSteer
    assertEquals(List.of(otherRunSteer), ids(store.listPendingByRun(22L, RunControlKind.STEER)));
    assertFalse(byRunSteer.contains(third));
    assertFalse(byRunFollowUp.contains(first));

    // session=11 PENDING 包含 first, second, third, otherRunSteer, followUpAgain（不含 otherSessionSteer
    // 因为 session=12）
    List<Long> bySession = ids(store.listPendingBySession(11L));
    assertEquals(List.of(first, second, third, otherRunSteer, followUpAgain), bySession);
    assertEquals(List.of(otherSessionSteer), ids(store.listPendingBySession(12L)));
  }

  /** CAS markConsumed 仅生效一次；二次调用与后续 markCleared 都必须 no-op。 */
  @Test
  void markConsumedIsIdempotentAndBlocksLaterTransitions() {
    long id = insertPending(11L, 21L, RunControlKind.STEER);

    assertTrue(store.markConsumed(id, 21L, 99L, NOW.plusSeconds(1)));
    RunControlMessage consumed = store.find(id).orElseThrow();
    assertEquals(RunControlStatus.CONSUMED, consumed.status());
    assertEquals(21L, consumed.consumedRunId());
    assertEquals(99L, consumed.consumedEntryId());
    assertEquals(NOW.plusSeconds(1), consumed.consumedAt());

    // 二次 CAS no-op（行已不是 PENDING）
    assertFalse(store.markConsumed(id, 21L, 100L, NOW.plusSeconds(2)));
    assertFalse(store.markCleared(id, NOW.plusSeconds(3)));
    assertFalse(store.markPromoted(id, 21L, 101L, NOW.plusSeconds(3)));

    RunControlMessage stillConsumed = store.find(id).orElseThrow();
    assertEquals(RunControlStatus.CONSUMED, stillConsumed.status());
    assertEquals(99L, stillConsumed.consumedEntryId());
  }

  /** PROMOTED 只允许从 PENDING 推进；不能再次被 CAS 推进为 CONSUMED/CLEARED。 */
  @Test
  void markPromotedBlocksFurtherCas() {
    long id = insertPending(11L, null, RunControlKind.FOLLOW_UP);

    assertTrue(store.markPromoted(id, 88L, 33L, NOW.plusSeconds(2)));
    RunControlMessage promoted = store.find(id).orElseThrow();
    assertEquals(RunControlStatus.PROMOTED, promoted.status());
    assertEquals(88L, promoted.consumedRunId());
    assertEquals(33L, promoted.consumedEntryId());

    assertFalse(store.markConsumed(id, 99L, 100L, NOW.plusSeconds(3)));
    assertFalse(store.markCleared(id, NOW.plusSeconds(3)));
    assertEquals(RunControlStatus.PROMOTED, store.find(id).orElseThrow().status());
  }

  /** CLEARED 写 consumedAt 但不伪造 consumedRunId/consumedEntryId。 */
  @Test
  void markClearedDoesNotFabricateConsumedIds() {
    long id = insertPending(11L, 21L, RunControlKind.STEER);

    assertTrue(store.markCleared(id, NOW.plusSeconds(4)));
    RunControlMessage cleared = store.find(id).orElseThrow();
    assertEquals(RunControlStatus.CLEARED, cleared.status());
    assertNull(cleared.consumedRunId());
    assertNull(cleared.consumedEntryId());
    assertEquals(NOW.plusSeconds(4), cleared.consumedAt());
  }

  /** clearPendingByRun 一次清空某 run 下所有 PENDING；不影响其它 run 的 PENDING；已非 PENDING 不被改写。 */
  @Test
  void clearPendingByRunBatchOnlyTouchesGivenRun() {
    long a1 = insertPending(11L, 21L, RunControlKind.STEER);
    long a2 = insertPending(11L, 21L, RunControlKind.FOLLOW_UP);
    long otherRun = insertPending(11L, 22L, RunControlKind.STEER);
    long consumed = insertPending(11L, 21L, RunControlKind.STEER);
    store.markConsumed(consumed, 21L, 9L, NOW);

    int cleared = store.clearPendingByRun(21L, NOW.plusSeconds(10));
    assertEquals(2, cleared);

    assertEquals(RunControlStatus.CLEARED, store.find(a1).orElseThrow().status());
    assertEquals(RunControlStatus.CLEARED, store.find(a2).orElseThrow().status());
    assertEquals(RunControlStatus.PENDING, store.find(otherRun).orElseThrow().status());
    assertEquals(RunControlStatus.CONSUMED, store.find(consumed).orElseThrow().status());
  }

  /** schema 必须无 workspace / tenant 列。 */
  @Test
  void schemaDoesNotCarryWorkspaceOrTenantColumns() {
    List<String> columns =
        jdbc.queryForList(
            "select column_name from information_schema.columns"
                + " where lower(table_name) = 'harness_run_control_message'",
            String.class);
    String joined = String.join(",", columns).toLowerCase();
    assertFalse(joined.contains("workspace"), joined);
    assertFalse(joined.contains("tenant"), joined);
    assertTrue(joined.contains("run_id"), joined);
    assertTrue(joined.contains("session_id"), joined);
    assertTrue(joined.contains("control_kind"), joined);
    assertTrue(joined.contains("consumption_mode"), joined);
    assertTrue(joined.contains("status"), joined);
    assertTrue(joined.contains("message_json"), joined);
  }

  /** 期望的索引在 schema 上必须存在。 */
  @Test
  void schemaHasRequiredControlIndexes() {
    List<String> indexes =
        jdbc.queryForList(
            "select index_name from information_schema.indexes"
                + " where lower(table_name) = 'harness_run_control_message'",
            String.class);
    assertTrue(
        indexes.stream().anyMatch("idx_harness_control_pending"::equalsIgnoreCase),
        "missing pending index: " + indexes);
    assertTrue(
        indexes.stream().anyMatch("idx_harness_control_session"::equalsIgnoreCase),
        "missing session index: " + indexes);
  }

  @Test
  void listPendingByRunForNullOriginalRunIdReturnsEmpty() {
    insertPending(11L, null, RunControlKind.FOLLOW_UP);
    insertPending(11L, 21L, RunControlKind.FOLLOW_UP);

    assertEquals(0, store.listPendingByRun(0L, RunControlKind.FOLLOW_UP).size());
  }

  /** 不同 message 内容互不干扰；round-trip 通过 codec 解码保证与原 AgentMessage 相等。 */
  @Test
  void distinctMessagesRoundTripIndependently() {
    AgentMessage m1 = userMessage("first");
    AgentMessage m2 = userMessage("second");
    RunControlMessage a =
        new RunControlMessage(
            idGenerator.newControlMessageId(),
            11L,
            21L,
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME,
            m1,
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null);
    RunControlMessage b =
        new RunControlMessage(
            idGenerator.newControlMessageId(),
            11L,
            22L,
            RunControlKind.FOLLOW_UP,
            ControlConsumptionMode.ALL,
            m2,
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null);
    store.insert(a);
    store.insert(b);

    assertEquals(m1, store.find(a.id()).orElseThrow().message());
    assertEquals(m2, store.find(b.id()).orElseThrow().message());
  }

  /** 复用同一 id 二次 insert 必须因 PRIMARY KEY 冲突而失败。 */
  @Test
  void duplicateInsertIsRejected() {
    long id = idGenerator.newControlMessageId();
    store.insert(
        new RunControlMessage(
            id,
            11L,
            21L,
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME,
            userMessage("hi"),
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            store.insert(
                new RunControlMessage(
                    id,
                    11L,
                    21L,
                    RunControlKind.STEER,
                    ControlConsumptionMode.ONE_AT_A_TIME,
                    userMessage("hi"),
                    RunControlStatus.PENDING,
                    null,
                    null,
                    NOW,
                    null)));
  }

  /** insert 只接受新建 PENDING；终态必须通过 CAS 推进。 */
  @Test
  void insertRejectsNonPendingStatus() {
    long id = idGenerator.newControlMessageId();
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.insert(
                    new RunControlMessage(
                        id,
                        11L,
                        21L,
                        RunControlKind.STEER,
                        ControlConsumptionMode.ONE_AT_A_TIME,
                        userMessage("hi"),
                        RunControlStatus.CONSUMED,
                        21L,
                        99L,
                        NOW,
                        NOW.plusSeconds(1))));
    assertEquals("control insert only accepts PENDING but was CONSUMED", exception.getMessage());
  }

  private long insertPending(long sessionId, Long originalRunId, RunControlKind kind) {
    RunControlMessage msg =
        new RunControlMessage(
            idGenerator.newControlMessageId(),
            sessionId,
            originalRunId,
            kind,
            ControlConsumptionMode.ONE_AT_A_TIME,
            userMessage("hi-" + originalRunId),
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null);
    store.insert(msg);
    return msg.id();
  }

  private static List<Long> ids(List<RunControlMessage> messages) {
    return messages.stream().map(RunControlMessage::id).toList();
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }
}
