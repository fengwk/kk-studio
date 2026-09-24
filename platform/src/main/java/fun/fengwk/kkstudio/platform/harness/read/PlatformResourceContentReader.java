package fun.fengwk.kkstudio.platform.harness.read;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 平台侧 Session 授权 Blob 资源内容读取器。
 *
 * <p>按 {@code kkstudio:/resources/<blobId>} 读取当前会话授权的 Blob 文本内容：
 *
 * <ol>
 *   <li>通过 {@code threadId} 从 {@link HarnessStore} 解析关联的 {@code sessionId}；
 *   <li>校验当前 Session 是否已引用该 Blob（鉴权失败必须与不存在区分不了地拒绝，避免信息泄露）；
 *   <li>通过 {@link StorageBlobContentService} 流式读取 ACTIVE Blob，并在回调内格式化有界窗口。
 * </ol>
 */
public class PlatformResourceContentReader {

  private final Supplier<HarnessStore> harnessStoreSupplier;
  private final SessionBlobRefManager sessionBlobRefManager;
  private final StorageBlobContentService storageBlobContentService;

  public PlatformResourceContentReader(
      Supplier<HarnessStore> harnessStoreSupplier,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobContentService storageBlobContentService) {
    this.harnessStoreSupplier =
        Objects.requireNonNull(harnessStoreSupplier, "harnessStoreSupplier");
    this.sessionBlobRefManager =
        Objects.requireNonNull(sessionBlobRefManager, "sessionBlobRefManager");
    this.storageBlobContentService =
        Objects.requireNonNull(storageBlobContentService, "storageBlobContentService");
  }

  /**
   * 读取当前 Thread 对应 Session 授权的 Blob 文本窗口。
   *
   * @param threadId 当前 Harness Thread ID，非空
   * @param blobId 目标 Blob ID，非空
   * @return 格式化的文本窗口
   * @throws PlatformReadException Thread 不存在、Session 未引用该资源、资源不存在或读取失败时抛出
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

    HarnessStore store = harnessStoreSupplier.get();
    if (store == null) {
      throw new PlatformReadException("harness store is unavailable");
    }

    Optional<ThreadState> threadState = store.transaction(tx -> tx.findThread(threadId));
    if (threadState.isEmpty()) {
      throw new PlatformReadException("thread not found: " + threadId);
    }
    UUID sessionId = threadState.get().sessionId();

    if (!sessionBlobRefManager.contains(sessionId, blobId)) {
      throw new PlatformReadException("resource is not referenced by this session");
    }

    try {
      String text =
          storageBlobContentService.withBlobStream(
              blobId,
              stream ->
                  ReadTextWindow.format(
                      stream, offset, limit, columnOffset, displayPath, "unsupported"));
      if (text == null) {
        throw new PlatformReadException("resource content is unavailable: " + blobId);
      }
      return text;
    } catch (StorageResourceNotFoundException e) {
      throw new PlatformReadException("resource not found: " + blobId, e);
    } catch (PlatformReadException e) {
      throw e;
    } catch (Exception e) {
      throw new PlatformReadException(
          "failed to read resource: " + blobId + ": " + e.getMessage(), e);
    }
  }
}
