package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.Objects;
import java.util.UUID;

/**
 * 已认领执行骨架：Target Processor 组合复用的最小进程内执行治理。
 *
 * <p>只承担与执行后端无关的五件事：Work-only 的所有权校验、按 invocation 的准入与 execution 登记、派发前的租约
 * margin、本地取消与关闭、活跃执行查询。它不解析请求、不物化历史、不写任何 invocation 状态；只有准入检查会开启独立的 Work-only 短事务，
 * 其余持久化操作使用调用方事务。所有方法都在调用方线程上同步完成。
 *
 * <p>所有权协议：{@link #admit} 先用 Work-only 短事务确认 claim 真实 owned，再进入同时充当 execution registry 的 {@link
 * ClaimAdmissionGuard}；guard 在原子计算内再次复核数据库所有权。同一 claim（同 lease token）重复 / 并发投递拒绝准入；不同有效 token 若存在旧
 * entry，则标记 superseded 并 abandon 旧 execution；伪造 / 错误 token 一律 LOST no-op，绝不 cancel 合法的 active
 * execution。准入与外部调用都在 guard 锁外执行，最终持久化仍须自行复核所有权。
 *
 * <p>租约：{@link #ensureLeaseMargin} 只在现有 lease 尚未覆盖权威时间 + leaseDuration 时延展，由 Store 在权威时间域内判定， 不做任何
 * JVM 时钟比较。阻塞执行期间的心跳由 execution 自己持有的 {@link WorkHeartbeat} 续租。
 *
 * @param <E> 本 Processor 的进程内 execution 类型
 */
public final class ClaimedExecutionSupport<E extends ClaimAdmissionGuard.GuardedExecution> {

  private final HarnessStore store;
  private final WorkTargetType targetType;
  private final ProcessorLeaseConfig leaseConfig;
  private final ClaimAdmissionGuard<E> guard = new ClaimAdmissionGuard<>();

  public ClaimedExecutionSupport(
      HarnessStore store, WorkTargetType targetType, ProcessorLeaseConfig leaseConfig) {
    this.store = Objects.requireNonNull(store, "store");
    this.targetType = Objects.requireNonNull(targetType, "targetType");
    this.leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
  }

  /**
   * 校验 Processor 未关闭、claim 类型匹配、当前仍持有该 claim 后尝试准入。
   *
   * <p>已关闭或类型不符是调用方契约错误，抛出异常；失权与重复 / 并发投递返回 {@code null}（调用方映射为 {@code LOST_OWNERSHIP}）。返回的 ticket
   * 覆盖 prepare 到 execution 登记，关闭后未 attach 的 ticket 拒绝 attach。
   */
  public ClaimAdmissionGuard.Ticket<E> admit(ClaimedWork claim) {
    Objects.requireNonNull(claim, "claim");
    if (guard.isClosed()) {
      throw new IllegalStateException("claimed execution support is closed");
    }
    if (claim.target().type() != targetType) {
      throw new IllegalArgumentException(
          "claimed execution support requires " + targetType + " work, got " + claim.target());
    }
    if (!claimOwned(claim)) {
      return null;
    }
    return guard.tryAdmit(claim, () -> claimOwned(claim));
  }

  /** 仅锁 Work 验证所有权；供准入与派发前复核复用。 */
  private boolean claimOwned(ClaimedWork claim) {
    return Boolean.TRUE.equals(store.transaction(tx -> !tx.lockClaimedWork(claim).isEmpty()));
  }

  /** 在调用方已锁定 Work 行时确保现有租约至少覆盖权威时间 + leaseDuration。 */
  public void ensureLeaseMargin(HarnessStore.Transaction tx, ClaimedWork claim) {
    tx.renewWork(claim, leaseConfig.leaseDuration());
  }

  /** 定位仍登记为 active 的本地 execution；已 superseded 或不存在时返回 {@code null}。 */
  public E execution(UUID invocationId) {
    return guard.getExecution(invocationId);
  }

  /** 释放已结束的 execution 登记；重复释放幂等。 */
  public void release(E execution) {
    guard.releaseExecution(execution);
  }

  /** 取消一个进程内本地 execution；结果不反写任何 durable 状态。 */
  public boolean cancel(UUID invocationId) {
    Objects.requireNonNull(invocationId, "invocationId");
    return guard.cancel(invocationId);
  }

  /** 取消全部本地 execution 并停止各自 heartbeat；不 shutdown 调用方注入的 scheduler / executor；幂等。 */
  public void close() {
    guard.close();
  }

  /** 是否已关闭：关闭后不再准入新 claim。 */
  public boolean isClosed() {
    return guard.isClosed();
  }

  /** 当前 JVM 是否持有任何活跃的本地 execution。 */
  public boolean hasActiveExecution() {
    return guard.hasActiveExecution();
  }
}
