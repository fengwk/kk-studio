package fun.fengwk.kkstudio.platform.storage.service;

import java.util.UUID;

/**
 * 单个 upload 的「对象写入 + 绑定」与「后台清理」之间的互斥边界。
 *
 * <p>complete/stage 在写临时/候选对象并绑定上传事实的整个窗口内独占持锁；后台清理（{@link StorageUploadService#expireOnce()}）在
 * claim 之前非阻塞尝试同一把锁，拿不到就跳过本轮 —— 绝不在无锁状态下删除任何清理证据（临时对象、 候选对象或上传行）。锁只串行化同一个 upload
 * 的操作，<b>不参与数据库事务</b>：持锁连接独立于业务连接池，持锁期间禁止在数据库事务或行锁内执行 S3 I/O 的约定不变。
 *
 * <p>调用顺序约定：必须在任何业务事务/行锁之前取锁，否则并发路径可能形成「持行锁等锁、持锁等行锁」的死锁环。
 *
 * @author fengwk
 */
public interface StorageUploadOperationLock {

  /**
   * 阻塞获取同一 upload 的独占锁：持锁方完成写对象与绑定后才会返回。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageConflictException
   *     等待超过取锁预算，上传仍被其它方占用（可重试）
   * @throws IllegalStateException 锁基础设施不可用（连接或 SQL 失败）
   */
  Handle acquire(UUID uploadId);

  /**
   * 非阻塞尝试获取同一 upload 的独占锁：已被占用时返回 null，调用方必须跳过本轮而不是等待（后台清理路径使用）。
   *
   * @throws IllegalStateException 锁基础设施不可用（连接或 SQL 失败）
   */
  Handle tryAcquire(UUID uploadId);

  /** 独占锁句柄：{@link #close()} 幂等，释放锁并关闭持锁会话（会话结束同样释放锁）。 */
  interface Handle extends AutoCloseable {

    @Override
    void close();
  }
}
