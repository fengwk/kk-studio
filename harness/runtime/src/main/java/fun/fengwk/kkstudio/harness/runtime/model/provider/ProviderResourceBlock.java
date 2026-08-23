package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * 携带全局 Blob 存储引用的 Provider 内容块（durable-safe：只含 blobId / name / preview，绝不含 URL）。
 *
 * <p>由 {@code ProviderMessageProjector} 从 durable {@code ResourceMessageContent} 投影；Provider
 * attempt 物化（platform 的 ProviderResourceMaterializer）在每次 attempt 时按 {@code storage_blob}
 * 事实把它转换为携带新鲜预签名 HTTPS URL 的 image/audio/video 块，或确定性文本回退——生成的 URL 只存在于本次 attempt 的有效请求，绝不持久化。
 */
public record ProviderResourceBlock(UUID blobId, String name, String preview)
    implements ProviderContentBlock {

  public ProviderResourceBlock {
    blobId = Objects.requireNonNull(blobId, "blobId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(preview, "preview");
  }
}
