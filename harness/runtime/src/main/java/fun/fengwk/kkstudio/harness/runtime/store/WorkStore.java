package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Work 调度原语的事务内视图。
 *
 * <p>Work 的最外层锁级位于 Tool 之后；业务事务必须先锁 owning Thread，dispatcher claim / heartbeat 除外。时间统一取事务权威时钟。
 */
public interface WorkStore {

  /** 按 target 读取 Work。 */
  Optional<Work> find(WorkTarget target);

  /** 锁定 Work 行；不存在返回空且不产生锁。 */
  Optional<Work> lock(WorkTarget target);

  /** 锁定 Work 行并校验 claim ownership；lost / stale ownership 返回空。 */
  Optional<Work> lockClaimed(ClaimedWork claim);

  /** 强制锁定并删除 Work 行，返回是否存在；要求 owning Thread 已锁定。 */
  boolean delete(WorkTarget target);

  /** 请求一次立即可处理的 wake；要求 owning Thread 已锁定。 */
  void request(WorkTarget target);

  /** 请求一次带环境亲和性的 TOOL wake。 */
  void request(WorkTarget target, EnvironmentId requiredEnvironmentId);

  /** 领取下一个 due Work（无节点亲和性）。 */
  Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration);

  /** 领取下一个满足环境亲和性的 due Work。 */
  Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration, UUID nodeInstanceId);

  /** 延长 claim lease，返回是否实际延长。 */
  boolean renew(ClaimedWork claim, Duration leaseDuration);

  /** 完成 claim；返回空表示删除匹配当前 wake 的行。 */
  Optional<Work> complete(ClaimedWork claim);

  /** 重排 claim 的 Work 到权威时间加延迟。 */
  void reschedule(ClaimedWork claim, Duration delay);
}
