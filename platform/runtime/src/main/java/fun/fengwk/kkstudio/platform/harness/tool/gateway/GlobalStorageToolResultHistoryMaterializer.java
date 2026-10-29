package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.port.ToolOutcomeMaterializationException;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 基于全局 Blob 存储的 {@link ToolResultHistoryMaterializer}。
 *
 * <p>在单工具 terminal 的同一 store 事务内：
 *
 * <ul>
 *   <li>只接受 Gateway 已准备的 {@code blob-upload} 或已授权的 {@code kkstudio} 引用，不执行对象 I/O；
 *   <li>上传引用走 {@code lockReady → 校验权威事实 → retainRef → delete} 原子转移；
 *   <li>结果为稳定的 {@link ToolResult}：Resource 只携带 {@code kkstudio:/resources/<blobId>}，绝不保留临时 upload
 *       URI；
 *   <li>任一项确定性校验失败抛 {@link ToolOutcomeMaterializationException} 并整体回滚，绝不产生部分 history。
 * </ul>
 */
public class GlobalStorageToolResultHistoryMaterializer implements ToolResultHistoryMaterializer {

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;
  private final SessionBlobRefManager refManager;
  private final int resourceMaxBytes;

  public GlobalStorageToolResultHistoryMaterializer(
      StorageUploadService uploadService,
      StorageBlobManager blobManager,
      SessionBlobRefManager refManager,
      int resourceMaxBytes) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    if (resourceMaxBytes <= 0) {
      throw new IllegalArgumentException("resourceMaxBytes must be positive");
    }
    this.resourceMaxBytes = resourceMaxBytes;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public ToolResult materialize(UUID sessionId, String toolName, ToolResult result) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(result, "result");
    List<ResultContent> source = result.contents();
    List<ResultContent> stable = new ArrayList<>(source.size());
    for (ResultContent content : source) {
      if (content instanceof TextResultContent || content instanceof JsonResultContent) {
        stable.add(content);
      } else if (content instanceof ResourceResultContent resource) {
        stable.add(stableResource(sessionId, resource));
      } else {
        throw new ToolOutcomeMaterializationException(
            "unsupported tool content kind for stable outcome: "
                + content.getClass().getSimpleName());
      }
    }
    return new ToolResult(
        result.toolCallId(), List.copyOf(stable), result.error(), result.detailsJson());
  }

  private ResourceResultContent stableResource(UUID sessionId, ResourceResultContent resource) {
    ResourceRef ref = resource.resource();
    if (ref.sessionBlobId() != null) {
      return reuseSessionResource(sessionId, resource);
    }
    if (ref.blobUploadId() != null) {
      return consumeUploadedResource(sessionId, resource);
    }
    throw new ToolOutcomeMaterializationException(
        "tool resource must be staged or an authorized session resource");
  }

  /**
   * 消费一个由 Daemon 直传的上传：在同一事务内把它原子转移为 Session 引用。
   *
   * <p>顺序固定为 {@code lockReady → 校验 blob 权威事实与文件名 → retainRef → delete}：新 owner 建立之前拒绝任何无效输入，
   * 任一步失败都让调用方 事务整体回滚，绝不产生部分 history 或悬空引用。
   */
  private ResourceResultContent consumeUploadedResource(
      UUID sessionId, ResourceResultContent resource) {
    ResourceRef ref = resource.resource();
    UUID uploadId = ref.blobUploadId();
    if (ref.size() == null || ref.sha256() == null) {
      throw new ToolOutcomeMaterializationException(
          "uploaded resource must declare size and sha256");
    }
    if (ref.size() > resourceMaxBytes) {
      throw new ToolOutcomeMaterializationException(
          "uploaded resource must not exceed " + resourceMaxBytes + " bytes");
    }
    StorageUploadService.ReadyUpload ready;
    try {
      ready = uploadService.lockReady(uploadId);
    } catch (StorageResourceNotFoundException | StorageVerificationException error) {
      throw new ToolOutcomeMaterializationException("uploaded resource is not ready for history");
    }
    StorageBlob blob = blobManager.getBlob(ready.blobId());
    if (blob == null
        || blob.getState() != StorageBlobState.ACTIVE
        || !ref.mediaType().equals(blob.getMediaType())
        || ref.size() != blob.getSizeBytes()
        || !ref.sha256().equals(blob.getSha256())) {
      throw new ToolOutcomeMaterializationException(
          "uploaded resource failed integrity validation");
    }
    String filename = ready.filename();
    if (filename == null || filename.isBlank()) {
      throw new ToolOutcomeMaterializationException(
          "uploaded resource has no authoritative filename");
    }
    if (resource.textMetadata() != null
        && resource.textMetadata().totalBytes() != blob.getSizeBytes()) {
      throw new ToolOutcomeMaterializationException(
          "text artifact metadata failed integrity validation");
    }
    refManager.retainRef(sessionId, ready.blobId());
    uploadService.delete(uploadId);
    return new ResourceResultContent(
        stableRef(blob, filename), resource.preview(), resource.textMetadata());
  }

  /** 复用一个已授权的 Session Blob：只接受当前 Session 已持有且与权威 blob facts 一致的引用，随后幂等 retain；绝不读取或写入对象存储。 */
  private ResourceResultContent reuseSessionResource(
      UUID sessionId, ResourceResultContent resource) {
    ResourceRef ref = resource.resource();
    UUID blobId = ref.sessionBlobId();
    if (ref.size() == null || ref.sha256() == null) {
      throw new ToolOutcomeMaterializationException(
          "session resource must declare size and sha256");
    }
    if (resource.textMetadata() != null) {
      throw new ToolOutcomeMaterializationException(
          "session media resource must not declare text metadata");
    }
    if (!refManager.contains(sessionId, blobId)) {
      throw new ToolOutcomeMaterializationException(
          "session resource is not referenced by this session");
    }
    StorageBlob blob = blobManager.getBlob(blobId);
    if (blob == null
        || blob.getState() != StorageBlobState.ACTIVE
        || !ref.mediaType().equals(blob.getMediaType())
        || ref.size() != blob.getSizeBytes()
        || !ref.sha256().equals(blob.getSha256())) {
      throw new ToolOutcomeMaterializationException("session resource failed integrity validation");
    }
    if (!isSupportedReadMedia(blob.getMediaType())) {
      throw new ToolOutcomeMaterializationException(
          "session resource is not a supported model media type");
    }
    if (ref.name() == null || ref.name().isBlank()) {
      throw new ToolOutcomeMaterializationException(
          "session resource must declare a durable filename");
    }
    refManager.retainRef(sessionId, blobId);
    return new ResourceResultContent(stableRef(blob, ref.name()), resource.preview(), null);
  }

  private static ResourceRef stableRef(StorageBlob blob, String filename) {
    return new ResourceRef(
        ResourceRef.sessionResourceUri(blob.getId()),
        blob.getMediaType(),
        filename,
        blob.getSizeBytes(),
        blob.getSha256());
  }

  /** 可作为模型输入的媒体类型：图片、音频、视频与 PDF 文档。 */
  private static boolean isSupportedReadMedia(String mediaType) {
    if (mediaType == null) {
      return false;
    }
    return mediaType.startsWith("image/")
        || mediaType.startsWith("audio/")
        || mediaType.startsWith("video/")
        || "application/pdf".equals(mediaType);
  }
}
