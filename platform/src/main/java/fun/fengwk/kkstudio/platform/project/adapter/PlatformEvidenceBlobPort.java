package fun.fengwk.kkstudio.platform.project.adapter;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.port.EvidenceBlobPort;

import java.util.UUID;

/**
 * {@link EvidenceBlobPort} 的 platform 宿主适配：把平台全局 Blob 能力暴露给 Project 领域。
 *
 * <p>仍加入调用方已有的同一物理事务（{@code StorageUploadService.lockReady}、{@code
 * StorageBlobManager.retain/release} 都要求活动事务），平台类型与异常在此收敛为 Project 领域类型。
 */
@AllArgsConstructor
@Service
public class PlatformEvidenceBlobPort implements EvidenceBlobPort {

  private static final String EVIDENCE_BLOB_UNAVAILABLE =
      "Evidence blob is not available for publication";

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;

  @Override
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

  @Override
  public void deleteUpload(UUID uploadId) {
    uploadService.delete(uploadId);
  }

  @Override
  public void retainBlob(UUID blobId) {
    try {
      blobManager.retain(blobId);
    } catch (StorageResourceNotFoundException error) {
      throw new ProjectValidationException("issue_evidence", EVIDENCE_BLOB_UNAVAILABLE);
    }
  }

  @Override
  public boolean releaseBlob(UUID blobId) {
    return blobManager.release(blobId);
  }

  @Override
  public boolean isBlobActive(UUID blobId) {
    StorageBlob blob = blobManager.getBlob(blobId);
    return blob != null && blob.getState() == StorageBlobState.ACTIVE;
  }
}
