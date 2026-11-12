package fun.fengwk.kkstudio.platform.harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.api.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.spi.driver.DriverThreadDeletion;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 真实 PostgreSQL / Testcontainers 环境下 Stop 回执集合存储契约测试。
 *
 * <p>核心验证点：
 *
 * <ul>
 *   <li><b>根对身份与隔离</b>：回执集合以 {@code (rootThreadId, rootStopRequestId)} 标识；不同执行树复用同一请求 UUID 时各自读取
 *       只返回本树集合，派生身份不互相串回执；
 *   <li><b>往返保真</b>：{@code cancelledInputs}（USER_MESSAGE）经 jsonb 往返后 sequence、idempotencyKey 与
 *       payload 精确保持；
 *   <li><b>集合校验</b>：插入必须包含根 Thread 自身回执，且每条回执 Thread 必须位于根树内，否则拒绝；
 *   <li><b>身份不可重用与 FK</b>：{@code (threadId, stopRequestId)} 重复插入与不存在的 {@code stoppedTurnEndEntryId}
 *       均被拒绝；
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
      UUID sessionId,
      UUID rootEntryId,
      UUID rootThreadId,
      UUID childThreadId,
      UUID grandChildThreadId) {}

  private Tree seedTree(String label) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID rootThreadId = tx.nextId();
          UUID childThreadId = tx.nextId();
          UUID grandChildThreadId = tx.nextId();
          tx.sessions().insert(StoreTestSupport.session(sessionId));
          tx.entries().insert(StoreTestSupport.rootEntry(rootEntryId, sessionId));
          tx.threads()
              .insert(thread(rootThreadId, sessionId, null, null, rootEntryId, label + "-root"));
          tx.threads()
              .insert(
                  thread(
                      childThreadId,
                      sessionId,
                      rootThreadId,
                      rootThreadId,
                      rootEntryId,
                      label + "-child"));
          tx.threads()
              .insert(
                  thread(
                      grandChildThreadId,
                      sessionId,
                      childThreadId,
                      rootThreadId,
                      rootEntryId,
                      label + "-grand"));
          return new Tree(sessionId, rootEntryId, rootThreadId, childThreadId, grandChildThreadId);
        });
  }

  private static ThreadState thread(
      UUID id,
      UUID sessionId,
      UUID parentThreadId,
      UUID rootThreadId,
      UUID headEntryId,
      String name) {
    return new ThreadState(
        id,
        sessionId,
        parentThreadId,
        headEntryId,
        HASH,
        name,
        parentThreadId == null ? id : rootThreadId,
        false,
        ThreadKind.SUBAGENT,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        StoreTestSupport.T0,
        StoreTestSupport.T0);
  }

  /** 锁定真实执行树根、停止目标与集合内全部 Thread（升序），随后插入给定回执集合。 */
  private void insertReceipts(
      UUID rootThreadId, UUID rootStopRequestId, List<StoppedThreadReceipt> receipts) {
    List<UUID> threadIds =
        Stream.concat(
                Stream.of(rootThreadId), receipts.stream().map(StoppedThreadReceipt::threadId))
            .distinct()
            .sorted(UuidOrder.COMPARATOR)
            .toList();
    StoreTestSupport.inTransaction(
        store,
        tx -> {
          List<UUID> chain = tx.threads().findAncestorChain(rootThreadId);
          tx.threads().lockTree(chain.isEmpty() ? rootThreadId : chain.get(chain.size() - 1));
          for (UUID threadId : threadIds) {
            tx.threads().lock(threadId).orElseThrow();
          }
          seedCancelledCommands(tx, receipts);
          tx.stops().insertReceipts(rootThreadId, rootStopRequestId, receipts);
        });
  }

  /** 回执的取消计数与输入只从 command 行读取；测试先把同一 Stop 的取消事实写入 command 表，再插入回执。 */
  private static void seedCancelledCommands(
      HarnessStore.Transaction tx, List<StoppedThreadReceipt> receipts) {
    for (StoppedThreadReceipt receipt : receipts) {
      Set<Long> inputSequences =
          receipt.cancelledInputs().stream()
              .map(CancelledThreadInput::sequence)
              .collect(Collectors.toSet());
      List<ThreadCommand> commands = new ArrayList<>();
      for (CancelledThreadInput input : receipt.cancelledInputs()) {
        commands.add(
            queuedCommand(
                receipt.threadId(), input.sequence(), input.idempotencyKey(), input.payload()));
      }
      long sequence = 1;
      for (int index = receipt.cancelledInputs().size();
          index < receipt.cancelledCommandCount();
          index++) {
        while (inputSequences.contains(sequence)) {
          sequence++;
        }
        commands.add(
            queuedCommand(
                receipt.threadId(),
                sequence++,
                UUID.randomUUID(),
                new SetAgentCommandPayload("ignored-cancelled-command")));
      }
      tx.commands().insert(commands);
      List<ThreadCommand> cancelled = new ArrayList<>(commands.size());
      for (ThreadCommand command : commands) {
        ThreadCommand stored =
            tx.commands()
                .find(command.threadId(), command.sequence())
                .orElseThrow(() -> new IllegalStateException("inserted command is missing"));
        cancelled.add(stored.cancel(receipt.stopRequestId(), StoreTestSupport.T0));
      }
      tx.commands().update(cancelled);
    }
  }

  private static ThreadCommand queuedCommand(
      UUID threadId, long sequence, UUID idempotencyKey, ThreadCommandPayload payload) {
    return new ThreadCommand(
        threadId, sequence, payload, idempotencyKey, HASH, null, null, null, StoreTestSupport.T0);
  }

  @Test
  void stopReceiptRoundTripPersistsIdentityAndCancelledInputs() {
    // 测试意图：根对身份下三类回执可往返读取，集合按 Thread UUID 升序返回，cancelledInputs 的 USER_MESSAGE 精确还原。
    Tree tree = seedTree("tree");
    UUID rootStopRequestId = UUID.randomUUID();
    UUID childStopRequestId = UUID.randomUUID();
    UUID grandStopRequestId = UUID.randomUUID();
    UUID userMessageIdempotencyKey = UUID.randomUUID();

    StoppedThreadReceipt rootReceipt =
        new StoppedThreadReceipt(tree.rootThreadId(), rootStopRequestId, null, 0, List.of());
    StoppedThreadReceipt childReceipt =
        new StoppedThreadReceipt(
            tree.childThreadId(),
            childStopRequestId,
            null,
            2,
            List.of(
                new CancelledThreadInput(
                    1,
                    userMessageIdempotencyKey,
                    new UserMessageCommandPayload(AgentMessage.user("draft one")))));
    StoppedThreadReceipt grandReceipt =
        new StoppedThreadReceipt(tree.grandChildThreadId(), grandStopRequestId, null, 0, List.of());

    insertReceipts(
        tree.rootThreadId(), rootStopRequestId, List.of(grandReceipt, rootReceipt, childReceipt));

    // 单条回执读取精确往返（含 cancelledInputs 的 payload 类型与内容）。
    StoppedThreadReceipt loadedChild =
        store
            .transaction(tx -> tx.stops().findReceipt(tree.childThreadId(), childStopRequestId))
            .orElseThrow();
    assertEquals(childReceipt, loadedChild);
    CancelledThreadInput loadedUserMessage = loadedChild.cancelledInputs().get(0);
    assertEquals(1L, loadedUserMessage.sequence());
    assertEquals(userMessageIdempotencyKey, loadedUserMessage.idempotencyKey());
    assertTrue(loadedUserMessage.payload() instanceof UserMessageCommandPayload);

    // 按 Thread 读取只返回该 Thread 自己的回执。
    assertEquals(
        List.of(childReceipt),
        store.transaction(tx -> tx.stops().loadByThread(tree.childThreadId())));

    // 按根对读取整集合，按 Thread UUID 升序。
    List<StoppedThreadReceipt> expected =
        new ArrayList<>(List.of(rootReceipt, childReceipt, grandReceipt));
    expected.sort(Comparator.comparing(StoppedThreadReceipt::threadId, UuidOrder.COMPARATOR));
    assertEquals(
        expected,
        store.transaction(
            tx -> tx.stops().loadByRootRequest(tree.rootThreadId(), rootStopRequestId)));
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
        new StoppedThreadReceipt(
            first.childThreadId(), sharedChildStopRequestId, null, 0, List.of());
    StoppedThreadReceipt secondRoot =
        new StoppedThreadReceipt(
            second.rootThreadId(), sharedRootStopRequestId, null, 0, List.of());
    StoppedThreadReceipt secondChild =
        new StoppedThreadReceipt(
            second.childThreadId(), sharedChildStopRequestId, null, 0, List.of());

    insertReceipts(first.rootThreadId(), sharedRootStopRequestId, List.of(firstRoot, firstChild));
    insertReceipts(
        second.rootThreadId(), sharedRootStopRequestId, List.of(secondRoot, secondChild));

    List<StoppedThreadReceipt> firstSet =
        store.transaction(
            tx -> tx.stops().loadByRootRequest(first.rootThreadId(), sharedRootStopRequestId));
    List<StoppedThreadReceipt> secondSet =
        store.transaction(
            tx -> tx.stops().loadByRootRequest(second.rootThreadId(), sharedRootStopRequestId));
    assertEquals(2, firstSet.size());
    assertEquals(2, secondSet.size());
    assertTrue(
        firstSet.stream()
            .allMatch(
                r ->
                    r.threadId().equals(first.rootThreadId())
                        || r.threadId().equals(first.childThreadId())));
    assertTrue(
        secondSet.stream()
            .allMatch(
                r ->
                    r.threadId().equals(second.rootThreadId())
                        || r.threadId().equals(second.childThreadId())));

    assertEquals(
        firstChild,
        store
            .transaction(
                tx -> tx.stops().findReceipt(first.childThreadId(), sharedChildStopRequestId))
            .orElseThrow());
    assertEquals(
        secondChild,
        store
            .transaction(
                tx -> tx.stops().findReceipt(second.childThreadId(), sharedChildStopRequestId))
            .orElseThrow());

    // 根对不匹配返回空集合，不泄漏他树回执。
    assertEquals(
        List.of(),
        store.transaction(
            tx -> tx.stops().loadByRootRequest(first.rootThreadId(), UUID.randomUUID())));
    assertEquals(
        List.of(),
        store.transaction(
            tx -> tx.stops().loadByRootRequest(UUID.randomUUID(), sharedRootStopRequestId)));
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
            () -> insertReceipts(tree.rootThreadId(), rootStopRequestId, List.of(childReceipt)));
    assertTrue(missingRoot.getMessage().contains("root thread receipt"));

    // 混入其它树的回执。
    IllegalArgumentException foreign =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                insertReceipts(
                    tree.rootThreadId(), rootStopRequestId, List.of(rootReceipt, foreignReceipt)));
    assertTrue(foreign.getMessage().contains("not in the stop subtree"));

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

    // root_stop_request_id 只是归组键：根身份由 stop_request_id = root_stop_request_id 的行表达，没有 root 外键。
    assertEquals(
        List.of("fk_harness_thread_stop_receipt_thread", "fk_harness_thread_stop_receipt_turn_end"),
        jdbcTemplate.queryForList(
            """
            select constraint_name
            from information_schema.table_constraints
            where table_schema = 'public' and table_name = 'harness_thread_stop_receipt'
              and constraint_type = 'FOREIGN KEY'
            order by constraint_name
            """,
            String.class));
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
        tree.rootThreadId(), rootStopRequestId, List.of(rootReceipt, childReceipt, grandReceipt));

    // 深删 child 子树：child 与 grandChild 回执被清理，root 回执保留。
    StoreTestSupport.inTransaction(
        store,
        tx -> {
          tx.threads().lockTree(tree.rootThreadId());
          tx.threads().lock(tree.childThreadId()).orElseThrow();
          tx.threads().lock(tree.grandChildThreadId()).orElseThrow();
          tx.threads()
              .delete(
                  List.of(tree.childThreadId(), tree.grandChildThreadId()),
                  DriverThreadDeletion.NONE);
        });
    assertEquals(
        List.of(rootReceipt),
        store.transaction(
            tx -> tx.stops().loadByRootRequest(tree.rootThreadId(), rootStopRequestId)));

    // 删除根 Thread（连同其回执）不会留下任何 root 外键残留。
    StoreTestSupport.inTransaction(
        store,
        tx -> {
          tx.threads().lockTree(tree.rootThreadId());
          tx.threads().lock(tree.rootThreadId()).orElseThrow();
          tx.threads().delete(List.of(tree.rootThreadId()), DriverThreadDeletion.NONE);
        });
    Integer remaining =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_stop_receipt where root_stop_request_id = ?",
            Integer.class,
            rootStopRequestId);
    assertEquals(0, remaining);
  }

  @Test
  void stopReceiptsSupportMiddleNodeTargetWithinItsSubtreeOnly() {
    // 测试意图：Stop 目标是中间节点时，集合只包含目标子树（自身 + 后代），祖先与兄弟都不受影响也不得混入。
    Tree tree = seedTree("middle");
    UUID siblingThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.threads()
                  .insert(
                      thread(
                          id,
                          tree.sessionId(),
                          tree.rootThreadId(),
                          tree.rootThreadId(),
                          tree.rootEntryId(),
                          "middle-sibling"));
              return id;
            });
    UUID subtreeStopRequestId = UUID.randomUUID();
    UUID grandStopRequestId = UUID.randomUUID();
    StoppedThreadReceipt childReceipt =
        new StoppedThreadReceipt(tree.childThreadId(), subtreeStopRequestId, null, 0, List.of());
    StoppedThreadReceipt grandReceipt =
        new StoppedThreadReceipt(tree.grandChildThreadId(), grandStopRequestId, null, 0, List.of());

    // 目标为中间节点 child：自身与后代各一条回执。
    insertReceipts(tree.childThreadId(), subtreeStopRequestId, List.of(childReceipt, grandReceipt));
    assertEquals(
        2,
        store
            .transaction(
                tx -> tx.stops().loadByRootRequest(tree.childThreadId(), subtreeStopRequestId))
            .size());
    assertTrue(
        store
            .transaction(
                tx -> tx.stops().loadByRootRequest(tree.childThreadId(), subtreeStopRequestId))
            .stream()
            .noneMatch(receipt -> receipt.threadId().equals(tree.rootThreadId())));
    // 祖先 root 与兄弟都不产生该根对的回执。
    assertEquals(
        List.of(),
        store.transaction(
            tx -> tx.stops().loadByRootRequest(tree.rootThreadId(), subtreeStopRequestId)));
    assertEquals(List.of(), store.transaction(tx -> tx.stops().loadByThread(siblingThreadId)));

    // 集合混入祖先回执：祖先不是 rootThreadId 的后代，拒绝。
    UUID ancestorMixRootRequestId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertReceipts(
                tree.childThreadId(),
                ancestorMixRootRequestId,
                List.of(
                    new StoppedThreadReceipt(
                        tree.childThreadId(), ancestorMixRootRequestId, null, 0, List.of()),
                    new StoppedThreadReceipt(
                        tree.rootThreadId(), UUID.randomUUID(), null, 0, List.of()))));

    // 集合混入兄弟回执：兄弟不在目标子树内，拒绝。
    StoppedThreadReceipt siblingReceipt =
        new StoppedThreadReceipt(siblingThreadId, UUID.randomUUID(), null, 0, List.of());
    UUID rejectedRootRequestId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertReceipts(
                tree.childThreadId(),
                rejectedRootRequestId,
                List.of(
                    new StoppedThreadReceipt(
                        tree.childThreadId(), rejectedRootRequestId, null, 0, List.of()),
                    siblingReceipt)));
  }

  @Test
  void stopReceiptRootMustCarryTheRootStopRequestId() {
    // 测试意图：root Thread 自身的回执必须携带 rootStopRequestId，防止根对身份与根回执身份不一致。
    Tree tree = seedTree("root-identity");
    UUID rootStopRequestId = UUID.randomUUID();
    UUID mismatchedStopRequestId = UUID.randomUUID();
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                insertReceipts(
                    tree.rootThreadId(),
                    rootStopRequestId,
                    List.of(
                        new StoppedThreadReceipt(
                            tree.rootThreadId(), mismatchedStopRequestId, null, 0, List.of()))));
    assertTrue(error.getMessage().contains("root stop request id"));
  }

  @Test
  void stopReceiptTurnEndMustBeOwnedStoppedTurnEndOfTheSameThread() {
    // 测试意图：stoppedTurnEndEntryId 必须是该 Thread 自己 Turn 的 STOPPED TurnEnd，而非任意存在 Entry。
    Tree tree = seedTree("turn-end");
    UUID rootStopRequestId = UUID.randomUUID();
    UUID ownedStoppedEnd =
        insertTurnEnd(tree.sessionId(), tree.rootEntryId(), tree.childThreadId(), true);
    UUID ownedCompletedEnd =
        insertTurnEnd(tree.sessionId(), tree.rootEntryId(), tree.childThreadId(), false);
    UUID foreignOwnedStoppedEnd =
        insertTurnEnd(tree.sessionId(), tree.rootEntryId(), tree.rootThreadId(), true);

    // 正向：合法的自有 STOPPED TurnEnd 被接受。
    insertReceipts(
        tree.rootThreadId(),
        rootStopRequestId,
        List.of(
            new StoppedThreadReceipt(tree.rootThreadId(), rootStopRequestId, null, 0, List.of()),
            new StoppedThreadReceipt(
                tree.childThreadId(), UUID.randomUUID(), ownedStoppedEnd, 0, List.of())));

    // 非 STOPPED TurnEnd 被拒绝。
    UUID completedRootRequestId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertReceipts(
                tree.rootThreadId(),
                completedRootRequestId,
                List.of(
                    new StoppedThreadReceipt(
                        tree.rootThreadId(), completedRootRequestId, null, 0, List.of()),
                    new StoppedThreadReceipt(
                        tree.childThreadId(),
                        UUID.randomUUID(),
                        ownedCompletedEnd,
                        0,
                        List.of()))));

    // 属于其它 Thread 的 STOPPED TurnEnd 被拒绝。
    UUID foreignRootRequestId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertReceipts(
                tree.rootThreadId(),
                foreignRootRequestId,
                List.of(
                    new StoppedThreadReceipt(
                        tree.rootThreadId(), foreignRootRequestId, null, 0, List.of()),
                    new StoppedThreadReceipt(
                        tree.childThreadId(),
                        UUID.randomUUID(),
                        foreignOwnedStoppedEnd,
                        0,
                        List.of()))));

    // 非 TurnEnd 的任意 Entry 被拒绝。
    UUID nonTurnEndRootRequestId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertReceipts(
                tree.rootThreadId(),
                nonTurnEndRootRequestId,
                List.of(
                    new StoppedThreadReceipt(
                        tree.rootThreadId(), nonTurnEndRootRequestId, null, 0, List.of()),
                    new StoppedThreadReceipt(
                        tree.childThreadId(),
                        UUID.randomUUID(),
                        tree.rootEntryId(),
                        0,
                        List.of()))));
  }

  /** 在 {@code parentEntryId} 下插入一个结构合法的自有 Turn 并返回其 TurnEnd id；{@code stopped} 决定 outcome。 */
  private UUID insertTurnEnd(
      UUID sessionId, UUID parentEntryId, UUID ownerThreadId, boolean stopped) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      sessionId,
                      parentEntryId,
                      new TurnStartPayload(
                          stopped ? TurnStartReason.STOP : TurnStartReason.INPUT,
                          StoreTestSupport.branchSettings(),
                          ownerThreadId),
                      StoreTestSupport.T1));
          UUID parentId = turnStartId;
          if (stopped) {
            UUID barrierId = tx.nextId();
            tx.entries()
                .insert(
                    new Entry(
                        barrierId,
                        sessionId,
                        parentId,
                        new AssistantErrorPayload(
                            new AssistantError(AssistantError.CANCELLED_CODE, "Cancelled by user."),
                            null),
                        StoreTestSupport.T1));
            parentId = barrierId;
          } else {
            UUID userId = tx.nextId();
            tx.entries()
                .insert(
                    new Entry(
                        userId,
                        sessionId,
                        parentId,
                        StoreTestSupport.userMessagePayload(),
                        StoreTestSupport.T1));
            UUID assistantId = tx.nextId();
            tx.entries()
                .insert(
                    StoreTestSupport.assistantEntry(
                        assistantId, sessionId, userId, StoreTestSupport.T1));
            parentId = assistantId;
          }
          UUID turnEndId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnEndId,
                      sessionId,
                      parentId,
                      stopped
                          ? new TurnEndPayload(
                              turnStartId,
                              TurnEndOutcome.STOPPED,
                              false,
                              TurnEndReason.USER_STOP,
                              UUID.randomUUID())
                          : new TurnEndPayload(
                              turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                      StoreTestSupport.T1));
          return turnEndId;
        });
  }
}
