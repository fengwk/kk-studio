package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.Names;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 持久化 Thread 当前状态。
 *
 * <p>持久化 Thread 自身拥有的字段：所属 Session、不可变父 Thread、creation request hash 身份键、head Entry cursor、Thread
 * 显示名称、Thread YOLO runtime policy（{@link ThreadYoloPolicy}）、执行控制（{@link
 * ThreadExecutionControl}）、已被普通 INPUT 接纳的输入水位 {@code inputThroughSequence}（初值 0）、下一条 Command
 * sequence 以及对外可见的 snapshot version。{@code sessionId}、{@code parentThreadId}、{@code
 * creationRequestHash} 与 {@code createdAt} 创建后不可变； environment、open turn、execution epoch 与
 * processor lease 刻意省略，settings 事实从 {@code headEntryId} 处的 Entry 分支派生。
 *
 * <p>{@code parentThreadId} 是不可变父 Thread UUID，建立执行关系树；根 Thread 为 {@code null}，不可指向自身。根 Thread 的
 * YOLO 策略是 {@code ENABLE} / {@code DISABLE} 且没有目标；子代理恒为 {@code
 * FOLLOW(rootThreadId)}，直接指向不可变执行根。执行控制只区分 {@code RUNNABLE / STOPPED}，不再维护递归的
 * IDLE/ACTIVE/WAITING_CHILDREN。
 *
 * <p>{@code inputThroughSequence} 是「已被普通 INPUT 接纳或确定性拒绝」的序号水位：它随历史、Command 应用坐标与 Invocation 在同
 * 一事务推进，不因 Stop、压缩或模型重试而倒退。{@code nextCommandSequence} 保留为下一条待分配 Command sequence。
 *
 * <p>所有 Thread 行变更都通过下方纯转换方法执行；转换会把回拨的调用方 wall-clock 抬升到当前 {@code updatedAt}，并把 {@code version} 严格
 * +1。该版本是 Thread 结构与控制状态的 CAS / invalidation cursor，不是完整快照的内容版本：ModelInvocation 的高频流式 checkpoint
 * 可在同一 Thread version 内推进。Store 仍必须在每次 {@code updateThread} 写入前调用 {@link
 * #validateTransition}，严格拒绝直接构造的时间回退。
 */
public record ThreadState(
    UUID id,
    UUID sessionId,
    UUID parentThreadId,
    UUID headEntryId,
    String creationRequestHash,
    String name,
    ThreadYoloPolicy yoloPolicy,
    ThreadExecutionControl executionControl,
    long inputThroughSequence,
    long nextCommandSequence,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  private static final Pattern CREATION_REQUEST_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public ThreadState {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sessionId, "sessionId");
    if (parentThreadId != null && parentThreadId.equals(id)) {
      throw new IllegalArgumentException("parentThreadId must not equal id");
    }
    Objects.requireNonNull(headEntryId, "headEntryId");
    if (creationRequestHash == null
        || !CREATION_REQUEST_HASH_PATTERN.matcher(creationRequestHash).matches()) {
      throw new IllegalArgumentException(
          "creationRequestHash must be 64 lowercase hexadecimal characters");
    }
    name = Names.normalize(name);
    Objects.requireNonNull(yoloPolicy, "yoloPolicy");
    if (parentThreadId == null) {
      if (yoloPolicy.isFollow()) {
        throw new IllegalArgumentException("root thread must not follow another thread");
      }
    } else {
      if (!yoloPolicy.isFollow()) {
        throw new IllegalArgumentException("child thread must follow its execution root");
      }
      if (yoloPolicy.rootThreadId().equals(id)) {
        throw new IllegalArgumentException("thread must not follow itself");
      }
    }
    Objects.requireNonNull(executionControl, "executionControl");
    if (inputThroughSequence < 0) {
      throw new IllegalArgumentException("inputThroughSequence must not be negative");
    }
    if (inputThroughSequence >= nextCommandSequence) {
      throw new IllegalArgumentException(
          "inputThroughSequence must be less than nextCommandSequence");
    }
    if (nextCommandSequence < 1) {
      throw new IllegalArgumentException("nextCommandSequence must start at 1");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * 校验 {@code next} 是存储行 {@code stored} 的合法迁移：identity（id / sessionId / parentThreadId /
   * creationRequestHash / createdAt）不可变，子代理的 {@link ThreadYoloMode#FOLLOW} 策略（含不可变 Follow 目标）
   * 不可改变，{@code headEntryId} / {@code inputThroughSequence} / {@code nextCommandSequence} / {@code
   * version} / {@code updatedAt} 不允许回退，任何 Thread 行变更都会把 {@code version} 严格 +1。exact replay 一律被接受。
   */
  public static void validateTransition(ThreadState stored, ThreadState next) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(next, "next");
    if (stored.equals(next)) {
      return;
    }
    if (!stored.id().equals(next.id())) {
      throw new IllegalArgumentException("thread id must not change");
    }
    if (!stored.sessionId().equals(next.sessionId())) {
      throw new IllegalArgumentException("thread sessionId must not change");
    }
    if (!Objects.equals(stored.parentThreadId(), next.parentThreadId())) {
      throw new IllegalArgumentException("thread parentThreadId must not change");
    }
    if (!stored.creationRequestHash().equals(next.creationRequestHash())) {
      throw new IllegalArgumentException("thread creationRequestHash must not change");
    }
    // 子代理的 Follow 目标创建后不可变：直接构造的 next 也不能换根、改模式或复制另一棵树的开关。
    if (stored.yoloPolicy().isFollow() && !stored.yoloPolicy().equals(next.yoloPolicy())) {
      throw new IllegalArgumentException("child thread yolo policy must not change");
    }
    if (!stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException("thread createdAt must not change");
    }
    if (next.inputThroughSequence() < stored.inputThroughSequence()) {
      throw new IllegalArgumentException("inputThroughSequence must not regress");
    }
    if (next.nextCommandSequence() < stored.nextCommandSequence()) {
      throw new IllegalArgumentException("nextCommandSequence must not regress");
    }
    if (next.updatedAt().isBefore(stored.updatedAt())) {
      throw new IllegalArgumentException("updatedAt must not regress");
    }
    if (next.version() != Math.addExact(stored.version(), 1L)) {
      throw new IllegalArgumentException(
          "any thread state change must bump version by exactly one");
    }
  }

  /**
   * 在一个原子步骤中预留 {@code count} 条 Command sequence：{@code nextCommandSequence} 前进 {@code count}，{@code
   * version} 严格 +1；{@code count} 必须为正。执行控制与输入水位保持不变。
   */
  public ThreadState reserveCommandSequences(int count, Instant now) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    return copy(
        headEntryId,
        executionControl,
        inputThroughSequence,
        Math.addExact(nextCommandSequence, (long) count),
        now);
  }

  /**
   * 在一次原子步骤中预留 {@code count} 条 Command sequence 并设置执行控制：仅显式新 USER/GOAL/CUSTOM 输入可把 STOPPED 的目标
   * Thread 恢复为 RUNNABLE，且必须在与命令写入同一 version 中完成；{@code count} 必须为正。
   */
  public ThreadState reserveCommandSequencesAndSetControl(
      int count, ThreadExecutionControl executionControl, Instant now) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    return copy(
        headEntryId,
        Objects.requireNonNull(executionControl, "executionControl"),
        inputThroughSequence,
        Math.addExact(nextCommandSequence, (long) count),
        now);
  }

  /**
   * 在一个原子步骤中推进 head Entry cursor，恒保留当前冻结的 YOLO runtime policy 与输入水位（不再接受外部传入值，杜绝 terminal /
   * resolver 提交路径写入过期策略）；{@code version} 严格 +1。
   */
  public ThreadState advanceHead(UUID headEntryId, Instant now) {
    return copy(
        Objects.requireNonNull(headEntryId, "headEntryId"),
        executionControl,
        inputThroughSequence,
        nextCommandSequence,
        now);
  }

  /**
   * 在一个原子步骤中同时推进 head Entry cursor 与输入水位：普通 INPUT 把已物化通知与 queued 输入的 cutoff、应用坐标、历史与 Invocation
   * 一起提交；{@code version} 严格 +1。
   */
  public ThreadState advanceHeadAndInputThroughSequence(
      UUID headEntryId, long inputThroughSequence, Instant now) {
    if (inputThroughSequence < this.inputThroughSequence) {
      throw new IllegalArgumentException("inputThroughSequence must not regress");
    }
    return copy(
        Objects.requireNonNull(headEntryId, "headEntryId"),
        executionControl,
        inputThroughSequence,
        nextCommandSequence,
        now);
  }

  /** 仅推进输入水位（确定性拒绝或纯配置推进），{@code version} 严格 +1。 */
  public ThreadState advanceInputThroughSequence(long inputThroughSequence, Instant now) {
    if (inputThroughSequence < this.inputThroughSequence) {
      throw new IllegalArgumentException("inputThroughSequence must not regress");
    }
    return copy(headEntryId, executionControl, inputThroughSequence, nextCommandSequence, now);
  }

  /**
   * 在同一 version 内预留 {@code count} 条 Command sequence 并推进 head：用于父 Thread 已停止时把系统通知直接固化到历史（命令与 head
   * 必须一致）。{@code count} 必须为正。
   */
  public ThreadState reserveCommandSequencesAndAdvanceHead(
      int count, UUID headEntryId, Instant now) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    return copy(
        Objects.requireNonNull(headEntryId, "headEntryId"),
        executionControl,
        inputThroughSequence,
        Math.addExact(nextCommandSequence, (long) count),
        now);
  }

  /**
   * 直接控制面更新执行控制（{@link ThreadExecutionControl}）：head / 水位 / nextCommandSequence / yoloPolicy / name
   * / parentThreadId 不变，{@code version} 严格 +1。调用方负责在 version CAS 之前先做「状态相同即 no-op」判断。
   */
  public ThreadState changeExecutionControl(ThreadExecutionControl executionControl, Instant now) {
    return copy(
        headEntryId,
        Objects.requireNonNull(executionControl, "executionControl"),
        inputThroughSequence,
        nextCommandSequence,
        now);
  }

  /**
   * 根控制面更新自身的 YOLO 开关：head / 水位 / nextCommandSequence 不变，{@code version} 严格 +1。只有执行根 （{@code
   * parentThreadId} 为 null）拥有独立开关；子代理必须 Follow 执行根，调用方负责在 version CAS 之前先做「值相同即 no-op」判断。
   */
  public ThreadState setRootYolo(boolean enabled, Instant now) {
    if (parentThreadId != null) {
      throw new IllegalArgumentException("only an execution root owns a yolo switch");
    }
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            parentThreadId,
            headEntryId,
            creationRequestHash,
            name,
            ThreadYoloPolicy.root(enabled),
            executionControl,
            inputThroughSequence,
            nextCommandSequence,
            Math.addExact(version, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * 直接控制面重命名 Thread：head / 水位 / nextCommandSequence / yoloPolicy 不变，{@code name} 被规范化替换且 {@code
   * version} 严格 +1。调用方负责在锁内先做「同名即 no-op」判断。
   */
  public ThreadState renameThread(String newName, Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            parentThreadId,
            headEntryId,
            creationRequestHash,
            newName,
            yoloPolicy,
            executionControl,
            inputThroughSequence,
            nextCommandSequence,
            Math.addExact(version, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /** 显式把对外可见的 snapshot version +1，不修改其他 durable 字段。 */
  public ThreadState touchVersion(Instant now) {
    return copy(headEntryId, executionControl, inputThroughSequence, nextCommandSequence, now);
  }

  private ThreadState copy(
      UUID headEntryId,
      ThreadExecutionControl executionControl,
      long inputThroughSequence,
      long nextCommandSequence,
      Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            parentThreadId,
            headEntryId,
            creationRequestHash,
            name,
            yoloPolicy,
            executionControl,
            inputThroughSequence,
            nextCommandSequence,
            Math.addExact(version, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  private Instant effectiveMutationTime(Instant now) {
    Instant candidate = Objects.requireNonNull(now, "now");
    return candidate.isBefore(updatedAt) ? updatedAt : candidate;
  }
}
