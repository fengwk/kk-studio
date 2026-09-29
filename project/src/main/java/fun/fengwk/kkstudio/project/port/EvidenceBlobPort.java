package fun.fengwk.kkstudio.project.port;

import java.util.Objects;
import java.util.UUID;

/**
 * Project/Issue 证据所需的全局 Blob 能力，由宿主（platform）适配实现。
 *
 * <p>只暴露证据发布与释放真正需要的最小事实：人工上传的权威消费、全局 Blob 引用计数与 Blob 活跃性判定。所有变更方法都加入调用方已有的同一物理事务。
 */
public interface EvidenceBlobPort {

  /**
   * 事务内锁定 READY 人工上传并返回权威消费事实（blobId 与权威文件名，文件名绝不信任客户端消息）。
   *
   * @throws fun.fengwk.kkstudio.project.error.ProjectNotFoundException 上传不存在
   * @throws fun.fengwk.kkstudio.project.error.ProjectValidationException 上传不可消费
   */
  ReadyUpload lockReadyUpload(UUID uploadId);

  /** 标记并消费一次已授权的人工上传（upload owner release 与对象清理由宿主幂等完成）。 */
  void deleteUpload(UUID uploadId);

  /**
   * 事务内 retain 一次全局 Blob 引用。
   *
   * @throws fun.fengwk.kkstudio.project.error.ProjectValidationException blob 不存在或已进入删除终态
   */
  void retainBlob(UUID blobId);

  /** 事务内 release 一次全局 Blob 引用；返回 false 表示已进入删除终态或不存在（幂等）。 */
  boolean releaseBlob(UUID blobId);

  /** 该 Blob 是否仍处于可读取的活跃状态：已发布证据的可读性依据，URI 本身不是权限凭据。 */
  boolean isBlobActive(UUID blobId);

  /**
   * 来源 Run 的 Harness Session 当前是否持有该 Blob 引用。
   *
   * <p>公开证据必须同时满足最终答复引用与 Session 持有；只凭 Blob 活跃不能把其他 Session 的产物发布到本 Issue。
   */
  boolean isSessionBlobRef(UUID sessionId, UUID blobId);

  /** READY 人工上传的权威消费事实。 */
  record ReadyUpload(UUID blobId, String filename) {

    public ReadyUpload {
      Objects.requireNonNull(blobId, "blobId");
    }
  }
}
