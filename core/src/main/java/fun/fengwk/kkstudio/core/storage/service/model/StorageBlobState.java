package fun.fengwk.kkstudio.core.storage.service.model;

/**
 * blob 生命周期状态。
 *
 * <p>{@link #ACTIVE} 表示存在活跃引用（{@code ref_count > 0}）；{@link #DELETING} 是终态（{@code ref_count =
 * 0}），不可再 retain，等待 S3 对象删除后行被条件删除。
 *
 * @author fengwk
 */
public enum StorageBlobState {
  ACTIVE,
  DELETING
}
