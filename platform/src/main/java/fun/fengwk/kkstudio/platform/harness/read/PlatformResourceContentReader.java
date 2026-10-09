package fun.fengwk.kkstudio.platform.harness.read;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 平台侧 Session 授权 Blob 资源内容读取器。
 *
 * <p>按 {@code kkstudio:/resources/<blobId>} 读取当前会话授权的 Blob：
 *
 * <ol>
 *   <li>通过 {@code threadId} 从 {@link HarnessStore} 解析关联的 {@code sessionId}，并校验当前 Session 已引用该
 *       Blob（鉴权失败与不存在 无法区分地拒绝，避免信息泄露）；
 *   <li>以授权 Blob 的权威 MIME 判定读取形态：文本继续返回有界窗口；{@code image/*}、{@code audio/*}、{@code video/*} 与
 *       {@code application/pdf} 走真实媒体路径；
 *   <li>媒体路径在读取任何内容之前，用「通过工具调用 id 解析到的唯一冻结调用模型」的输入模态与该 protocol 的合法位置能力、权威 MIME 与内联预算共同校验：
 *       不支持或超预算都以明确英文错误拒绝；
 *   <li>通过校验后只返回指向同一已授权 Blob 的 durable {@link ResourceRef}，真实字节在 provider attempt 物化时读取。
 * </ol>
 *
 * <p>读取预算在本类入口冻结（{@link #READ_TIMEOUT}），因此包括 S3 getObject 握手在内的整个读取过程共享同一绝对截止时间；超时或取消都会中止 S3
 * 连接并以明确错误结束，不会继续排空响应体。
 */
public class PlatformResourceContentReader {

  /** 受管资源读取的绝对预算：从读取请求入口冻结，覆盖会话鉴权之后的 S3 握手与响应体消费。 */
  public static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  /** read 的两种结果形态：文本窗口或指向已授权 Blob 的 durable 媒体引用。 */
  public sealed interface ResourceRead permits ResourceRead.Text, ResourceRead.Media {

    record Text(String text) implements ResourceRead {}

    record Media(ResourceRef resource, String preview) implements ResourceRead {}
  }

  private final Supplier<HarnessStore> harnessStoreSupplier;
  private final SessionBlobRefManager sessionBlobRefManager;
  private final StorageBlobManager storageBlobManager;
  private final StorageBlobContentService storageBlobContentService;

  public PlatformResourceContentReader(
      Supplier<HarnessStore> harnessStoreSupplier,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageBlobContentService storageBlobContentService) {
    this.harnessStoreSupplier =
        Objects.requireNonNull(harnessStoreSupplier, "harnessStoreSupplier");
    this.sessionBlobRefManager =
        Objects.requireNonNull(sessionBlobRefManager, "sessionBlobRefManager");
    this.storageBlobManager = Objects.requireNonNull(storageBlobManager, "storageBlobManager");
    this.storageBlobContentService =
        Objects.requireNonNull(storageBlobContentService, "storageBlobContentService");
  }

  /**
   * 读取当前 Thread 对应 Session 授权的 Blob 文本窗口。
   *
   * @param threadId 当前 Harness Thread ID，非空
   * @param blobId 目标 Blob ID，非空
   * @return 格式化的文本窗口
   * @throws PlatformReadException Thread 不存在、Session 未引用该资源、资源不存在、读取超时/取消或读取失败时抛出
   */
  public String readResourceText(
      UUID threadId,
      UUID blobId,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath) {
    if (threadId == null) {
      throw new PlatformReadException("threadId must not be null");
    }
    if (blobId == null) {
      throw new PlatformReadException("blobId must not be null");
    }
    ReadDeadline deadline = ReadDeadline.after(READ_TIMEOUT);
    HarnessStore store = requireStore();
    authorize(store, threadId, blobId);
    return readTextWindow(blobId, offset, limit, columnOffset, displayPath, deadline);
  }

  /**
   * 按调用工具槽位读取 Session 授权的 Blob：文本返回有界窗口；支持的媒体返回指向同一 Blob 的 durable 引用。
   *
   * <p>媒体路径只用冻结调用模型事实，绝不读取 live thread 当前 model 替代：模态/协议不支持、MIME 非法或超出内联预算时，在读取任何 Blob
   * 内容之前以明确英文错误拒绝。
   *
   * @param threadId 当前 Harness Thread ID，非空
   * @param invocationId 调用该工具的 durable {@code ToolInvocation} id；文本读取可为 null，媒体读取必须存在
   * @param callId 本次工具调用 id，用于校验调用绑定
   * @param blobId 目标 Blob ID，非空
   * @return 文本窗口或媒体引用
   * @throws PlatformReadException 鉴权失败、调用绑定不一致、媒体不受支持或超出预算、读取失败时抛出
   */
  public ResourceRead readResource(
      UUID threadId,
      UUID invocationId,
      String callId,
      UUID blobId,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath) {
    if (threadId == null) {
      throw new PlatformReadException("threadId must not be null");
    }
    if (blobId == null) {
      throw new PlatformReadException("blobId must not be null");
    }
    ReadDeadline deadline = ReadDeadline.after(READ_TIMEOUT);
    HarnessStore store = requireStore();
    authorize(store, threadId, blobId);

    StorageBlob blob = storageBlobManager.getBlob(blobId);
    ModelInputModality modality = blob == null ? null : mediaModality(blob.getMediaType());
    if (modality == null) {
      return new ResourceRead.Text(
          readTextWindow(blobId, offset, limit, columnOffset, displayPath, deadline));
    }
    if (blob.getState() != StorageBlobState.ACTIVE) {
      throw new PlatformReadException("resource is not available: " + blobId);
    }
    ModelRequestSpec spec = resolveFrozenSpec(store, invocationId, callId, threadId);
    ProviderMediaCapabilities capabilities = spec.providerType().mediaCapabilities();
    boolean modelDeclares = spec.model().inputModalities().contains(modality);
    boolean protocolAccepts =
        capabilities.supports(modality, true) || capabilities.supports(modality, false);
    if (!modelDeclares || !protocolAccepts) {
      throw new PlatformReadException(
          unsupportedMediaMessage(blob, modality, modelDeclares, protocolAccepts));
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
    ResourceRef ref =
        new ResourceRef(
            ResourceRef.sessionResourceUri(blobId),
            blob.getMediaType(),
            blobId.toString(),
            sizeBytes,
            blob.getSha256());
    return new ResourceRead.Media(ref, null);
  }

  private HarnessStore requireStore() {
    HarnessStore store = harnessStoreSupplier.get();
    if (store == null) {
      throw new PlatformReadException("harness store is unavailable");
    }
    return store;
  }

  /** Thread 存在且当前 Session 已引用该 Blob；否则以无法区分的错误拒绝。 */
  private void authorize(HarnessStore store, UUID threadId, UUID blobId) {
    Optional<ThreadState> threadState = store.transaction(tx -> tx.findThread(threadId));
    if (threadState.isEmpty()) {
      throw new PlatformReadException("thread not found: " + threadId);
    }
    UUID sessionId = threadState.get().sessionId();
    if (!sessionBlobRefManager.contains(sessionId, blobId)) {
      throw new PlatformReadException("resource is not referenced by this session");
    }
  }

  /** 通过调用工具槽位解析唯一冻结的调用模型 spec，并校验 thread 与 call 绑定。 */
  private static ModelRequestSpec resolveFrozenSpec(
      HarnessStore store, UUID invocationId, String callId, UUID threadId) {
    if (invocationId == null) {
      throw new PlatformReadException(
          "cannot resolve the calling model: tool invocation identity is unavailable");
    }
    return store.transaction(
        tx -> {
          ToolInvocation invocation = tx.findToolInvocation(invocationId).orElse(null);
          if (invocation == null) {
            throw new PlatformReadException("calling tool invocation not found: " + invocationId);
          }
          if (callId == null || !callId.equals(invocation.call().id())) {
            throw new PlatformReadException(
                "calling tool invocation does not match the requested call");
          }
          ModelInvocation model =
              tx.findModelInvocation(invocation.modelInvocationId()).orElse(null);
          if (model == null || !model.threadId().equals(threadId)) {
            throw new PlatformReadException("calling model invocation is not bound to this thread");
          }
          return model.requestSpec();
        });
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
                  ReadTextWindow.format(
                      stream, offset, limit, columnOffset, displayPath, "unsupported"));
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
