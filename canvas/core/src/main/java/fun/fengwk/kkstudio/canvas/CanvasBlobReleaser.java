package fun.fengwk.kkstudio.canvas;

import java.util.UUID;

/**
 * Canvas Resource 行持有的全局 Blob 引用释放端口。
 *
 * <p>宿主 Storage 适配该端口；引用变更与关系写入必须同事务，对象字节由 Storage GC 在事务外删除。
 */
public interface CanvasBlobReleaser {

  /** 释放一个 Blob 引用；无法释放时抛出异常，使外层事务整体回滚。 */
  void release(UUID blobId);
}
