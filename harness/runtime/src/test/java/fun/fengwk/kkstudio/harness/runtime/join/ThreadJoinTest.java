package fun.fengwk.kkstudio.harness.runtime.join;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** ThreadJoin 不可变记录、状态机跃迁与 validateTransition 约束测试。 */
class ThreadJoinTest {

  private static final String VALID_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
  private static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");
  private static final Instant T3 = Instant.parse("2026-01-01T00:00:03Z");

  private ThreadJoin initialJoin(UUID invocationId, UUID parentThreadId, UUID childThreadId) {
    return new ThreadJoin(
        invocationId,
        VALID_HASH,
        parentThreadId,
        childThreadId,
        1L,
        0L,
        "test-agent",
        10,
        0L,
        null,
        null,
        null,
        T0,
        T0);
  }

  @Test
  void acceptsValidInitialTaskJoin() {
    // 测试意图：验证带 parentThreadId 的初始未匹配 ThreadJoin 正确记录所有字段并判定 matched 为 false。
    UUID invocationId = id(1);
    UUID parentId = id(2);
    UUID childId = id(3);
    ThreadJoin join = initialJoin(invocationId, parentId, childId);

    assertEquals(invocationId, join.invocationId());
    assertEquals(VALID_HASH, join.requestHash());
    assertEquals(parentId, join.parentThreadId());
    assertEquals(childId, join.childThreadId());
    assertEquals(1L, join.sourceCommandSequence());
    assertEquals(0L, join.afterVersion());
    assertEquals("test-agent", join.agent());
    assertEquals(10, join.maxTurns());
    assertEquals(0L, join.reminderTurn());
    assertNull(join.matchedIdleVersion());
    assertNull(join.resultHeadEntryId());
    assertNull(join.deliveryCommandSequence());
    assertEquals(T0, join.createdAt());
    assertEquals(T0, join.updatedAt());
    assertFalse(join.matched());
  }

  @Test
  void acceptsValidInitialRootTicket() {
    // 测试意图：验证 parentThreadId 与 maxTurns 为 null 的根 ticket 可成功构造。
    UUID invocationId = id(10);
    UUID childId = id(11);
    ThreadJoin rootJoin =
        new ThreadJoin(
            invocationId,
            VALID_HASH,
            null,
            childId,
            1L,
            0L,
            "root-agent",
            null,
            0L,
            null,
            null,
            null,
            T0,
            T1);

    assertEquals(invocationId, rootJoin.invocationId());
    assertNull(rootJoin.parentThreadId());
    assertEquals(childId, rootJoin.childThreadId());
    assertNull(rootJoin.maxTurns());
    assertEquals(T1, rootJoin.updatedAt());
    assertFalse(rootJoin.matched());
  }

  @Test
  void acceptsValidMatchedAndDeliveredJoin() {
    // 测试意图：验证已匹配且已投递的完整状态 ThreadJoin 构造及 matched 判定为 true。
    UUID invocationId = id(20);
    UUID parentId = id(21);
    UUID childId = id(22);
    UUID resultHead = id(23);

    ThreadJoin delivered =
        new ThreadJoin(
            invocationId,
            VALID_HASH,
            parentId,
            childId,
            1L,
            0L,
            "test-agent",
            10,
            2L,
            3L,
            resultHead,
            5L,
            T0,
            T2);

    assertTrue(delivered.matched());
    assertEquals(3L, delivered.matchedIdleVersion());
    assertEquals(resultHead, delivered.resultHeadEntryId());
    assertEquals(5L, delivered.deliveryCommandSequence());
    assertEquals(2L, delivered.reminderTurn());
  }

  @Test
  void acceptsBoundaryAgentLength() {
    // 测试意图：验证 agent 字段在长度 1 与最大限制 256 字符时的边界行为。
    ThreadJoin join1 =
        new ThreadJoin(
            id(1), VALID_HASH, id(2), id(3), 1L, 0L, "a", null, 0L, null, null, null, T0, T0);
    assertEquals("a", join1.agent());

    String maxLenAgent = "x".repeat(256);
    ThreadJoin join2 =
        new ThreadJoin(
            id(1),
            VALID_HASH,
            id(2),
            id(3),
            1L,
            0L,
            maxLenAgent,
            null,
            0L,
            null,
            null,
            null,
            T0,
            T0);
    assertEquals(maxLenAgent, join2.agent());
  }

