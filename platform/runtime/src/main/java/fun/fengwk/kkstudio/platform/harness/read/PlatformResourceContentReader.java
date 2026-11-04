package fun.fengwk.kkstudio.platform.harness.read;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadInterruptedException;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.Objects;
import java.util.UUID;

/**
 * 平台侧 Session 授权 Blob 资源内容读取器。
 *
 * <p>按 {@code vfs:/resources/<blobId>} 读取当前会话授权的 Blob：
 *
 * <ol>
 *   <li>会话身份由工具执行上下文给出，只校验该 Session 已引用该 Blob（鉴权失败与不存在无法区分地拒绝，避免信息泄露）；
 *   <li>以授权 Blob 的权威 MIME 判定读取形态：文本继续返回有界窗口；{@code image/*}、{@code audio/*}、{@code video/*} 与
 *       {@code application/pdf} 走真实媒体路径；
 *   <li>媒体路径在读取任何内容之前，用执行上下文中该 Turn 已冻结的消费者媒体能力、权威 MIME 与内联预算共同校验：不支持或超预算都以明确英文错误拒绝；
 *   <li>通过校验后只返回指向同一已授权 Blob 的 durable {@link ResourceRef}，真实字节在 provider attempt 物化时读取。
 * </ol>
 *
 * <p>读取预算由调用方在入口冻结并传入唯一 {@link ReadDeadline}，鉴权、S3 getObject 握手消费与文本窗口扫描共用同一绝对截止时间；超时或取消都会中止 S3
 * 连接并以明确错误结束，不会继续排空响应体。
 */
public class PlatformResourceContentReader {

  /** read 的两种结果形态：文本窗口或指向已授权 Blob 的 durable 媒体引用。 */
  public sealed interface ResourceRead permits ResourceRead.Text, ResourceRead.Media {

    record Text(String text) implements ResourceRead {}

    record Media(ResourceRef resource, String preview) implements ResourceRead {}
  }

  private final SessionBlobRefManager sessionBlobRefManager;
  private final StorageBlobManager storageBlobManager;
  private final StorageBlobContentService storageBlobContentService;

  public PlatformResourceContentReader(
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageBlobContentService storageBlobContentService) {
    this.sessionBlobRefManager =
        Objects.requireNonNull(sessionBlobRefManager, "sessionBlobRefManager");
    this.storageBlobManager = Objects.requireNonNull(storageBlobManager, "storageBlobManager");
    this.storageBlobContentService =
        Objects.requireNonNull(storageBlobContentService, "storageBlobContentService");
  }

  /**
   * 按工具执行上下文读取 Session 授权的 Blob：文本返回有界窗口；支持的媒体返回指向同一 Blob 的 durable 引用。
   *
   * <p>媒体路径只用执行上下文中冻结的消费者媒体能力，绝不读取 live thread 当前 model 替代：模态/协议不支持、MIME 非法或超出内联预算时，在读取任何 Blob
   * 内容之前以明确英文错误拒绝。
   *
   * @param sessionId 本次调用所属会话，非空
   * @param mediaCapabilities 本次调用 Turn 已冻结的消费者媒体能力，非空
   * @param blobId 目标 Blob ID，非空
   * @param deadline 绝对读取截止，非空
   * @return 文本窗口或媒体引用
   * @throws PlatformReadException 鉴权失败、媒体不受支持或超出预算、读取失败时抛出
   */
  public ResourceRead readResource(
      UUID sessionId,
      TurnMediaCapabilities mediaCapabilities,
      UUID blobId,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      ReadDeadline deadline) {
    if (sessionId == null) {
      throw new PlatformReadException("sessionId must not be null");
    }
    Objects.requireNonNull(mediaCapabilities, "mediaCapabilities must not be null");
    if (blobId == null) {
      throw new PlatformReadException("blobId must not be null");
    }
    Objects.requireNonNull(deadline, "deadline must not be null");
    authorize(sessionId, blobId);

    StorageBlob blob = storageBlobManager.getBlob(blobId);
    ModelInputModality modality = blob == null ? null : mediaModality(blob.getMediaType());
    if (modality == null) {
      return new ResourceRead.Text(
          readTextWindow(blobId, offset, limit, columnOffset, displayPath, deadline));
    }
    if (blob.getState() != StorageBlobState.ACTIVE) {
      throw new PlatformReadException("resource is not available: " + blobId);
    }
    boolean consumerDeclares = mediaCapabilities.declares(modality);
    boolean protocolAccepts = mediaCapabilities.acceptsProtocolPosition(modality);
    if (!consumerDeclares || !protocolAccepts) {
      throw new PlatformReadException(
          unsupportedMediaMessage(blob, modality, consumerDeclares, protocolAccepts));
    }
    long sizeBytes = blob.getSizeBytes();
    long maxBlobBytes = ProviderResourceMaterializer.maxInlineBlobBytes();
    if (sizeBytes < 0 || sizeBytes > maxBlobBytes) {
      throw new PlatformReadException(
          "resource "
              + blobId
              + " is "
              + sizeBytes
              + " bytes ("
              + blob.getMediaType()
              + "), which exceeds the "
              + maxBlobBytes
              + " byte inline limit for model input");
    }
    long estimatedChars =
        ProviderResourceMaterializer.estimatedInlineChars(blob.getMediaType(), sizeBytes);
    long maxChars = ProviderResourceMaterializer.maxInlineRequestChars();
    if (estimatedChars > maxChars) {
      throw new PlatformReadException(
          "resource "
              + blobId
              + " ("
              + blob.getMediaType()
              + ") requires "
              + estimatedChars
              + " inline characters, which exceeds the "
              + maxChars
              + " character request budget");
    }
    if (Thread.currentThread().isInterrupted() || deadline.isExpired()) {
      throw new PlatformReadException("resource read interrupted or timed out: " + displayPath);
    }
    ResourceRef ref =
        new ResourceRef(
            ResourceRef.sessionResourceUri(blobId),
            blob.getMediaType(),
            blobId.toString(),
            sizeBytes,
            blob.getSha256());
    return new ResourceRead.Media(ref, null);
  }

