package fun.fengwk.kkstudio.platform.harness.model;

import com.github.benmanes.caffeine.cache.Ticker;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider attempt 的 Resource 物化：把 durable-safe 的 {@link
 * ProviderResourceBlock}（blobId/name/preview）按当前所选 model 的 {@code inputModalities}、当前 adapter 的
 * {@link ProviderMediaCapabilities} 与 {@code storage_blob} 事实转换为本次 attempt 的有效 provider 内容。
 *
 * <p>每次 attempt 调用（provider 解析阶段，PlatformModelGateway 事务外）只把「用户普通内容」与「TOOL provider tool result
 * 的内容」中的支持的媒体读取为 attempt-only {@code data:<mime>;base64,...}。远端 Provider 无法访问本地或私网存储， 因此本边界绝不产生
 * URL，也不调用任何预签名能力；SYSTEM/ASSISTANT 消息中的 Resource 一律退化为确定性文本。
 *
 * <p>模态判定取三个条件的交集：所选 model 的 {@code inputModalities}、当前 adapter 针对该位置的用户/工具结果能力，以及 Blob 权威
 * MIME。支持映射为 IMAGE/AUDIO/VIDEO 三种媒体块，DOCUMENT 只接受 {@code application/pdf} 并生成 {@link
 * ProviderDocumentBlock}；其余媒体类型、非 ACTIVE/缺失 Blob、外部化文本与能力不匹配都生成确定性文本回退（含
 * name/blobId/mediaType/size/preview），绝不读取或签名存储内容。
 *
 * <p>内联受应用安全上限约束（单文件原始字节与单次 request 全部 data URI 字符总量，重复与嵌套引用同样计入），由 {@link
 * ProviderInlineBlobReader} 执行有界读取、校验与缓存；供应商侧能力与请求体积约束由各 adapter 独立负责。
 *
 * <p>这些瞬时 source 绝不持久化（durable invocation request 只保存 {@link ProviderResourceBlock}）。
 */
public final class ProviderResourceMaterializer {

  /** 单次 attempt 允许生成的 data URI 字符总量：应用侧安全闸门，与任何 Provider 能力或供应商限制无关。 */
  static final long MAX_REQUEST_INLINE_CHARS = 160L * 1024L * 1024L;

  private final StorageBlobManager blobManager;
  private final ProviderInlineBlobReader blobReader;
  private final long maxRequestInlineChars;

  public ProviderResourceMaterializer(
      StorageBlobManager blobManager, StorageBlobContentService blobContentService) {
    this(
        blobManager,
        blobContentService,
        ProviderInlineBlobReader.Limits.DEFAULT,
        Ticker.systemTicker(),
        MAX_REQUEST_INLINE_CHARS);
  }

