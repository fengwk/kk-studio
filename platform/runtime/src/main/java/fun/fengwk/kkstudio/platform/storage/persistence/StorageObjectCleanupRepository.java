package fun.fengwk.kkstudio.platform.storage.persistence;

import java.util.List;

/**
 * {@code storage_object_cleanup} 持久化契约：对象 key 的耐久清理记录。
 *
 * <p>插入幂等（key 是主键），必须与「移除该对象最后一个数据库事实」的变更在同一事务内提交；记录永不按时间删除，清扫只推进下一次尝试时间。
 *
 * <p>所有时间推进都由数据库时间加调用方给出的非负毫秒延后得到，调用方不提供绝对时间点：节点 JVM 时钟偏移不会把下次尝试时间推到过去。
 */
public interface StorageObjectCleanupRepository {

  /** 幂等登记一个清理 key（已存在则保留原记录，不覆盖其下次尝试时间）。 */
  boolean insertIfAbsent(String key);

  /**
   * 原子认领到期批次：按数据库当前时间选出至多 {@code limit} 条到期记录（{@code for update skip locked} 与其他节点互斥）， 并把它们的 {@code
   * next_attempt_at} 统一推进为「数据库当前时间 + {@code delayMillis} 毫秒」后返回被认领的 key。
   *
   * @param delayMillis 非负的延后毫秒数（0 表示立即可再次到期）
   */
  List<String> claimDue(int limit, long delayMillis);

  /** 认领后的对象删除失败：把该 key 的下次尝试时间改为「数据库当前时间 + {@code delayMillis} 毫秒」。 */
  boolean reschedule(String key, long delayMillis);
}
