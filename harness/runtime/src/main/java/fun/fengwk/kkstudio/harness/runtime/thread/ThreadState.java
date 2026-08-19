package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * durable Thread 当前状态。
 *
 * <p>持久化 Thread 自身拥有的字段：所属 Session、materialization 身份键、head Entry cursor、Thread YOLO runtime
 * policy、下一条 Command sequence 以及对外可见的 snapshot revision。{@code sessionId} 与 {@code
 * materializationHash} 创建后不可变；environment、status、open turn、runnable flag、execution epoch 与
 * processor lease 刻意省略，settings 事实从 {@code headEntryId} 处的 Entry 分支派生。
 *
 * <p>{@code materializationHash} 是 NEW_SESSION / ENTRY 的 64 位小写 SHA-256 身份键，只作持久化身份键，不对产品 DTO
 * 暴露；{@code headEntryId} 必须属于 {@code sessionId} 的 Session，该约束由 Store 在 insert/update 时按
 * harness_entry 的 session 归属强制。
 *
 * <p>所有状态变更都通过下方纯转换方法执行；转换会把回拨的调用方 wall-clock 抬升到当前 {@code updatedAt}，任何对外可见的变更都会把 {@code revision}
 * 严格 +1。Store 仍必须在每次 {@code updateThread} 写入前调用 {@link #validateTransition}，严格拒绝直接构造的时间回退。
 */
public record ThreadState(
    UUID id,
    UUID sessionId,
    UUID headEntryId,
    String materializationHash,
    boolean yoloEnabled,
    long nextCommandSequence,
    long revision,
    Instant createdAt,
    Instant updatedAt) {

  private static final Pattern MATERIALIZATION_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public ThreadState {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(headEntryId, "headEntryId");
    if (materializationHash == null
        || !MATERIALIZATION_HASH_PATTERN.matcher(materializationHash).matches()) {
      throw new IllegalArgumentException(
          "materializationHash must be 64 lowercase hexadecimal characters");
    }
    if (nextCommandSequence < 1) {
      throw new IllegalArgumentException("nextCommandSequence must start at 1");
    }
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * 校验 {@code next} 是存储行 {@code stored} 的合法迁移：identity（id / sessionId / materializationHash /
   * createdAt）不可变， {@code headEntryId} / {@code nextCommandSequence} / {@code revision} / {@code
   * updatedAt} 不允许回退，任何对外可见的 变更都会把 {@code revision} 严格 +1。exact replay 一律被接受。
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
    if (!stored.materializationHash().equals(next.materializationHash())) {
      throw new IllegalArgumentException("thread materializationHash must not change");
    }
    if (!stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException("thread createdAt must not change");
    }
    if (next.nextCommandSequence() < stored.nextCommandSequence()) {
      throw new IllegalArgumentException("nextCommandSequence must not regress");
    }
    if (next.updatedAt().isBefore(stored.updatedAt())) {
      throw new IllegalArgumentException("updatedAt must not regress");
    }
    if (next.revision() != Math.addExact(stored.revision(), 1L)) {
      throw new IllegalArgumentException(
          "any thread state change must bump revision by exactly one");
    }
  }

  /**
   * 在一个原子步骤中预留 {@code count} 条 Command sequence：{@code nextCommandSequence} 前进 {@code count}，{@code
   * revision} 严格 +1；{@code count} 必须为正。
   */
  public ThreadState reserveCommandSequences(int count, Instant now) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            headEntryId,
            materializationHash,
            yoloEnabled,
            Math.addExact(nextCommandSequence, (long) count),
            Math.addExact(revision, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * 在一个原子步骤中推进 head Entry cursor，恒保留当前冻结的 YOLO runtime policy（不再接受外部传入值，杜绝 terminal / resolver
   * 提交路径写入过期策略）；{@code revision} 严格 +1。
   */
  public ThreadState advanceHead(UUID headEntryId, Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            headEntryId,
            materializationHash,
            yoloEnabled,
            nextCommandSequence,
            Math.addExact(revision, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * 直接控制面更新 YOLO runtime policy：head / nextCommandSequence 不变，{@code revision} 严格 +1。调用方负责在
   * revision CAS 之前先做「值相同即 no-op」判断。
   */
  public ThreadState setYoloEnabled(boolean enabled, Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            headEntryId,
            materializationHash,
            enabled,
            nextCommandSequence,
            Math.addExact(revision, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /** 显式把对外可见的 snapshot revision +1，不修改其他 durable 字段。 */
  public ThreadState touchRevision(Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            headEntryId,
            materializationHash,
            yoloEnabled,
            nextCommandSequence,
            Math.addExact(revision, 1L),
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
