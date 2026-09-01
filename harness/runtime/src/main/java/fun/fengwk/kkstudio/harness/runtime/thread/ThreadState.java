package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * durable Thread 当前状态。
 *
 * <p>持久化 Thread 自身拥有的字段：所属 Session、creation request hash 身份键、head Entry cursor、Thread YOLO runtime
 * policy、下一条 Command sequence 以及对外可见的 snapshot version。{@code sessionId} 与 {@code
 * creationRequestHash} 创建后不可变；environment、status、open turn、runnable flag、execution epoch 与
 * processor lease 刻意省略，settings 事实从 {@code headEntryId} 处的 Entry 分支派生。
 *
 * <p>{@code creationRequestHash} 是 NEW_SESSION / ENTRY 的初始创建请求指纹：64 位小写 SHA-256 身份键，只作持久化身份键，不对产品
 * DTO 暴露；{@code headEntryId} 必须属于 {@code sessionId} 的 Session，该约束由 Store 在 insert/update 时按
 * harness_entry 的 session 归属强制。
 *
 * <p>所有状态变更都通过下方纯转换方法执行；转换会把回拨的调用方 wall-clock 抬升到当前 {@code updatedAt}，任何对外可见的变更都会把 {@code version}
 * 严格 +1。Store 仍必须在每次 {@code updateThread} 写入前调用 {@link #validateTransition}，严格拒绝直接构造的时间回退。
 */
public record ThreadState(
    UUID id,
    UUID sessionId,
    UUID headEntryId,
    String creationRequestHash,
    boolean yoloEnabled,
    long nextCommandSequence,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  private static final Pattern CREATION_REQUEST_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public ThreadState {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(headEntryId, "headEntryId");
    if (creationRequestHash == null
        || !CREATION_REQUEST_HASH_PATTERN.matcher(creationRequestHash).matches()) {
      throw new IllegalArgumentException(
          "creationRequestHash must be 64 lowercase hexadecimal characters");
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
   * 校验 {@code next} 是存储行 {@code stored} 的合法迁移：identity（id / sessionId / creationRequestHash /
   * createdAt）不可变， {@code headEntryId} / {@code nextCommandSequence} / {@code version} / {@code
   * updatedAt} 不允许回退，任何对外可见的 变更都会把 {@code version} 严格 +1。exact replay 一律被接受。
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
    if (!stored.creationRequestHash().equals(next.creationRequestHash())) {
      throw new IllegalArgumentException("thread creationRequestHash must not change");
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
    if (next.version() != Math.addExact(stored.version(), 1L)) {
      throw new IllegalArgumentException(
          "any thread state change must bump version by exactly one");
    }
  }

  /**
   * 在一个原子步骤中预留 {@code count} 条 Command sequence：{@code nextCommandSequence} 前进 {@code count}，{@code
   * version} 严格 +1；{@code count} 必须为正。
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
            creationRequestHash,
            yoloEnabled,
            Math.addExact(nextCommandSequence, (long) count),
            Math.addExact(version, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * 在一个原子步骤中推进 head Entry cursor，恒保留当前冻结的 YOLO runtime policy（不再接受外部传入值，杜绝 terminal / resolver
   * 提交路径写入过期策略）；{@code version} 严格 +1。
   */
  public ThreadState advanceHead(UUID headEntryId, Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            headEntryId,
            creationRequestHash,
            yoloEnabled,
            nextCommandSequence,
            Math.addExact(version, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * 直接控制面更新 YOLO runtime policy：head / nextCommandSequence 不变，{@code version} 严格 +1。调用方负责在 version
   * CAS 之前先做「值相同即 no-op」判断。
   */
  public ThreadState setYoloEnabled(boolean enabled, Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            headEntryId,
            creationRequestHash,
            enabled,
            nextCommandSequence,
            Math.addExact(version, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /** 显式把对外可见的 snapshot version +1，不修改其他 durable 字段。 */
  public ThreadState touchVersion(Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            sessionId,
            headEntryId,
            creationRequestHash,
            yoloEnabled,
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
