package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.runtime.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 真实 PostgreSQL / Testcontainers 环境下 Stop 回执集合存储契约测试。
 *
 * <p>核心验证点：
 *
 * <ul>
 *   <li><b>根对身份与隔离</b>：回执集合以 {@code (rootThreadId, rootStopRequestId)} 标识；不同执行树复用同一请求 UUID 时各自读取
 *       只返回本树集合，派生身份不互相串回执；
 *   <li><b>往返保真</b>：{@code cancelledInputs}（USER_MESSAGE / GOAL）经 jsonb 往返后 sequence、idempotencyKey 与 payload 精确保持；
 *   <li><b>集合校验</b>：插入必须包含根 Thread 自身回执，且每条回执 Thread 必须位于根树内，否则拒绝；
 *   <li><b>身份不可重用与 FK</b>：{@code (threadId, stopRequestId)} 重复插入与不存在的 {@code stoppedTurnEndEntryId} 均被拒绝；
 *   <li><b>深删清理</b>：删除 Thread 时同批清理其回执，避免孤儿行。
 * </ul>
 */
class PostgresqlStopReceiptTest {

  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private HarnessStore store;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    jdbcTemplate = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
  }

  /** 三层执行树：root -> child -> grandChild，共享同一 Session 与 head Entry。 */
  private record Tree(
      UUID sessionId, UUID rootEntryId, UUID rootThreadId, UUID childThreadId, UUID grandChildThreadId) {}

  private Tree seedTree(String label) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID rootThreadId = tx.nextId();
          UUID childThreadId = tx.nextId();
          UUID grandChildThreadId = tx.nextId();
          tx.insertSession(StoreTestSupport.session(sessionId));
          tx.insertEntry(StoreTestSupport.rootEntry(rootEntryId, sessionId));
          tx.insertThread(thread(rootThreadId, sessionId, null, rootEntryId, label + "-root"));
          tx.insertThread(
              thread(childThreadId, sessionId, rootThreadId, rootEntryId, label + "-child"));
          tx.insertThread(
              thread(
                  grandChildThreadId,
                  sessionId,
                  childThreadId,
                  rootEntryId,
                  label + "-grand"));
          return new Tree(sessionId, rootEntryId, rootThreadId, childThreadId, grandChildThreadId);
        });
  }

  private static ThreadState thread(
      UUID id, UUID sessionId, UUID parentThreadId, UUID headEntryId, String name) {
    return new ThreadState(
        id,
        sessionId,
        parentThreadId,
        headEntryId,
        HASH,
        name,
        false,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        StoreTestSupport.T0,
        StoreTestSupport.T0);
  }

  /** 锁定整棵树并插入给定回执集合。 */
  private void insertReceipts(
      UUID rootThreadId, UUID rootStopRequestId, List<StoppedThreadReceipt> receipts) {
    List<UUID> threadIds = receipts.stream().map(StoppedThreadReceipt::threadId).toList();
    StoreTestSupport.inTransaction(
        store,
        tx -> {
          tx.lockTree(rootThreadId);
          for (UUID threadId : threadIds) {
            tx.lockThread(threadId).orElseThrow();
          }
          tx.insertStopReceipts(rootThreadId, rootStopRequestId, receipts);
        });
  }

  @Test
  void stopReceiptRoundTripPersistsIdentityAndCancelledInputs() {
    // 测试意图：根对身份下三类回执可往返读取，集合按 Thread UUID 升序返回，cancelledInputs 的 USER_MESSAGE / GOAL 精确还原。
    Tree tree = seedTree("tree");
    UUID rootStopRequestId = UUID.randomUUID();
    UUID childStopRequestId = UUID.randomUUID();
    UUID grandStopRequestId = UUID.randomUUID();
    UUID userMessageIdempotencyKey = UUID.randomUUID();
    UUID goalIdempotencyKey = UUID.randomUUID();

    StoppedThreadReceipt rootReceipt =
        new StoppedThreadReceipt(tree.rootThreadId(), rootStopRequestId, null, 0, List.of());
    StoppedThreadReceipt childReceipt =
        new StoppedThreadReceipt(
            tree.childThreadId(),
            childStopRequestId,
            null,
            3,
            List.of(
                new CancelledThreadInput(
                    1,
                    userMessageIdempotencyKey,
                    new UserMessageCommandPayload(AgentMessage.user("draft one"))),
                new CancelledThreadInput(
                    2, goalIdempotencyKey, new GoalCommandPayload("Ship the feature"))));
    StoppedThreadReceipt grandReceipt =
        new StoppedThreadReceipt(tree.grandChildThreadId(), grandStopRequestId, null, 0, List.of());

    insertReceipts(
        tree.rootThreadId(),
        rootStopRequestId,
        List.of(grandReceipt, rootReceipt, childReceipt));

    // 单条回执读取精确往返（含 cancelledInputs 的 payload 类型与内容）。
    StoppedThreadReceipt loadedChild =
        store
            .transaction(tx -> tx.findStopReceipt(tree.childThreadId(), childStopRequestId))
            .orElseThrow();
    assertEquals(childReceipt, loadedChild);
    CancelledThreadInput loadedUserMessage = loadedChild.cancelledInputs().get(0);
    assertEquals(1L, loadedUserMessage.sequence());
    assertEquals(userMessageIdempotencyKey, loadedUserMessage.idempotencyKey());
    assertTrue(loadedUserMessage.payload() instanceof UserMessageCommandPayload);
    assertTrue(loadedChild.cancelledInputs().get(1).payload() instanceof GoalCommandPayload);

    // 按 Thread 读取只返回该 Thread 自己的回执。
    assertEquals(
        List.of(childReceipt),
        store.transaction(tx -> tx.loadStopReceiptsByThread(tree.childThreadId())));

    // 按根对读取整集合，按 Thread UUID 升序。
    List<StoppedThreadReceipt> expected = new ArrayList<>(List.of(rootReceipt, childReceipt, grandReceipt));
    expected.sort(
        Comparator.comparing(StoppedThreadReceipt::threadId, UuidOrder.COMPARATOR));
    assertEquals(
        expected,
        store.transaction(tx -> tx.loadStopReceiptsByRootRequest(tree.rootThreadId(), rootStopRequestId)));
  }

  @Test
  void stopReceiptsAreIsolatedByRootThreadWhenRequestUuidCollides() {
    // 测试意图：两棵不同执行树复用同一 rootStopRequestId 与同一 child stopRequestId 时，根对读取与单条读取都不串回执。
    Tree first = seedTree("first");
    Tree second = seedTree("second");
    UUID sharedRootStopRequestId = UUID.randomUUID();
    UUID sharedChildStopRequestId = UUID.randomUUID();

    StoppedThreadReceipt firstRoot =
        new StoppedThreadReceipt(first.rootThreadId(), sharedRootStopRequestId, null, 0, List.of());
    StoppedThreadReceipt firstChild =
        new StoppedThreadReceipt(first.childThreadId(), sharedChildStopRequestId, null, 0, List.of());
    StoppedThreadReceipt secondRoot =
        new StoppedThreadReceipt(second.rootThreadId(), sharedRootStopRequestId, null, 0, List.of());
    StoppedThreadReceipt secondChild =
        new StoppedThreadReceipt(second.childThreadId(), sharedChildStopRequestId, null, 0, List.of());

    insertReceipts(first.rootThreadId(), sharedRootStopRequestId, List.of(firstRoot, firstChild));
    insertReceipts(second.rootThreadId(), sharedRootStopRequestId, List.of(secondRoot, secondChild));

    List<StoppedThreadReceipt> firstSet =
        store.transaction(
            tx -> tx.loadStopReceiptsByRootRequest(first.rootThreadId(), sharedRootStopRequestId));
    List<StoppedThreadReceipt> secondSet =
        store.transaction(
            tx -> tx.loadStopReceiptsByRootRequest(second.rootThreadId(), sharedRootStopRequestId));
    assertEquals(2, firstSet.size());
    assertEquals(2, secondSet.size());
    assertTrue(firstSet.stream().allMatch(r -> r.threadId().equals(first.rootThreadId())
        || r.threadId().equals(first.childThreadId())));
    assertTrue(secondSet.stream().allMatch(r -> r.threadId().equals(second.rootThreadId())
        || r.threadId().equals(second.childThreadId())));

    assertEquals(
        firstChild,
        store
            .transaction(tx -> tx.findStopReceipt(first.childThreadId(), sharedChildStopRequestId))
            .orElseThrow());
    assertEquals(
        secondChild,
        store
            .transaction(tx -> tx.findStopReceipt(second.childThreadId(), sharedChildStopRequestId))
            .orElseThrow());

    // 根对不匹配返回空集合，不泄漏他树回执。
    assertEquals(
        List.of(),
        store.transaction(
            tx -> tx.loadStopReceiptsByRootRequest(first.rootThreadId(), UUID.randomUUID())));
    assertEquals(
        List.of(),
        store.transaction(
            tx -> tx.loadStopReceiptsByRootRequest(UUID.randomUUID(), sharedRootStopRequestId)));
  }

  @Test
  void stopReceiptInsertRejectsSetOutsideRootTreeOrMissingRootReceipt() {
    // 测试意图：插入集合必须包含根 Thread 自身回执且每条回执 Thread 位于根树内，否则拒绝且不留残留。
    Tree tree = seedTree("guarded");
    Tree unrelated = seedTree("unrelated");
    UUID rootStopRequestId = UUID.randomUUID();
    StoppedThreadReceipt rootReceipt =
        new StoppedThreadReceipt(tree.rootThreadId(), rootStopRequestId, null, 0, List.of());
    StoppedThreadReceipt childReceipt =
        new StoppedThreadReceipt(tree.childThreadId(), UUID.randomUUID(), null, 0, List.of());
    StoppedThreadReceipt foreignReceipt =
        new StoppedThreadReceipt(unrelated.rootThreadId(), UUID.randomUUID(), null, 0, List.of());

    // 缺少根 Thread 自身回执。
    IllegalArgumentException missingRoot =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                insertReceipts(
                    tree.rootThreadId(), rootStopRequestId, List.of(childReceipt)));
    assertTrue(missingRoot.getMessage().contains("root thread receipt"));

    // 混入其它树的回执。
    IllegalArgumentException foreign =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                insertReceipts(
                    tree.rootThreadId(),
                    rootStopRequestId,
                    List.of(rootReceipt, foreignReceipt)));
    assertTrue(foreign.getMessage().contains("not in the execution tree"));

    // 空集合拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> insertReceipts(tree.rootThreadId(), rootStopRequestId, List.of()));

    // 失败路径未留下任何回执行。
    Integer total =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_stop_receipt where root_stop_request_id = ?",
            Integer.class,
            rootStopRequestId);
    assertEquals(0, total);
  }

  @Test
  void stopReceiptInsertRejectsDuplicateIdentityAndMissingTurnEndEntry() {
    // 测试意图：回执身份 (threadId, stopRequestId) 不可重用；不存在的 stoppedTurnEndEntryId 被拒绝；合法 FK 可持久化。
    Tree tree = seedTree("identity");
    UUID rootStopRequestId = UUID.randomUUID();
    StoppedThreadReceipt rootReceipt =
        new StoppedThreadReceipt(tree.rootThreadId(), rootStopRequestId, null, 0, List.of());
    UUID childStopRequestId = UUID.randomUUID();
    StoppedThreadReceipt childReceipt =
        new StoppedThreadReceipt(tree.childThreadId(), childStopRequestId, null, 0, List.of());

    // 集合内重复 (threadId, stopRequestId)。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertReceipts(
                tree.rootThreadId(),
                rootStopRequestId,
                List.of(rootReceipt, childReceipt, childReceipt)));

    // 不存在的 stoppedTurnEndEntryId。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertReceipts(
                tree.rootThreadId(),
                rootStopRequestId,
                List.of(
                    rootReceipt,
                    new StoppedThreadReceipt(
                        tree.childThreadId(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0,
                        List.of()))));

    // 合法写入后重放同一身份被拒绝（身份不可重用）。
    insertReceipts(tree.rootThreadId(), rootStopRequestId, List.of(rootReceipt, childReceipt));
    assertThrows(
        IllegalArgumentException.class,
        () -> insertReceipts(tree.rootThreadId(), rootStopRequestId, List.of(rootReceipt)));

    // 直接写库验证 root_thread_id 外键门禁。
    DataIntegrityViolationException rootFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbcTemplate.update(
                    "insert into harness_thread_stop_receipt (root_thread_id, thread_id, stop_request_id,"
                        + " root_stop_request_id, cancelled_command_count, cancelled_inputs, created_at)"
                        + " values (?, ?, ?, ?, 0, '[]'::jsonb, statement_timestamp())",
                    UUID.randomUUID(),
                    tree.grandChildThreadId(),
                    UUID.randomUUID(),
                    UUID.randomUUID()));
    assertTrue(rootFk.getMessage().contains("fk_harness_thread_stop_receipt_root_thread"));
  }

  @Test
  void deletingThreadsRemovesTheirStopReceipts() {
    // 测试意图：深删子 Thread 清理其回执但保留存活根的回执；删除整棵树时全部回执被清理且无 FK 残留。
    Tree tree = seedTree("delete");
    UUID rootStopRequestId = UUID.randomUUID();
    StoppedThreadReceipt rootReceipt =
        new StoppedThreadReceipt(tree.rootThreadId(), rootStopRequestId, null, 0, List.of());
    StoppedThreadReceipt childReceipt =
        new StoppedThreadReceipt(tree.childThreadId(), UUID.randomUUID(), null, 0, List.of());
    StoppedThreadReceipt grandReceipt =
        new StoppedThreadReceipt(tree.grandChildThreadId(), UUID.randomUUID(), null, 0, List.of());
    insertReceipts(
        tree.rootThreadId(),
        rootStopRequestId,
        List.of(rootReceipt, childReceipt, grandReceipt));

    // 深删 child 子树：child 与 grandChild 回执被清理，root 回执保留。
    StoreTestSupport.inTransaction(
        store,
        tx -> {
          tx.lockTree(tree.rootThreadId());
          tx.lockThread(tree.childThreadId()).orElseThrow();
          tx.lockThread(tree.grandChildThreadId()).orElseThrow();
          tx.deleteThreads(List.of(tree.childThreadId(), tree.grandChildThreadId()));
        });
    assertEquals(
        List.of(rootReceipt),
        store.transaction(
            tx -> tx.loadStopReceiptsByRootRequest(tree.rootThreadId(), rootStopRequestId)));

    // 删除根 Thread（连同其回执）不再违反 root_thread_id 外键。
    StoreTestSupport.inTransaction(
        store,
        tx -> {
          tx.lockTree(tree.rootThreadId());
          tx.lockThread(tree.rootThreadId()).orElseThrow();
          tx.deleteThreads(List.of(tree.rootThreadId()));
        });
    Integer remaining =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_stop_receipt where root_stop_request_id = ?",
            Integer.class,
            rootStopRequestId);
    assertEquals(0, remaining);
  }
}
