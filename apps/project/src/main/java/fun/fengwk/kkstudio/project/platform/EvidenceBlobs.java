package fun.fengwk.kkstudio.project.platform;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;

import java.util.Objects;
import java.util.UUID;

/**
 * Project/Issue 证据所需的全局 Blob 能力。
 *
 * <p>只暴露证据发布与释放真正需要的最小事实：人工上传的权威消费、全局 Blob 引用计数与 Blob 活跃性判定。所有变更方法都加入调用方已有的同一物理事务（{@code
 * StorageUploadService.lockReady}、{@code StorageBlobManager.retain/release} 都要求活动事务），存储异常在此收敛为
 * Project 领域异常。
 */
@AllArgsConstructor
@Service
public class EvidenceBlobs {

  private static final String EVIDENCE_BLOB_UNAVAILABLE =
      "Evidence blob is not available for publication";

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;
  private final SessionBlobRefManager sessionBlobRefManager;

  /**
   * 事务内锁定 READY 人工上传并返回权威消费事实（blobId 与权威文件名，文件名绝不信任客户端消息）。
   *
   * @throws ProjectNotFoundException 上传不存在
   * @throws ProjectValidationException 上传不可消费
   */
  public ReadyUpload lockReadyUpload(UUID uploadId) {
    try {
      StorageUploadService.ReadyUpload ready = uploadService.lockReady(uploadId);
      return new ReadyUpload(ready.blobId(), ready.filename());
    } catch (StorageResourceNotFoundException error) {
      throw new ProjectNotFoundException("storage_upload");
    } catch (StorageVerificationException error) {
      throw new ProjectValidationException(
          "upload", "Upload is not ready to be published as evidence");
    }
  }

  /** 标记并消费一次已授权的人工上传（upload owner release 与对象清理由存储幂等完成）。 */
  public void deleteUpload(UUID uploadId) {
    uploadService.delete(uploadId);
  }

  /**
   * 事务内 retain 一次全局 Blob 引用。
   *
   * @throws ProjectValidationException blob 不存在或已进入删除终态
   */
  public void retainBlob(UUID blobId) {
    try {
      blobManager.retain(blobId);
    } catch (StorageResourceNotFoundException error) {
      throw new ProjectValidationException("issue_evidence", EVIDENCE_BLOB_UNAVAILABLE);
    }
  }

  /** 事务内 release 一次全局 Blob 引用；返回 false 表示已进入删除终态或不存在（幂等）。 */
  public boolean releaseBlob(UUID blobId) {
    return blobManager.release(blobId);
  }

  /** 该 Blob 是否仍处于可读取的活跃状态：已发布证据的可读性依据，URI 本身不是权限凭据。 */
  public boolean isBlobActive(UUID blobId) {
    StorageBlob blob = blobManager.getBlob(blobId);
    return blob != null && blob.getState() == StorageBlobState.ACTIVE;
  }

  /**
   * 来源 Run 的 Harness Session 当前是否持有该 Blob 引用。
   *
   * <p>公开证据必须同时满足最终答复引用与 Session 持有；只凭 Blob 活跃不能把其他 Session 的产物发布到本 Issue。
   */
  public boolean isSessionBlobRef(UUID sessionId, UUID blobId) {
    if (sessionId == null || blobId == null) {
      return false;
    }
    return sessionBlobRefManager.contains(sessionId, blobId);
  }

  /** READY 人工上传的权威消费事实。 */
  public record ReadyUpload(UUID blobId, String filename) {
    public ReadyUpload {
      Objects.requireNonNull(blobId, "blobId");
    }
  }
}
