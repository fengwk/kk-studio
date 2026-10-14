package fun.fengwk.kkstudio.platform.storage.service.model;

/** 存储 Blob 的生命周期状态。 */
public enum StorageBlobState {
  /** 存在活跃引用，可正常读取与保留。 */
  ACTIVE,

  /** 引用计数已归零，等待物理删除，不可再被保留。 */
  DELETING
}
