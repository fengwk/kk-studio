package fun.fengwk.kkstudio.platform.storage.service;

/**
 * 对象物理删除的耐久登记与后台收敛。
 *
 * <p>凡是「对象 key 的最后一个数据库事实即将消失」的路径，都必须在同一事务内先 {@link #enqueue(String)}：登记记录本身永不按 TTL 删除，因此迟到的浏览器直传
 * PUT、服务端 COPY 或预览写入得以在后续清扫中最终被删除，而不是在无数据库引用的情况下长期残留。 调用方不得为仍被任何 ACTIVE 行引用的对象登记清理。
 */
public interface StorageObjectCleanupService {

  /** 单次清扫最多认领的到期记录数；后台维护据此判断是否已排空。 */
  int MAX_CLEANUP_BATCH = 16;

  /**
   * 幂等登记一个不可再绑定的对象 key；必须与「移除该 key 最后一个数据库事实」的变更处于同一事务，回滚时登记一并撤销。
   * 调用方不在活动事务中时直接拒绝，避免出现「事实已回滚但清理记录已提交」的伪证据。
   *
   * @param objectKey 由 {@link fun.fengwk.kkstudio.platform.storage.StorageObjectKeys} 推导的确定性对象 key
   */
  void enqueue(String objectKey);

  /**
   * 认领一批到期记录并在事务外幂等删除对应对象：到期判定与下次尝试时间全部由数据库时间换算（调用方只提供毫秒延后）， 因此节点时钟偏移不会让记录在过去被反复即时认领；认领先把 {@code
   * next_attempt_at} 推到未来，失败再改到短重试截止点，批次排空有界且失败不造成头部阻塞。返回本次认领的记录数。
   *
   * <p>该方法必须在事务外调用：对象删除是 S3 I/O，不得在数据库事务内执行。
   */
  int sweepOnce();
}