  /** 当前 Session 已引用该 Blob；否则以无法区分的错误拒绝。会话身份来自执行上下文，不在这里反查 Harness 存储。 */
  private void authorize(UUID sessionId, UUID blobId) {
    if (!sessionBlobRefManager.contains(sessionId, blobId)) {
      throw new PlatformReadException("resource is not referenced by this session");
    }
  }

  private String readTextWindow(
      UUID blobId,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      ReadDeadline deadline) {
    try {
      String text =
          storageBlobContentService.withBlobStream(
              blobId,
              deadline,
              stream ->
                  TextReadWindow.format(
                      stream, offset, limit, columnOffset, displayPath, "unsupported", deadline));
      if (text == null) {
        throw new PlatformReadException("resource content is unavailable: " + blobId);
      }
      return text;
    } catch (StorageResourceNotFoundException e) {
      throw new PlatformReadException("resource not found: " + blobId, e);
    } catch (StorageReadTimeoutException e) {
      throw new PlatformReadException("resource read timed out: " + blobId, e);
    } catch (StorageReadInterruptedException e) {
      throw new PlatformReadException("resource read interrupted: " + blobId, e);
    } catch (PlatformReadException e) {
      throw e;
    } catch (Exception e) {
      // 固定安全英文失败消息：S3/OS 原始错误只保留在 cause 异常链，绝不进入模型可见文本。
      throw new PlatformReadException("failed to read resource: " + blobId, e);
    }
  }

  /** 权威 MIME 到可送达模型的媒体模态；文本或任意其它类型返回 null（继续走文本窗口）。 */
  private static ModelInputModality mediaModality(String mediaType) {
    if (mediaType == null) {
      return null;
    }
    if (mediaType.startsWith("image/")) {
      return ModelInputModality.IMAGE;
    }
    if (mediaType.startsWith("audio/")) {
      return ModelInputModality.AUDIO;
    }
    if (mediaType.startsWith("video/")) {
      return ModelInputModality.VIDEO;
    }
    if ("application/pdf".equals(mediaType)) {
      return ModelInputModality.DOCUMENT;
    }
    return null;
  }

  /** 媒体不受支持时的明确英文错误：只包含 Blob id、MIME 与原因，不回显任何内容。 */
  private static String unsupportedMediaMessage(
      StorageBlob blob,
      ModelInputModality modality,
      boolean modelDeclares,
      boolean protocolAccepts) {
    String reason;
    if (!modelDeclares) {
      reason = "the calling model does not declare " + modality + " input";
    } else {
      reason =
          "the calling protocol accepts " + modality + " in neither user messages nor tool results";
    }
    return "resource "
        + blob.getId()
        + " ("
        + blob.getMediaType()
        + ") cannot be read as model input: "
        + reason;
  }
}