  @Test
  void acceptsBoundaryTimestamps() {
    // 测试意图：验证 updatedAt 等于 createdAt 或晚于 createdAt 时均合法。
    assertDoesNotThrow(
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                null,
                0L,
                null,
                null,
                null,
                T0,
                T0));

    assertDoesNotThrow(
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                null,
                0L,
                null,
                null,
                null,
                T0,
                T1));
  }

  @Test
  void rejectsNullRequiredFields() {
    // 测试意图：验证 invocationId、childThreadId、createdAt 与 updatedAt 为 null 时抛出 NullPointerException。
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoin(
                null, VALID_HASH, id(2), id(3), 1L, 0L, "agent", 10, 0L, null, null, null, T0, T0));

    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), null, 1L, 0L, "agent", 10, 0L, null, null, null, T0, T0));

    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                null,
                T0));

    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                T0,
                null));
  }

  @Test
  void rejectsInvalidRequestHash() {
    // 测试意图：验证 requestHash 校验（null、大写、非法字符、长度不符）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), null, id(2), id(3), 1L, 0L, "agent", 10, 0L, null, null, null, T0, T0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef",
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                T0,
                T0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                "invalid-hash",
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                T0,
                T0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH + "0",
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                T0,
                T0));
  }

  @Test
  void rejectsParentSameAsChild() {
    // 测试意图：验证 parentThreadId 与 childThreadId 相同（自引用）时抛出 IllegalArgumentException。
    UUID sameThreadId = id(100);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ThreadJoin(
                    id(1),
                    VALID_HASH,
                    sameThreadId,
                    sameThreadId,
                    1L,
                    0L,
                    "agent",
                    10,
                    0L,
                    null,
                    null,
                    null,
                    T0,
                    T0));
    assertEquals("join parent and child must differ", ex.getMessage());
  }

  @Test
  void rejectsInvalidSequencesAndVersions() {
    // 测试意图：验证 sourceCommandSequence <= 0、afterVersion < 0 与 reminderTurn < 0 的非法取值被拒绝。
    // sourceCommandSequence = 0
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                0L,
                0L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                T0,
                T0));

    // sourceCommandSequence = -1
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                -1L,
                0L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                T0,
                T0));

    // afterVersion = -1
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                -1L,
                "agent",
                10,
                0L,
                null,
                null,
                null,
                T0,
                T0));

    // reminderTurn = -1
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                10,
                -1L,
                null,
                null,
                null,
                T0,
                T0));
  }

  @Test
  void rejectsInvalidAgent() {
    // 测试意图：验证 agent 为 null、空白字符串或超过 256 字符时抛出异常。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, null, 10, 0L, null, null, null, T0, T0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, "", 10, 0L, null, null, null, T0, T0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, "   ", 10, 0L, null, null, null, T0, T0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "a".repeat(257),
                10,
                0L,
                null,
                null,
                null,
                T0,
                T0));
  }

  @Test
  void rejectsInvalidMaxTurns() {
    // 测试意图：验证 maxTurns 非空时必须为正整数。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, "agent", 0, 0L, null, null, null, T0, T0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                -5,
                0L,
                null,
                null,
                null,
                T0,
                T0));
  }

  @Test
  void rejectsInconsistentMatchedReceipt() {
    // 测试意图：验证 matchedIdleVersion 与 resultHeadEntryId 必须同时存在且 matchedIdleVersion > afterVersion。
    // 有 matchedIdleVersion 但没有 resultHeadEntryId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, "agent", 10, 0L, 1L, null, null, T0, T0));

    // 无 matchedIdleVersion 但有 resultHeadEntryId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1),
                VALID_HASH,
                id(2),
                id(3),
                1L,
                0L,
                "agent",
                10,
                0L,
                null,
                id(4),
                null,
                T0,
                T0));

    // matchedIdleVersion 等于 afterVersion
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 2L, "agent", 10, 0L, 2L, id(4), null, T0, T0));

    // matchedIdleVersion 小于 afterVersion
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 5L, "agent", 10, 0L, 3L, id(4), null, T0, T0));
  }

  @Test
  void rejectsInvalidDeliveryCommandSequence() {
    // 测试意图：验证 deliveryCommandSequence 必须在有 parentThreadId、已匹配（matched）且序号大于 0 时才合法。
    // deliveryCommandSequence 非空但 parentThreadId 为空
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, null, id(3), 1L, 0L, "agent", null, 0L, 1L, id(4), 1L, T0, T0));

    // deliveryCommandSequence 非空但尚未匹配（matchedIdleVersion 为空）
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, "agent", 10, 0L, null, null, 1L, T0, T0));

    // deliveryCommandSequence 为 0
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, "agent", 10, 0L, 1L, id(4), 0L, T0, T0));

    // deliveryCommandSequence 为 -1
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadJoin(
                id(1), VALID_HASH, id(2), id(3), 1L, 0L, "agent", 10, 0L, 1L, id(4), -1L, T0, T0));
  }

  @Test
  void rejectsUpdatedAtBeforeCreatedAt() {
    // 测试意图：验证 updatedAt 早于 createdAt 时抛出 IllegalArgumentException。
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ThreadJoin(
                    id(1),
                    VALID_HASH,
                    id(2),
                    id(3),
                    1L,
                    0L,
                    "agent",
                    10,
                    0L,
                    null,
                    null,
                    null,
                    T1,
                    T0));
    assertEquals("updatedAt precedes createdAt", ex.getMessage());
  }

  @Test
  void matchMethodLifecycle() {
    // 测试意图：验证 match 方法正常流转，以及对重复 match、idle 版本小于等于 afterVersion 与 null resultHead 的校验拦截。
    ThreadJoin join = initialJoin(id(1), id(2), id(3));

    UUID resultHead = id(50);
    ThreadJoin matched = join.match(2L, resultHead, T1);

    assertTrue(matched.matched());
    assertEquals(2L, matched.matchedIdleVersion());
    assertEquals(resultHead, matched.resultHeadEntryId());
    assertNull(matched.deliveryCommandSequence());
    assertEquals(T1, matched.updatedAt());
    assertEquals(join.invocationId(), matched.invocationId());
    assertEquals(join.requestHash(), matched.requestHash());
    assertEquals(join.parentThreadId(), matched.parentThreadId());
    assertEquals(join.childThreadId(), matched.childThreadId());
    assertEquals(join.sourceCommandSequence(), matched.sourceCommandSequence());
    assertEquals(join.afterVersion(), matched.afterVersion());
    assertEquals(join.agent(), matched.agent());
    assertEquals(join.maxTurns(), matched.maxTurns());
    assertEquals(join.reminderTurn(), matched.reminderTurn());
    assertEquals(join.createdAt(), matched.createdAt());

    // 重复 match 抛出异常
    assertThrows(IllegalArgumentException.class, () -> matched.match(3L, resultHead, T2));

    // idleVersion <= afterVersion（例如 afterVersion 为 0，传入 0 或 -1）
    assertThrows(IllegalArgumentException.class, () -> join.match(0L, resultHead, T1));
    assertThrows(IllegalArgumentException.class, () -> join.match(-1L, resultHead, T1));

    // resultHead 为 null
    assertThrows(NullPointerException.class, () -> join.match(2L, null, T1));
  }

  @Test
  void deliveredMethodLifecycle() {
    // 测试意图：验证 delivered 方法在已 match 状态下正常更新 deliveryCommandSequence 与 updatedAt，并在未 match 或已
    // delivered 时抛出异常。
    ThreadJoin initial = initialJoin(id(1), id(2), id(3));
    UUID resultHead = id(50);
    ThreadJoin matched = initial.match(2L, resultHead, T1);

    // 未 match 时直接 delivered 抛出异常
    assertThrows(IllegalArgumentException.class, () -> initial.delivered(10L, T1));

    // 正常 delivered
    ThreadJoin delivered = matched.delivered(10L, T2);
    assertEquals(10L, delivered.deliveryCommandSequence());
    assertEquals(T2, delivered.updatedAt());
    assertEquals(matched.matchedIdleVersion(), delivered.matchedIdleVersion());
    assertEquals(matched.resultHeadEntryId(), delivered.resultHeadEntryId());

    // 已 delivered 后再次调用 delivered 抛出异常
    assertThrows(IllegalArgumentException.class, () -> delivered.delivered(11L, T3));
  }

  @Test
  void remindMethodLifecycle() {
    // 测试意图：验证 remind 方法可递增 reminderTurn 并推进 updatedAt，且拒绝已 matched 或非递增 turn 的调用。
    ThreadJoin initial = initialJoin(id(1), id(2), id(3));

    ThreadJoin reminded1 = initial.remind(1L, T1);
    assertEquals(1L, reminded1.reminderTurn());
    assertEquals(T1, reminded1.updatedAt());
    assertFalse(reminded1.matched());
    assertNull(reminded1.matchedIdleVersion());
    assertNull(reminded1.resultHeadEntryId());
    assertNull(reminded1.deliveryCommandSequence());

    ThreadJoin reminded2 = reminded1.remind(3L, T2);
    assertEquals(3L, reminded2.reminderTurn());
    assertEquals(T2, reminded2.updatedAt());

    // turn <= reminderTurn 拒绝（等于当前值）
    assertThrows(IllegalArgumentException.class, () -> reminded2.remind(3L, T3));
    // turn <= reminderTurn 拒绝（小于当前值）
    assertThrows(IllegalArgumentException.class, () -> reminded2.remind(2L, T3));

    // 已 matched 时拒绝 remind
    ThreadJoin matched = reminded2.match(5L, id(99), T3);
    assertThrows(IllegalArgumentException.class, () -> matched.remind(4L, T3));
  }

  @Test
  void validateTransitionSuccess() {
    // 测试意图：验证 validateTransition 接受合法生命周期演进（初始->remind->match->delivered、无变更幂等校验以及 root ticket 演进）。
    ThreadJoin initial = initialJoin(id(1), id(2), id(3));

    // 初始 -> remind
    ThreadJoin reminded = initial.remind(1L, T1);
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(initial, reminded));

    // remind -> match
    ThreadJoin matched = reminded.match(2L, id(50), T2);
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(reminded, matched));

    // match -> delivered
    ThreadJoin delivered = matched.delivered(10L, T3);
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(matched, delivered));

    // 跨阶段演进：初始 -> delivered
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(initial, delivered));

    // 相同状态且 updatedAt 相同与推进（涵盖 matched 与 delivered 状态的幂等演进）
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(initial, initial));
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(matched, matched));
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(delivered, delivered));

    ThreadJoin deliveredAdvanceTime =
        new ThreadJoin(
            delivered.invocationId(),
            delivered.requestHash(),
            delivered.parentThreadId(),
            delivered.childThreadId(),
            delivered.sourceCommandSequence(),
            delivered.afterVersion(),
            delivered.agent(),
            delivered.maxTurns(),
            delivered.reminderTurn(),
            delivered.matchedIdleVersion(),
            delivered.resultHeadEntryId(),
            delivered.deliveryCommandSequence(),
            delivered.createdAt(),
            T3.plusSeconds(1));
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(delivered, deliveredAdvanceTime));

    // 相同状态且 updatedAt 推进
    ThreadJoin initialAdvanceTime =
        new ThreadJoin(
            initial.invocationId(),
            initial.requestHash(),
            initial.parentThreadId(),
            initial.childThreadId(),
            initial.sourceCommandSequence(),
            initial.afterVersion(),
            initial.agent(),
            initial.maxTurns(),
            initial.reminderTurn(),
            initial.matchedIdleVersion(),
            initial.resultHeadEntryId(),
            initial.deliveryCommandSequence(),
            initial.createdAt(),
            T2);
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(initial, initialAdvanceTime));

    // root ticket（parentThreadId 为 null，maxTurns 为 null）生命周期演进
    ThreadJoin rootInitial =
        new ThreadJoin(
            id(100),
            VALID_HASH,
            null,
            id(101),
            1L,
            0L,
            "root-agent",
            null,
            0L,
            null,
            null,
            null,
            T0,
            T0);
    ThreadJoin rootReminded = rootInitial.remind(1L, T1);
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(rootInitial, rootReminded));
    ThreadJoin rootMatched = rootReminded.match(2L, id(102), T2);
    assertDoesNotThrow(() -> ThreadJoin.validateTransition(rootReminded, rootMatched));
  }

  @Test
  void validateTransitionRejectsNulls() {
    // 测试意图：验证 validateTransition 在入参 old 或 next 为 null 时抛出 NullPointerException。
    ThreadJoin join = initialJoin(id(1), id(2), id(3));
    assertThrows(NullPointerException.class, () -> ThreadJoin.validateTransition(null, join));
    assertThrows(NullPointerException.class, () -> ThreadJoin.validateTransition(join, null));
  }

  @Test
  void validateTransitionRejectsIdentityMutation() {
    // 测试意图：验证 validateTransition 严格拒绝不可变 identity
    // 与创建时配置（invocationId、requestHash、parent/child、commandSequence、afterVersion、agent、maxTurns、createdAt）的任何变更。
    ThreadJoin old = initialJoin(id(1), id(2), id(3));

    // invocationId 篡改
    ThreadJoin mutateInvocation =
        new ThreadJoin(
            id(999),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(old, mutateInvocation));

    // requestHash 篡改
    ThreadJoin mutateHash =
        new ThreadJoin(
            old.invocationId(),
            "f".repeat(64),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(old, mutateHash));

    // parentThreadId 篡改（非空 -> null）
    ThreadJoin mutateParentToNull =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            null,
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(old, mutateParentToNull));

    // parentThreadId 篡改（null -> 非空）
    ThreadJoin rootOld =
        new ThreadJoin(
            id(10), VALID_HASH, null, id(11), 1L, 0L, "agent", null, 0L, null, null, null, T0, T0);
    ThreadJoin mutateParentFromNull =
        new ThreadJoin(
            rootOld.invocationId(),
            rootOld.requestHash(),
            id(99),
            rootOld.childThreadId(),
            rootOld.sourceCommandSequence(),
            rootOld.afterVersion(),
            rootOld.agent(),
            rootOld.maxTurns(),
            rootOld.reminderTurn(),
            null,
            null,
            null,
            rootOld.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(rootOld, mutateParentFromNull));

    // parentThreadId 篡改（不同非空 UUID）
    ThreadJoin mutateParentDiff =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            id(999),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(old, mutateParentDiff));

    // childThreadId 篡改
    ThreadJoin mutateChild =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            id(999),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(old, mutateChild));

    // sourceCommandSequence 篡改
    ThreadJoin mutateSeq =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            2L,
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(old, mutateSeq));

    // afterVersion 篡改
    ThreadJoin mutateAfterVersion =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            1L,
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(old, mutateAfterVersion));

    // agent 篡改
    ThreadJoin mutateAgent =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            "other-agent",
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(old, mutateAgent));

    // maxTurns 篡改（非空 -> null）
    ThreadJoin mutateMaxTurnsToNull =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            null,
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(old, mutateMaxTurnsToNull));

    // maxTurns 篡改（null -> 非空）
    ThreadJoin mutateMaxTurnsFromNull =
        new ThreadJoin(
            rootOld.invocationId(),
            rootOld.requestHash(),
            rootOld.parentThreadId(),
            rootOld.childThreadId(),
            rootOld.sourceCommandSequence(),
            rootOld.afterVersion(),
            rootOld.agent(),
            5,
            rootOld.reminderTurn(),
            null,
            null,
            null,
            rootOld.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(rootOld, mutateMaxTurnsFromNull));

    // maxTurns 篡改（不同非空数值）
    ThreadJoin mutateMaxTurnsDiff =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            20,
            old.reminderTurn(),
            null,
            null,
            null,
            old.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(old, mutateMaxTurnsDiff));

    // createdAt 篡改
    ThreadJoin mutateCreatedAt =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            T1,
            T1);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(old, mutateCreatedAt));
  }

  @Test
  void validateTransitionRejectsTimeAndReminderRegressions() {
    // 测试意图：验证 validateTransition 拒绝 updatedAt 回退以及 reminderTurn 倒退。
    ThreadJoin old = initialJoin(id(1), id(2), id(3));
    ThreadJoin oldAdvancedTime =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            T0,
            T2);

    // updatedAt 回退（T1 早于 oldAdvancedTime 的 updatedAt T2）
    ThreadJoin timeRegression =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            old.reminderTurn(),
            null,
            null,
            null,
            T0,
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(oldAdvancedTime, timeRegression));

    // reminderTurn 倒退（从 5 变 4）
    ThreadJoin oldReminded = old.remind(5L, T1);
    ThreadJoin reminderRegression =
        new ThreadJoin(
            old.invocationId(),
            old.requestHash(),
            old.parentThreadId(),
            old.childThreadId(),
            old.sourceCommandSequence(),
            old.afterVersion(),
            old.agent(),
            old.maxTurns(),
            4L,
            null,
            null,
            null,
            old.createdAt(),
            T2);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(oldReminded, reminderRegression));
  }

  @Test
  void validateTransitionRejectsFrozenReceiptMutation() {
    // 测试意图：验证已匹配（matched）后的 matchedIdleVersion 与 resultHeadEntryId 冻结不可变、matched 状态下不能再推进
    // reminderTurn、以及已投递的 deliveryCommandSequence 不可被篡改。
    ThreadJoin old = initialJoin(id(1), id(2), id(3));
    UUID resultHead = id(50);
    ThreadJoin matched = old.match(2L, resultHead, T1);

    // 篡改 matchedIdleVersion
    ThreadJoin mutateIdleVersion =
        new ThreadJoin(
            matched.invocationId(),
            matched.requestHash(),
            matched.parentThreadId(),
            matched.childThreadId(),
            matched.sourceCommandSequence(),
            matched.afterVersion(),
            matched.agent(),
            matched.maxTurns(),
            matched.reminderTurn(),
            3L,
            matched.resultHeadEntryId(),
            null,
            matched.createdAt(),
            T2);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(matched, mutateIdleVersion));

    // 篡改 resultHeadEntryId
    ThreadJoin mutateHead =
        new ThreadJoin(
            matched.invocationId(),
            matched.requestHash(),
            matched.parentThreadId(),
            matched.childThreadId(),
            matched.sourceCommandSequence(),
            matched.afterVersion(),
            matched.agent(),
            matched.maxTurns(),
            matched.reminderTurn(),
            matched.matchedIdleVersion(),
            id(51),
            null,
            matched.createdAt(),
            T2);
    assertThrows(
        IllegalArgumentException.class, () -> ThreadJoin.validateTransition(matched, mutateHead));

    // matched 后尝试推进 reminderTurn
    ThreadJoin advanceReminderAfterMatched =
        new ThreadJoin(
            matched.invocationId(),
            matched.requestHash(),
            matched.parentThreadId(),
            matched.childThreadId(),
            matched.sourceCommandSequence(),
            matched.afterVersion(),
            matched.agent(),
            matched.maxTurns(),
            1L,
            matched.matchedIdleVersion(),
            matched.resultHeadEntryId(),
            null,
            matched.createdAt(),
            T2);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(matched, advanceReminderAfterMatched));

    // delivered 之后篡改 deliveryCommandSequence（改成不同值）
    ThreadJoin delivered = matched.delivered(10L, T2);
    ThreadJoin mutateDeliverySeq =
        new ThreadJoin(
            delivered.invocationId(),
            delivered.requestHash(),
            delivered.parentThreadId(),
            delivered.childThreadId(),
            delivered.sourceCommandSequence(),
            delivered.afterVersion(),
            delivered.agent(),
            delivered.maxTurns(),
            delivered.reminderTurn(),
            delivered.matchedIdleVersion(),
            delivered.resultHeadEntryId(),
            11L,
            delivered.createdAt(),
            T3);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(delivered, mutateDeliverySeq));

    // delivered 之后将 deliveryCommandSequence 改成 null
    ThreadJoin clearDeliverySeq =
        new ThreadJoin(
            delivered.invocationId(),
            delivered.requestHash(),
            delivered.parentThreadId(),
            delivered.childThreadId(),
            delivered.sourceCommandSequence(),
            delivered.afterVersion(),
            delivered.agent(),
            delivered.maxTurns(),
            delivered.reminderTurn(),
            delivered.matchedIdleVersion(),
            delivered.resultHeadEntryId(),
            null,
            delivered.createdAt(),
            T3);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadJoin.validateTransition(delivered, clearDeliverySeq));
  }

  @Test
  void equalsAndHashCodeAndToStringContract() {
    // 测试意图：验证 Record 自动生成的 equals、hashCode 与 toString 符合标准约定。
    ThreadJoin join1 = initialJoin(id(1), id(2), id(3));
    ThreadJoin join2 = initialJoin(id(1), id(2), id(3));
    ThreadJoin diff = initialJoin(id(9), id(2), id(3));

    assertEquals(join1, join2);
    assertEquals(join1.hashCode(), join2.hashCode());
    assertNotEquals(join1, diff);
    assertNotEquals(join1, null);
    assertNotEquals(join1, "other");
    assertNotNull(join1.toString());
  }
}