  /** 测试注入入口：以内联读取器上限、ticker 与 request 内联字符预算覆盖边界。 */
  ProviderResourceMaterializer(
      StorageBlobManager blobManager,
      StorageBlobContentService blobContentService,
      ProviderInlineBlobReader.Limits limits,
      Ticker ticker,
      long maxRequestInlineChars) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.blobReader =
        new ProviderInlineBlobReader(
            Objects.requireNonNull(blobContentService, "blobContentService"), limits, ticker);
    if (maxRequestInlineChars <= 0L) {
      throw new IllegalArgumentException("maxRequestInlineChars must be positive");
    }
    this.maxRequestInlineChars = maxRequestInlineChars;
  }

  /**
   * 把请求消息中的 Resource 块物化为本次 attempt 的有效内容块；消息与块顺序保持不变。
   *
   * @param mediaCapabilities 当前 adapter 声明的内联媒体能力；未声明时只产生文本回退
   */
  public List<ProviderMessage> materialize(
      List<ProviderMessage> messages,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(inputModalities, "inputModalities");
    Objects.requireNonNull(mediaCapabilities, "mediaCapabilities");
    InlineBudget budget = new InlineBudget(maxRequestInlineChars);
    List<ProviderMessage> result = new ArrayList<>(messages.size());
    for (ProviderMessage message : messages) {
      // 物化只替换 Resource 块：assistant 的 native replay state 必须原样穿过本边界（本类不解析其 payload）。
      result.add(
          new ProviderMessage(
              message.role(),
              materializeContents(
                  message.contents(),
                  inputModalities,
                  mediaCapabilities,
                  message.role(),
                  false,
                  budget),
              message.replayState()));
    }
    return List.copyOf(result);
  }

  private List<ProviderContentBlock> materializeContents(
      List<ProviderContentBlock> contents,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderMessageRole role,
      boolean toolResult,
      InlineBudget budget) {
    List<ProviderContentBlock> result = new ArrayList<>(contents.size());
    for (ProviderContentBlock content : contents) {
      result.add(
          materializeBlock(content, inputModalities, mediaCapabilities, role, toolResult, budget));
    }
    return List.copyOf(result);
  }

  private ProviderContentBlock materializeBlock(
      ProviderContentBlock block,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderMessageRole role,
      boolean toolResult,
      InlineBudget budget) {
    if (block instanceof ProviderToolResultBlock result) {
      List<ProviderContentBlock> contents =
          materializeContents(
              result.contents(), inputModalities, mediaCapabilities, role, true, budget);
      if (contents.equals(result.contents())) {
        return block;
      }
      return new ProviderToolResultBlock(
          result.toolCallId(), result.toolName(), contents, result.error(), result.detailsJson());
    }
    if (!(block instanceof ProviderResourceBlock resource)) {
      return block;
    }
    if (resource.isExternalizedText()) {
      return new ProviderTextBlock(formatExternalizedText(resource));
    }
    StorageBlob blob = blobManager.getBlob(resource.blobId());
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      // 缺失或非 ACTIVE：绝不读取内容，也绝不暴露陈旧媒体事实。
      return new ProviderTextBlock(fallbackText(resource, null));
    }
    boolean mediaAllowed =
        (role == ProviderMessageRole.USER && !toolResult)
            || (role == ProviderMessageRole.TOOL && toolResult);
    if (!mediaAllowed) {
      return new ProviderTextBlock(fallbackText(resource, blob));
    }
    ProviderContentBlock media =
        inlineMedia(resource, blob, inputModalities, mediaCapabilities, toolResult, budget);
    return media != null ? media : new ProviderTextBlock(fallbackText(resource, blob));
  }

  /** 仅在 model 模态与 adapter 位置能力同时支持时才读取内容；返回 null 表示必须走文本回退。 */
  private ProviderContentBlock inlineMedia(
      ProviderResourceBlock resource,
      StorageBlob blob,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      boolean toolResult,
      InlineBudget budget) {
    ModelInputModality modality = modalityOf(blob.getMediaType());
    if (modality == null
        || !inputModalities.contains(modality)
        || !mediaCapabilities.supports(modality, toolResult)) {
      return null;
    }
    ProviderInlineBlobReader.Limits limits = blobReader.limits();
    if (blob.getSizeBytes() < 0 || blob.getSizeBytes() > limits.maxBlobBytes()) {
      throw new IllegalArgumentException(
          "blob size "
              + blob.getSizeBytes()
              + " bytes is outside the allowed inline range 0.."
              + limits.maxBlobBytes()
              + " bytes");
    }
    long required =
        ProviderInlineBlobReader.estimatedDataUriChars(blob.getMediaType(), blob.getSizeBytes());
    // 先按完整 Base64 长度记账再读取；读取器校验实际长度，任一失败都会终止整个 attempt 物化。
    budget.reserve(required);
    String source =
        blobReader.readDataUri(
            resource.blobId(), blob.getMediaType(), blob.getSizeBytes(), required);
    return switch (modality) {
      case IMAGE -> new ProviderImageBlock(blob.getMediaType(), source);
      case AUDIO -> new ProviderAudioBlock(blob.getMediaType(), source);
      case VIDEO -> new ProviderVideoBlock(blob.getMediaType(), source);
      case DOCUMENT -> new ProviderDocumentBlock(blob.getMediaType(), source);
      case TEXT -> null;
    };
  }

  /** Blob MIME 到输入模态的映射；未支持类型返回 null（继续走确定性文本回退）。 */
  private static ModelInputModality modalityOf(String mediaType) {
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
    // DOCUMENT 只接受 PDF：不做通用 document 编造。
    if ("application/pdf".equals(mediaType)) {
      return ModelInputModality.DOCUMENT;
    }
    return null;
  }

  private static String formatExternalizedText(ProviderResourceBlock resource) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "[Output externalized. The preview below is incomplete; do not treat it as the full"
            + " result.\n\n");
    sb.append("The complete output has been saved as a downloadable user attachment: ")
        .append(resource.name())
        .append("\n");
    sb.append("Size: ")
        .append(resource.totalBytes())
        .append(" bytes, ")
        .append(resource.totalLines())
        .append(" lines]");
    sb.append("\n\n--- preview ---");
    if (resource.preview() != null && !resource.preview().isEmpty()) {
      sb.append("\n").append(resource.preview());
    }
    return sb.toString();
  }

  /** 确定性文本回退：始终包含 name/blobId；durable preview 非空时始终附带；mediaType/size 只在存在 ACTIVE storage 事实时附带。 */
  private static String fallbackText(ProviderResourceBlock resource, StorageBlob blob) {
    StringBuilder text = new StringBuilder("[Resource: ").append(resource.name()).append("]");
    text.append("\nblobId: ").append(resource.blobId());
    if (blob != null && blob.getState() == StorageBlobState.ACTIVE) {
      if (blob.getMediaType() != null && !blob.getMediaType().isBlank()) {
        text.append("\nmediaType: ").append(blob.getMediaType());
      }
      text.append("\nsize: ").append(blob.getSizeBytes());
    }
    if (!resource.preview().isEmpty()) {
      text.append("\npreview: ").append(resource.preview());
    }
    return text.toString();
  }

  /** 单次 attempt 的内联字符预算：重复与嵌套引用同样计入，attempt 结束后不残留。 */
  private static final class InlineBudget {

    private final long maxChars;
    private long usedChars;

    private InlineBudget(long maxChars) {
      this.maxChars = maxChars;
    }

    private void reserve(long chars) {
      if (chars > maxChars - usedChars) {
        throw new IllegalArgumentException(
            "request inline data URI payload must not exceed "
                + maxChars
                + " characters; requires at least "
                + chars
                + " more characters");
      }
      usedChars += chars;
    }
  }
}
