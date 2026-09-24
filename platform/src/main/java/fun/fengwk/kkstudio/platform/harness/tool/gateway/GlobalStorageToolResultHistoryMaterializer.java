package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
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
 * <p>在 Tool outcome Entry 插入前、同一 store 事务内：
 *
 * <ul>
 *   <li>只接受 Gateway 已准备的 {@code blob-upload} 引用，不读取 ResourceStore 或执行对象 I/O；
 *   <li>引用走 {@code lockReady → retainRef → delete} 原子转移；
 *   <li>以权威 blob 事实复核 ref size/sha/mediaType，并保留宿主已验证的文本 metadata；
 *   <li>构造并返回携带完整结构化 facts 的 durable {@link ResourceMessageContent}；
 *   <li>任一项失败整体回滚事务，绝不产生部分 history。
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
  public List<AgentMessageContent> materialize(UUID sessionId, String toolName, ToolResult result) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(result, "result");
    List<ResultContent> source = result.contents();
    List<AgentMessageContent> contents = new ArrayList<>(source.size());
    for (int index = 0; index < source.size(); index++) {
      ResultContent content = source.get(index);
      if (content instanceof TextResultContent text) {
        contents.add(new TextMessageContent(text.text()));
      } else if (content instanceof JsonResultContent json) {
        contents.add(new JsonMessageContent(json.json()));
      } else if (content instanceof ResourceResultContent resource) {
        if (resource.resource().blobUploadId() == null) {
          throw new IllegalArgumentException(
              "tool resource must be staged before history materialization");
        }
        contents.add(consumeUploadedResource(sessionId, resource));
      } else {
        throw new IllegalArgumentException(
            "unsupported tool content kind for history materialization: "
                + content.getClass().getSimpleName());
      }
    }
    return List.copyOf(contents);
  }

  /**
   * 消费一个由 Daemon 直传的上传：在同一事务内把它原子转移为 Session 引用。
   *
   * <p>顺序固定为 {@code lockReady → 校验 blob 权威事实 → retainRef → delete}：先锁定上传行并取得权威 blobId 与文件名， 再用不可变
   * blob 行复核媒体类型/字节数/摘要与终态声明完全一致，然后为新 owner retain，最后请求释放上传 owner。任一步失败都让调用方 事务整体回滚，绝不产生部分 history
   * 或悬空引用。
   *
   * <p><b>权威文件名：</b>durable history 的文件名一律取自上传行（{@link
   * StorageUploadService.ReadyUpload#filename()}），绝不信任 终态消息中的 {@code ref.name()}。Daemon
   * 是不可信输入方：展示名可能被伪造、与其声明的内容不符，或试图注入路径/协议语义。
   *
   * <p><b>尺寸上界：</b>所有 staged 引用施加同一个 {@code resourceMaxBytes} 上限，声明尺寸超限时在锁定上传行之前拒绝， 不产生任何存储副作用。
   *
   * <p>资源最终只为 Session 保留 {@code blobId}：上传行、bucket、对象物理 key 与任何 Daemon 本地事实都不进入 durable history。
   */
  private ResourceMessageContent consumeUploadedResource(
      UUID sessionId, ResourceResultContent resource) {
    ResourceRef ref = resource.resource();
    UUID uploadId = ref.blobUploadId();
    if (ref.size() == null || ref.sha256() == null) {
      throw new IllegalArgumentException("uploaded resource must declare size and sha256");
    }
    if (ref.size() > resourceMaxBytes) {
      throw new IllegalArgumentException(
          "uploaded resource must not exceed " + resourceMaxBytes + " bytes");
    }
    StorageUploadService.ReadyUpload ready;
    try {
      ready = uploadService.lockReady(uploadId);
    } catch (StorageResourceNotFoundException | StorageVerificationException error) {
      throw new IllegalArgumentException("uploaded resource is not ready for history");
    }
    StorageBlob blob = blobManager.getBlob(ready.blobId());
    if (blob == null
        || blob.getState() != StorageBlobState.ACTIVE
        || !ref.mediaType().equals(blob.getMediaType())
        || ref.size() != blob.getSizeBytes()
        || !ref.sha256().equals(blob.getSha256())) {
      throw new IllegalArgumentException("uploaded resource failed integrity validation");
    }
    refManager.retainRef(sessionId, ready.blobId());
    uploadService.delete(uploadId);
    String filename = ready.filename();
    if (filename == null || filename.isBlank()) {
      throw new IllegalArgumentException("uploaded resource has no authoritative filename");
    }
    if (resource.textMetadata() != null) {
      if (resource.textMetadata().totalBytes() != blob.getSizeBytes()) {
        throw new IllegalArgumentException("text artifact metadata failed integrity validation");
      }
      return ResourceMessageContent.externalizedText(
          ready.blobId(),
          filename,
          blob.getSizeBytes(),
          resource.textMetadata().totalLines(),
          resource.preview());
    }
    // 工具结果图片没有用户选择：冻结平台默认档位（720P）；非图片媒体不带档位。
    return ResourceMessageContent.media(
        ready.blobId(), filename, resource.preview(), defaultImageTier(blob.getMediaType()));
  }

  /** 图片工具结果的默认输入档位；非 image/* 媒体不携带档位。 */
  private static ImageInputTier defaultImageTier(String mediaType) {
    return mediaType != null && mediaType.startsWith("image/") ? ImageInputTier.P720 : null;
  }
}
