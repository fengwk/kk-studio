package fun.fengwk.kkstudio.platform.harness.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Prompt 媒体窄端口的宿主实现：接受事务外暂存引用媒体，接受事务内把暂存物化为 durable RESOURCE。
 *
 * <p>暂存严格校验真实字节数与媒体类型，校验失败自行回收本次暂存；接收时以上传行的权威文件名与 blobId 构造 RESOURCE，先为 Session 建立引用再释放 upload
 * owner，全过程与命令接受同一事务，事务内绝不执行对象存储 IO。
 */
@Slf4j
@Component
public final class PluginPromptResourceBridge implements PluginPromptResources {

  private final StorageUploadService uploadService;
  private final SessionBlobRefManager refManager;

  @Autowired
  public PluginPromptResourceBridge(
      StorageUploadService uploadService, SessionBlobRefManager refManager) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
  }

  @Override
  public StagedResource stage(
      String filename, String mediaType, InputStream input, long expectedSize) {
    Objects.requireNonNull(filename, "filename");
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(input, "input");
    if (expectedSize <= 0L) {
      throw new IllegalArgumentException("expectedSize must be positive");
    }
    StorageUploadService.StagedUpload staged =
        uploadService.stage(filename, mediaType, input, expectedSize);
    if (!mediaType.equals(staged.mediaType()) || staged.sizeBytes() != expectedSize) {
      discardQuietly(staged.uploadId());
      throw new IllegalArgumentException("staged prompt media failed integrity validation");
    }
    return new StagedResource(staged.uploadId(), staged.blobId());
  }

  @Override
  public void discard(StagedResource resource) {
    Objects.requireNonNull(resource, "resource");
    uploadService.delete(resource.uploadId());
  }

  /**
   * 接受事务内的媒体接收：调用方必须处于事务中。
   *
   * <p>锁定 READY 上传行（PENDING、过期、已请求清理或不存在确定性拒绝），复核暂存句柄的 blob 身份，以权威文件名建 durable RESOURCE，再让 Session
   * 先持有 blob 引用并释放 upload owner；任一步失败整体回滚，对象清理由提交后的 Storage Maintenance 完成。
   */
  ResourceMessageContent accept(UUID sessionId, StagedResource staged) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(staged, "staged");
    StorageUploadService.ReadyUpload ready;
    try {
      ready = uploadService.lockReady(staged.uploadId());
    } catch (StorageResourceNotFoundException | StorageVerificationException error) {
      throw new IllegalArgumentException("staged prompt media cannot be consumed");
    }
    if (!staged.blobId().equals(ready.blobId())) {
      throw new IllegalArgumentException("staged prompt media failed integrity validation");
    }
    refManager.retainRef(sessionId, ready.blobId());
    uploadService.delete(staged.uploadId());
    return ResourceMessageContent.media(ready.blobId(), ready.filename());
  }

  /** 校验失败时的自清理：清理失败不得遮蔽完整性失败本身，达期上传仍由 maintenance 兜底。 */
  private void discardQuietly(UUID uploadId) {
    try {
      uploadService.delete(uploadId);
    } catch (RuntimeException ignored) {
      log.debug("discarded staged prompt media is still waiting for durable expiry");
    }
  }
}
