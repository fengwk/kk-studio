package fun.fengwk.kkstudio.harness.runtime.thread;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** ThreadState durable 字段与不变量校验。 */
class ThreadStateTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final UUID SESSION_ID = id(1_000_000L);

  @Test
  void acceptsValidDurableFields() {
    // 测试意图：验证合法的 ThreadState 根线程（parentThreadId 为 null）与子线程（parentThreadId 非空）能正常构造并暴露字段。
    ThreadState rootState =
        state(id(7), null, id(42), true, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);

    assertEquals(id(7), rootState.id());
    assertEquals(SESSION_ID, rootState.sessionId());
    assertNull(rootState.parentThreadId());
    assertEquals(id(42), rootState.headEntryId());
    assertEquals(CREATION_REQUEST_HASH, rootState.creationRequestHash());
    assertEquals("main", rootState.name());
    assertTrue(rootState.yoloPolicy().isEnabled());
    assertEquals(ThreadExecutionControl.RUNNABLE, rootState.executionControl());
    assertEquals(3L, rootState.nextCommandSequence());
    assertEquals(5L, rootState.version());
    assertEquals(CREATED, rootState.createdAt());
    assertEquals(CREATED, rootState.updatedAt());

    assertEquals(0L, rootState.inputThroughSequence());
    assertTrue(rootState.executionControl().isRunnable());
    assertFalse(rootState.executionControl().isStopped());

    ThreadState childState =
        state(
            id(8),
            id(7),
            id(43),
            false,
            ThreadExecutionControl.RUNNABLE,
            1L,
            0L,
            CREATED.plusSeconds(2));
    assertEquals(id(7), childState.parentThreadId());
    // 子线程恒为 FOLLOW 执行根，没有独立开关，因此 isEnabled 为 false。
    assertFalse(childState.yoloPolicy().isEnabled());
    assertEquals(ThreadExecutionControl.RUNNABLE, childState.executionControl());
    assertTrue(childState.executionControl().isRunnable());
  }

  @Test
  void rejectsInvalidIdentityAndSequence() {
    // 测试意图：验证不可变 identity 字段校验，包括 null 拒绝、parent 指向自身拒绝以及 sequence/version 下界约束。
    assertThrows(
        IllegalArgumentException.class, () -> state(id(7), id(42), false, 0L, 0L, CREATED));
    assertThrows(
        IllegalArgumentException.class, () -> state(id(7), id(42), false, 1L, -1L, CREATED));
    assertThrows(NullPointerException.class, () -> state(null, id(42), false, 1L, 0L, CREATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadState(
                id(7),
                null,
                null,
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                ThreadYoloPolicy.root(false),
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
    assertThrows(NullPointerException.class, () -> state(id(7), null, false, 1L, 0L, CREATED));
    // 拒绝 parentThreadId 指向自身
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                id(7),
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                ThreadYoloPolicy.follow(id(7)),
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
    // 拒绝 status 为 null
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                null,
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                ThreadYoloPolicy.root(false),
                null,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
    // 拒绝非法的 creationRequestHash
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                null,
                id(42),
                null,
                "main",
                ThreadYoloPolicy.root(false),
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                null,
                id(42),
                "invalid-hash",
                "main",
                ThreadYoloPolicy.root(false),
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
  }

  @Test
  void rejectsInvalidTimestamps() {
    // 测试意图：验证时间戳不可空且 updatedAt 不得早于 createdAt。
    assertThrows(
        NullPointerException.class, () -> state(id(7), id(42), false, 1L, 0L, (Instant) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> state(id(7), id(42), false, 1L, 0L, CREATED.minusSeconds(1)));
  }

  @Test
  void canonicalConstructorEstablishesCanonicalState() {
    // 测试意图：验证规范全参构造器正确设置初始值（sequence=1, version=0, createdAt=updatedAt=now）。
    Instant now = CREATED.plusSeconds(10);
    ThreadState initialViaConstructor =
        new ThreadState(
            id(101),
            SESSION_ID,
            id(7),
            id(42),
            CREATION_REQUEST_HASH,
            "child-branch",
            ThreadYoloPolicy.follow(id(7)),
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0L,
            now,
            now);

    assertEquals(id(101), initialViaConstructor.id());
    assertEquals(SESSION_ID, initialViaConstructor.sessionId());
    assertEquals(id(7), initialViaConstructor.parentThreadId());
    assertEquals(id(42), initialViaConstructor.headEntryId());
    assertEquals(CREATION_REQUEST_HASH, initialViaConstructor.creationRequestHash());
    assertEquals("child-branch", initialViaConstructor.name());
    assertEquals(ThreadYoloPolicy.follow(id(7)), initialViaConstructor.yoloPolicy());
    assertEquals(ThreadExecutionControl.RUNNABLE, initialViaConstructor.executionControl());
    assertEquals(1L, initialViaConstructor.nextCommandSequence());
    assertEquals(0L, initialViaConstructor.version());
    assertEquals(now, initialViaConstructor.createdAt());
    assertEquals(now, initialViaConstructor.updatedAt());

    ThreadState initialRoot =
        new ThreadState(
            id(102),
            SESSION_ID,
            null,
            id(43),
            CREATION_REQUEST_HASH,
            "root-thread",
            ThreadYoloPolicy.root(false),
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0L,
            now,
            now);

    assertEquals(id(102), initialRoot.id());
    assertNull(initialRoot.parentThreadId());
    assertEquals(ThreadExecutionControl.RUNNABLE, initialRoot.executionControl());
    assertEquals(1L, initialRoot.nextCommandSequence());
    assertEquals(0L, initialRoot.version());
    assertEquals(now, initialRoot.createdAt());
    assertEquals(now, initialRoot.updatedAt());
  }

  @Test
  void changeLifecycleStatusBumpsVersionByOneAndPreservesFields() {
    // 测试意图：验证 changeLifecycleStatus 转换方法将 status 更新、version 严格 +1、并完整保留 parent 与其他 durable 字段。
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);

    ThreadState idle =
        stored.changeExecutionControl(ThreadExecutionControl.RUNNABLE, CREATED.plusSeconds(1));
    assertEquals(ThreadExecutionControl.RUNNABLE, idle.executionControl());
    assertEquals(6L, idle.version());
    assertEquals(stored.id(), idle.id());
    assertEquals(stored.sessionId(), idle.sessionId());
    assertEquals(id(100), idle.parentThreadId());
    assertEquals(stored.headEntryId(), idle.headEntryId());
    assertEquals(stored.creationRequestHash(), idle.creationRequestHash());
    assertEquals(stored.name(), idle.name());
    assertEquals(stored.yoloPolicy(), idle.yoloPolicy());
    assertEquals(stored.nextCommandSequence(), idle.nextCommandSequence());
    assertEquals(stored.createdAt(), idle.createdAt());
    assertEquals(CREATED.plusSeconds(1), idle.updatedAt());

    ThreadState stopped =
        idle.changeExecutionControl(ThreadExecutionControl.STOPPED, CREATED.plusSeconds(2));
    assertEquals(ThreadExecutionControl.STOPPED, stopped.executionControl());
    assertEquals(7L, stopped.version());
    assertEquals(id(100), stopped.parentThreadId());

    // 拒绝传入 null status
    assertThrows(
        NullPointerException.class,
        () -> stored.changeExecutionControl(null, CREATED.plusSeconds(1)));
  }

  @Test
  void reserveCommandSequencesAdvancesBatchAndVersionByOne() {
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    ThreadState next = stored.reserveCommandSequences(3, CREATED.plusSeconds(1));
    assertEquals(6L, next.nextCommandSequence());
    assertEquals(6L, next.version());
    assertEquals(CREATED.plusSeconds(1), next.updatedAt());
    assertEquals(stored.headEntryId(), next.headEntryId());
    assertEquals(stored.yoloPolicy(), next.yoloPolicy());
    assertEquals(stored.parentThreadId(), next.parentThreadId());
    assertEquals(stored.executionControl(), next.executionControl());
    // 单个 sequence 等价于 count=1
    ThreadState single = stored.reserveCommandSequences(1, CREATED.plusSeconds(1));
    assertEquals(4L, single.nextCommandSequence());
    assertEquals(6L, single.version());
    assertEquals(stored.parentThreadId(), single.parentThreadId());
    assertEquals(stored.executionControl(), single.executionControl());
  }

  @Test
  void reserveCommandSequencesRejectsNonPositiveCountAndOverflow() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    assertThrows(IllegalArgumentException.class, () -> stored.reserveCommandSequences(0, CREATED));
    assertThrows(IllegalArgumentException.class, () -> stored.reserveCommandSequences(-1, CREATED));
    assertThrows(
        ArithmeticException.class,
        () ->
            state(id(7), id(42), false, Long.MAX_VALUE, 5L, CREATED)
                .reserveCommandSequences(2, CREATED));
  }

  @Test
  void advanceHeadPreservesYoloPolicyAndBumpsVersionByOne() {
    ThreadState stored =
        state(id(7), id(100), id(42), true, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    // head 推进恒保留当前 yolo policy（不再接受外部传入值，杜绝 terminal / resolver 路径写回过期策略），仅 bump 一次 version。
    ThreadState head = stored.advanceHead(id(99), CREATED.plusSeconds(2));
    assertEquals(id(99), head.headEntryId());
    // 子线程跟随执行根：推进 head 必须完整保留 FOLLOW policy。
    assertEquals(stored.yoloPolicy(), head.yoloPolicy());
    assertEquals(6L, head.version());
    assertEquals(3L, head.nextCommandSequence());
    assertEquals(stored.parentThreadId(), head.parentThreadId());
    assertEquals(stored.executionControl(), head.executionControl());
    // 再次推进同样保留 policy，不因显式传入而改写。
    ThreadState advanced = head.advanceHead(id(99), CREATED.plusSeconds(3));
    assertEquals(head.yoloPolicy(), advanced.yoloPolicy());
    assertEquals(7L, advanced.version());
    assertEquals(id(99), advanced.headEntryId());
    assertEquals(3L, advanced.nextCommandSequence());
    assertEquals(stored.parentThreadId(), advanced.parentThreadId());
    assertEquals(stored.executionControl(), advanced.executionControl());
    // false 值同样被保留。
    ThreadState storedDisabled = state(id(7), id(42), false, 3L, 5L, CREATED);
    ThreadState advancedDisabled = storedDisabled.advanceHead(id(99), CREATED.plusSeconds(2));
    assertFalse(advancedDisabled.yoloPolicy().isEnabled());
    assertEquals(6L, advancedDisabled.version());
    assertEquals(id(99), advancedDisabled.headEntryId());
    assertEquals(storedDisabled.parentThreadId(), advancedDisabled.parentThreadId());
    assertEquals(storedDisabled.executionControl(), advancedDisabled.executionControl());
  }

  @Test
  void setRootYoloBumpsVersionExactlyOnceAndPreservesCursor() {
    // setRootYolo 只作用于执行根（parentThreadId 为 null），因此 fixture 使用根线程。
    ThreadState stored =
        state(id(7), null, id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    ThreadState enabled = stored.setRootYolo(true, CREATED.plusSeconds(2));
    assertTrue(enabled.yoloPolicy().isEnabled());
    assertEquals(6L, enabled.version());
    assertEquals(id(42), enabled.headEntryId());
    assertEquals(3L, enabled.nextCommandSequence());
    assertEquals(stored.parentThreadId(), enabled.parentThreadId());
    assertEquals(stored.executionControl(), enabled.executionControl());
    // 再次切换同样精确 +1；时间钳制与其它转换一致。
    ThreadState disabled = enabled.setRootYolo(false, CREATED.plusSeconds(2));
    assertFalse(disabled.yoloPolicy().isEnabled());
    assertEquals(7L, disabled.version());
    assertEquals(CREATED.plusSeconds(2), enabled.updatedAt());
    assertEquals(CREATED.plusSeconds(2), disabled.updatedAt());
    assertEquals(stored.parentThreadId(), disabled.parentThreadId());
    assertEquals(stored.executionControl(), disabled.executionControl());
  }

  @Test
  void transitionMethodsClampWallClockRollbackToCurrentUpdatedAt() {
    Instant durableNow = CREATED.plusSeconds(2);
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, durableNow);

    // Caller wall-clock 回拨时，纯转换保留 durable 时间下界；version/sequence/head 语义照常推进。
    assertEquals(durableNow, stored.reserveCommandSequences(1, CREATED).updatedAt());
    assertEquals(durableNow, stored.advanceHead(id(99), CREATED).updatedAt());
    assertEquals(durableNow, stored.touchVersion(CREATED).updatedAt());
    assertEquals(durableNow, stored.setRootYolo(true, CREATED).updatedAt());
    assertEquals(durableNow, stored.renameThread("new name", CREATED).updatedAt());
    assertEquals(
        durableNow,
        stored.changeExecutionControl(ThreadExecutionControl.RUNNABLE, CREATED).updatedAt());
  }

  @Test
  void renameThreadNormalizesNameAndBumpsVersionExactlyOnce() {
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    ThreadState renamed = stored.renameThread("  新 名字  ", CREATED.plusSeconds(3));
    // 名称规范化折叠为单行；version 精确 +1；head/sequence/yolo/parent/status/createdAt 不变。
    assertEquals("新 名字", renamed.name());
    assertEquals(6L, renamed.version());
    assertEquals(stored.headEntryId(), renamed.headEntryId());
    assertEquals(stored.nextCommandSequence(), renamed.nextCommandSequence());
    assertEquals(stored.yoloPolicy(), renamed.yoloPolicy());
    assertEquals(stored.parentThreadId(), renamed.parentThreadId());
    assertEquals(stored.executionControl(), renamed.executionControl());
    assertEquals(stored.createdAt(), renamed.createdAt());
    // renamed 是同一 stored 行的合法迁移。
    ThreadState.validateTransition(stored, renamed);
    // rename 后对 renamed 行再做一次同名 rename 属于新迁移（由控制面负责 no-op），version 照常严格 +1。
    ThreadState sameName = renamed.renameThread("新 名字", CREATED.plusSeconds(3));
    assertEquals(7L, sameName.version());
    assertEquals("新 名字", sameName.name());
  }

  @Test
  void renameThreadRejectsOverlongName() {
    // Thread 名称手工上限 256 码点：rename 超长直接抛 IllegalArgumentException（绝不截断）。
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    assertThrows(
        IllegalArgumentException.class,
        () -> stored.renameThread("x".repeat(257), CREATED.plusSeconds(1)));
    // 恰好 256 码点可接受。
    assertEquals(
        "x".repeat(256), stored.renameThread("x".repeat(256), CREATED.plusSeconds(1)).name());
  }

  @Test
  void touchVersionBumpsVersionExactlyOnceAndPreservesFields() {
    // 测试意图：验证 touchVersion 仅递增 version 并保留 parentThreadId 与 status。
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    ThreadState touched = stored.touchVersion(CREATED.plusSeconds(1));
    assertEquals(6L, touched.version());
    assertEquals(stored.parentThreadId(), touched.parentThreadId());
    assertEquals(stored.executionControl(), touched.executionControl());
    assertEquals(stored.headEntryId(), touched.headEntryId());
    assertEquals(stored.nextCommandSequence(), touched.nextCommandSequence());
  }

  @Test
  void validateTransitionAcceptsExactReplayAndRejectsIdentityRegression() {
    // 测试意图：验证 validateTransition 接受完全重放，并拒绝任何 identity 变更（包括 parentThreadId 不可变）。
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    ThreadState.validateTransition(stored, stored);

    // 拒绝 id 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(8),
                    id(100),
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    6L,
                    CREATED)));
    // 拒绝 sessionId 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(7),
                    id(999_999L),
                    id(100),
                    id(42),
                    CREATION_REQUEST_HASH,
                    "main",
                    ThreadYoloPolicy.follow(id(100)),
                    ThreadExecutionControl.RUNNABLE,
                    0L,
                    3L,
                    6L,
                    CREATED,
                    CREATED.plusSeconds(1))));
    // 拒绝 parentThreadId 改变（从非空变空、从空变非空、变其他 parent）
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    6L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    id(101),
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    6L,
                    CREATED.plusSeconds(1))));
    ThreadState rootStored =
        state(id(7), null, id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                rootStored,
                state(
                    id(7),
                    id(100),
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    6L,
                    CREATED.plusSeconds(1))));
    // 拒绝 creationRequestHash 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(7),
                    SESSION_ID,
                    id(100),
                    id(42),
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    "main",
                    ThreadYoloPolicy.follow(id(100)),
                    ThreadExecutionControl.RUNNABLE,
                    0L,
                    3L,
                    6L,
                    CREATED,
                    CREATED.plusSeconds(1))));
    // 拒绝 createdAt 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(7),
                    SESSION_ID,
                    id(100),
                    id(42),
                    CREATION_REQUEST_HASH,
                    "main",
                    ThreadYoloPolicy.follow(id(100)),
                    ThreadExecutionControl.RUNNABLE,
                    0L,
                    3L,
                    6L,
                    CREATED.plusSeconds(1),
                    CREATED.plusSeconds(1))));
  }

  @Test
  void validateTransitionRejectsSequenceVersionAndUpdatedAtRegression() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED.plusSeconds(2));
    // nextCommandSequence 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 2L, 6L, CREATED.plusSeconds(3))));
    // version 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 3L, 4L, CREATED.plusSeconds(3))));
    // updatedAt 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 3L, 6L, CREATED.plusSeconds(1))));
  }

  @Test
  void validateTransitionRequiresExactVersionIncrementOnAnyChange() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    // 任何对外可见的变更都必须将 version 恰好 bump 一次
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    true,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    4L,
                    5L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(43),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    7L,
                    CREATED.plusSeconds(1))));
    // status 改变但 version 未 bump 时拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    // 即便仅修改 updatedAt，仍必须 bump version
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadExecutionControl.RUNNABLE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    // status 改变且 version 严格 +1 允许通过
    ThreadState.validateTransition(
        stored,
        state(
            id(7),
            null,
            id(42),
            false,
            ThreadExecutionControl.RUNNABLE,
            3L,
            6L,
            CREATED.plusSeconds(1)));
    ThreadState.validateTransition(
        stored,
        state(
            id(7),
            null,
            id(42),
            false,
            ThreadExecutionControl.RUNNABLE,
            3L,
            6L,
            CREATED.plusSeconds(1)));
  }

  /** 根/子的 YOLO 策略 shape 在构造期强约束：根不可 FOLLOW；子必须 FOLLOW 执行根且不可 follow 自身。 */
  @Test
  void rejectsYoloPolicyShapeViolations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                null,
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                ThreadYoloPolicy.follow(id(9)),
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
    // 子线程必须 FOLLOW，不能自带根开关
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                id(9),
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                ThreadYoloPolicy.root(true),
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
    // 子线程不可 follow 自身（与 parent-not-self 相互独立的第二道约束）
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                id(9),
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                ThreadYoloPolicy.follow(id(7)),
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                CREATED,
                CREATED));
    // 非 FOLLOW 模式不得携带目标，FOLLOW 必须有目标
    assertThrows(
        IllegalArgumentException.class, () -> new ThreadYoloPolicy(ThreadYoloMode.ENABLE, id(9)));
    assertThrows(
        IllegalArgumentException.class, () -> new ThreadYoloPolicy(ThreadYoloMode.DISABLE, id(9)));
    assertThrows(NullPointerException.class, () -> ThreadYoloPolicy.follow(null));
  }

  /** 子代理的 FOLLOW 目标创建后不可变：validateTransition 拒绝换根，即使 version 严格 +1；同目标推进仍允许。 */
  @Test
  void validateTransitionRejectsMutatingChildFollowPolicy() {
    ThreadState stored =
        state(id(8), id(7), id(43), false, ThreadExecutionControl.RUNNABLE, 1L, 5L, CREATED);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(8),
                    SESSION_ID,
                    id(7),
                    id(43),
                    CREATION_REQUEST_HASH,
                    "main",
                    ThreadYoloPolicy.follow(id(100)),
                    ThreadExecutionControl.RUNNABLE,
                    0L,
                    1L,
                    6L,
                    CREATED,
                    CREATED.plusSeconds(1))));

    ThreadState.validateTransition(
        stored,
        new ThreadState(
            id(8),
            SESSION_ID,
            id(7),
            id(43),
            CREATION_REQUEST_HASH,
            "main",
            ThreadYoloPolicy.follow(id(7)),
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            6L,
            CREATED,
            CREATED.plusSeconds(1)));
  }

  /** 只有执行根拥有 YOLO 开关：子线程调用 setRootYolo 必须拒绝，根调用仅切换自身 ENABLE/DISABLE 且 version +1。 */
  @Test
  void childThreadCannotOwnYoloSwitch() {
    ThreadState child =
        state(id(8), id(7), id(43), false, ThreadExecutionControl.RUNNABLE, 1L, 5L, CREATED);
    assertThrows(
        IllegalArgumentException.class, () -> child.setRootYolo(true, CREATED.plusSeconds(1)));

    ThreadState root =
        state(id(7), null, id(42), false, ThreadExecutionControl.RUNNABLE, 3L, 5L, CREATED);
    ThreadState enabled = root.setRootYolo(true, CREATED.plusSeconds(1));
    assertTrue(enabled.yoloPolicy().isEnabled());
    assertFalse(enabled.yoloPolicy().isFollow());
    assertEquals(6L, enabled.version());
    assertEquals(root.headEntryId(), enabled.headEntryId());
    assertEquals(root.nextCommandSequence(), enabled.nextCommandSequence());
  }

  private static ThreadState state(
      UUID id,
      UUID headEntryId,
      boolean yoloEnabled,
      long nextCommandSequence,
      long version,
      Instant updatedAt) {
    return state(
        id,
        null,
        headEntryId,
        yoloEnabled,
        ThreadExecutionControl.RUNNABLE,
        nextCommandSequence,
        version,
        updatedAt);
  }

  private static ThreadState state(
      UUID id,
      UUID parentThreadId,
      UUID headEntryId,
      boolean yoloEnabled,
      ThreadExecutionControl executionControl,
      long nextCommandSequence,
      long version,
      Instant updatedAt) {
    // 根线程使用独立开关，子线程恒 FOLLOW 传入的执行根（fixture 的 parent 即执行根）。
    ThreadYoloPolicy yoloPolicy =
        parentThreadId == null
            ? ThreadYoloPolicy.root(yoloEnabled)
            : ThreadYoloPolicy.follow(parentThreadId);
    return new ThreadState(
        id,
        SESSION_ID,
        parentThreadId,
        headEntryId,
        CREATION_REQUEST_HASH,
        "main",
        yoloPolicy,
        executionControl,
        0L,
        nextCommandSequence,
        version,
        CREATED,
        updatedAt);
  }
}
