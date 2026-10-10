package fun.fengwk.kkstudio.platform.harness.model;

import com.github.benmanes.caffeine.cache.Ticker;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
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
import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
 * ProviderDocumentBlock}。
 *
 * <p>工具结果中的媒体优先使用协议原生位置：当 adapter 在 TOOL 位置支持该模态时直接投影为 tool result 内的真实 media 块。当 TOOL 位置不支持、但所选
 * model 声明该模态且 adapter 在 USER 位置支持时，保持同批全部 toolCallResult 配对完整，在该批 TOOL 结果之后追加一条 明确标注来源 toolCallId
 * 与 blobId 的 USER 媒体消息；tool result 内保留确定性文本事实。两个位置都不支持时显式失败，绝不把真实媒体静默 降级成文本描述。其余媒体类型、非 ACTIVE/缺失
 * Blob、外部化文本与用户普通内容能力不匹配保持既有确定性文本回退（图片在用户位置不支持时显式失败）。
 *
 * <p>内联受应用安全上限约束（单文件原始字节与单次 request 全部 data URI 字符总量，重复与嵌套引用同样计入），由 {@link
 * ProviderInlineBlobReader} 执行有界读取、校验与缓存；供应商侧能力与请求体积约束由各 adapter 独立负责。
 *
 * <p>这些瞬时 source 绝不持久化（durable invocation request 只保存 {@link ProviderResourceBlock}）。
 */
public final class ProviderResourceMaterializer {

  /** 单次 attempt 允许生成的 data URI 字符总量：应用侧安全闸门，与任何 Provider 能力或供应商限制无关。 */
  static final long MAX_REQUEST_INLINE_CHARS = 160L * 1024L * 1024L;

  /** 单文件内联原始字节上限（与 attempt 物化实际使用的 {@link ProviderInlineBlobReader.Limits} 一致）。 */
  public static long maxInlineBlobBytes() {
    return ProviderInlineBlobReader.Limits.DEFAULT.maxBlobBytes();
  }

  /** 单次 attempt 允许生成的 data URI 字符总量上限。 */
  public static long maxInlineRequestChars() {
    return MAX_REQUEST_INLINE_CHARS;
  }

  /** 估算给定 MIME 与字节数的内联 data URI 字符数（与 attempt 物化使用的计算严格一致）。 */
  public static long estimatedInlineChars(String mediaType, long sizeBytes) {
    return ProviderInlineBlobReader.estimatedDataUriChars(mediaType, sizeBytes);
  }

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
   * 把请求消息中的 Resource 块物化为本次 attempt 的有效内容块；消息顺序保持不变。
   *
   * <p>连续 TOOL 消息组成一个 tool 批次：当其中媒体的协议原生位置不可用而用户位置可用时，在该批次全部 TOOL 结果之后追加一条标注来源的 USER 媒体 消息，因此后续
   * TOOL 结果始终与其 assistant 调用保持原生相邻。
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
    List<DeferredMedia> deferred = new ArrayList<>();
    boolean inToolBatch = false;
    for (ProviderMessage message : messages) {
      if (message.role() == ProviderMessageRole.TOOL) {
        inToolBatch = true;
        result.add(
            materializeToolMessage(message, inputModalities, mediaCapabilities, budget, deferred));
        continue;
      }
      if (inToolBatch) {
        flushDeferredMedia(deferred, result);
        inToolBatch = false;
      }
      result.add(materializePlainMessage(message, inputModalities, mediaCapabilities, budget));
    }
    if (inToolBatch) {
      flushDeferredMedia(deferred, result);
    }
    return List.copyOf(result);
  }

  /** USER/ASSISTANT 消息的物化：只替换 Resource 块，assistant 的 native replay state 原样穿过。 */
  private ProviderMessage materializePlainMessage(
      ProviderMessage message,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      InlineBudget budget) {
    List<ProviderContentBlock> contents = new ArrayList<>(message.contents().size());
    for (ProviderContentBlock content : message.contents()) {
      contents.add(
          materializePlainBlock(
              content, inputModalities, mediaCapabilities, message.role(), budget));
    }
    if (contents.equals(message.contents())) {
      return message;
    }
    return new ProviderMessage(message.role(), List.copyOf(contents), message.replayState());
  }

  private ProviderContentBlock materializePlainBlock(
      ProviderContentBlock block,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderMessageRole role,
      InlineBudget budget) {
    if (!(block instanceof ProviderResourceBlock resource)) {
      return block;
    }
    return materializeUserResource(resource, inputModalities, mediaCapabilities, role, budget);
  }

  /**
   * 用户普通内容中的 Resource：只有 USER 角色的、model 与用户位置能力同时支持的媒体才内联；ASSISTANT/其他角色一律文本回退。
   *
   * <p>图片在用户位置不支持时显式失败（降级成文本会让模型误以为已看到图片内容）；非图片媒体保持既有文本回退。
   */
  private ProviderContentBlock materializeUserResource(
      ProviderResourceBlock resource,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderMessageRole role,
      InlineBudget budget) {
    if (resource.isExternalizedText()) {
      return new ProviderTextBlock(formatExternalizedText(resource));
    }
    StorageBlob blob = blobManager.getBlob(resource.blobId());
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      // 缺失或非 ACTIVE：绝不读取内容，也绝不暴露陈旧媒体事实。
      return new ProviderTextBlock(fallbackText(resource, null));
    }
    if (role != ProviderMessageRole.USER) {
      return new ProviderTextBlock(fallbackText(resource, blob));
    }
    ModelInputModality modality = modalityOf(blob.getMediaType());
    if (modality == null) {
      return new ProviderTextBlock(fallbackText(resource, blob));
    }
    if (!inputModalities.contains(modality) || !mediaCapabilities.supports(modality, false)) {
      if (modality == ModelInputModality.IMAGE) {
        throw new IllegalArgumentException(
            imageRejectedMessage(resource, blob, inputModalities, false));
      }
      return new ProviderTextBlock(fallbackText(resource, blob));
    }
    return inlineMediaBlock(resource, blob, modality, budget);
  }

  /** TOOL 消息的物化：结果内媒体优先走原生 TOOL 位置，否则暂存为该批次之后的一条 USER 媒体消息。 */
  private ProviderMessage materializeToolMessage(
      ProviderMessage message,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      InlineBudget budget,
      List<DeferredMedia> deferred) {
    List<ProviderContentBlock> contents = new ArrayList<>(message.contents().size());
    for (ProviderContentBlock content : message.contents()) {
      if (content instanceof ProviderToolResultBlock result) {
        List<ProviderContentBlock> nested = new ArrayList<>(result.contents().size());
        for (ProviderContentBlock block : result.contents()) {
          nested.add(
              materializeToolResultBlock(
                  block,
                  result.toolCallId(),
                  inputModalities,
                  mediaCapabilities,
                  budget,
                  deferred));
        }
        if (nested.equals(result.contents())) {
          contents.add(result);
        } else {
          contents.add(
              new ProviderToolResultBlock(
                  result.toolCallId(),
                  result.toolName(),
                  List.copyOf(nested),
                  result.error(),
                  result.detailsJson()));
        }
      } else {
        contents.add(content);
      }
    }
    if (contents.equals(message.contents())) {
      return message;
    }
    return new ProviderMessage(message.role(), List.copyOf(contents), message.replayState());
  }

  private ProviderContentBlock materializeToolResultBlock(
      ProviderContentBlock block,
      String toolCallId,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      InlineBudget budget,
      List<DeferredMedia> deferred) {
    if (!(block instanceof ProviderResourceBlock resource)) {
      return block;
    }
    if (resource.isExternalizedText()) {
      return new ProviderTextBlock(formatExternalizedText(resource));
    }
    StorageBlob blob = blobManager.getBlob(resource.blobId());
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      return new ProviderTextBlock(fallbackText(resource, null));
    }
    ModelInputModality modality = modalityOf(blob.getMediaType());
    if (modality == null) {
      // DOCUMENT 只接受 application/pdf：其它应用文件保持既有文本回退，绝不编造媒体。
      return new ProviderTextBlock(fallbackText(resource, blob));
    }
    boolean modelSupports = inputModalities.contains(modality);
    if (modelSupports && mediaCapabilities.supports(modality, true)) {
      return inlineMediaBlock(resource, blob, modality, budget);
    }
    if (modelSupports && mediaCapabilities.supports(modality, false)) {
      ProviderContentBlock media = inlineMediaBlock(resource, blob, modality, budget);
      deferred.add(
          new DeferredMedia(
              toolCallId,
              resource.blobId(),
              resource.name(),
              blob.getMediaType(),
              blob.getSizeBytes(),
              media));
      return new ProviderTextBlock(deferredToolMediaText(resource, blob));
    }
    throw new IllegalArgumentException(
        toolResourceRejectedMessage(resource, blob, inputModalities, mediaCapabilities));
  }

  /** 在 tool 批次之后追加一条 USER 媒体消息：文本说明每项的真实来源 toolCallId 与 blobId，随后是真实 media 块。 */
  private static void flushDeferredMedia(
      List<DeferredMedia> deferred, List<ProviderMessage> result) {
    if (deferred.isEmpty()) {
      return;
    }
    StringBuilder note =
        new StringBuilder(
            "Tool result media from the preceding tool call(s) is delivered as user content. Sources:");
    List<ProviderContentBlock> blocks = new ArrayList<>(deferred.size() + 1);
    for (DeferredMedia item : deferred) {
      note.append("\n- tool call ")
          .append(item.toolCallId())
          .append(", resource ")
          .append(item.blobId())
          .append(" (")
          .append(item.name())
          .append(", ")
          .append(item.mediaType())
          .append(", ")
          .append(item.sizeBytes())
          .append(" bytes)");
      blocks.add(item.media());
    }
    blocks.add(0, new ProviderTextBlock(note.toString()));
    result.add(new ProviderMessage(ProviderMessageRole.USER, List.copyOf(blocks)));
    deferred.clear();
  }

  /**
   * 仅在 model 模态与 adapter 位置能力同时支持时才读取内容并构造媒体块。
   *
   * <p>调用方已完成位置能力判定；本方法只做单文件大小与 request 预算校验、有界读取与块构造。
   */
  private ProviderContentBlock inlineMediaBlock(
      ProviderResourceBlock resource,
      StorageBlob blob,
      ModelInputModality modality,
      InlineBudget budget) {
    ProviderInlineBlobReader.Limits limits = blobReader.limits();
    if (blob.getSizeBytes() < 0 || blob.getSizeBytes() > limits.maxBlobBytes()) {
      throw new IllegalArgumentException(
          "blob size "
              + blob.getSizeBytes()
              + " bytes is outside the allowed inline range 0.."
              + limits.maxBlobBytes()
              + " bytes");
    }
    if (modality == ModelInputModality.IMAGE) {
      return inlineImage(resource, blob, budget);
    }
    long required =
        ProviderInlineBlobReader.estimatedDataUriChars(blob.getMediaType(), blob.getSizeBytes());
    // 先按完整 Base64 长度记账再读取；读取器校验实际长度，任一失败都会终止整个 attempt 物化。
    budget.reserve(required);
    String source =
        blobReader.readDataUri(
            resource.blobId(), blob.getMediaType(), blob.getSizeBytes(), required);
    return switch (modality) {
      case AUDIO -> new ProviderAudioBlock(blob.getMediaType(), source);
      case VIDEO -> new ProviderVideoBlock(blob.getMediaType(), source);
      case DOCUMENT -> new ProviderDocumentBlock(blob.getMediaType(), source);
      case TEXT, IMAGE -> null;
    };
  }

  /**
   * 图片按冻结档位物化：{@link ImageInputTier#ORIGINAL} 与未指定档位的媒体一样保留原字节，字符数由声明事实确定，因此先记账再读取；
   * 缩放档位的实际字节数只有读取完成后才知道，先把本次剩余预算交给读取器，再按实际 data URI 字符数记账。
   */
  private ProviderImageBlock inlineImage(
      ProviderResourceBlock resource, StorageBlob blob, InlineBudget budget) {
    // 档位在命令接受事务中冻结；历史数据可能没有档位，按平台默认 720P 处理。
    ImageInputTier tier = resource.imageTier() == null ? ImageInputTier.P720 : resource.imageTier();
    boolean original = tier.isOriginal();
    long allowedChars =
        original
            ? ProviderInlineBlobReader.estimatedDataUriChars(
                blob.getMediaType(), blob.getSizeBytes())
            : budget.remaining();
    if (original) {
      budget.reserve(allowedChars);
    }
    ProviderInlineBlobReader.InlineBlob inline =
        blobReader.readImage(
            resource.blobId(), blob.getMediaType(), blob.getSizeBytes(), tier, allowedChars);
    if (!original) {
      budget.reserve(inline.dataUri().length());
    }
    return new ProviderImageBlock(inline.mediaType(), inline.dataUri());
  }

  /** 图片无法送达用户位置时的显式失败信息：只包含资源名、MIME 与位置，不包含任何内容。 */
  private static String imageRejectedMessage(
      ProviderResourceBlock resource,
      StorageBlob blob,
      Set<ModelInputModality> inputModalities,
      boolean toolResult) {
    String reason =
        !inputModalities.contains(ModelInputModality.IMAGE)
            ? "the selected model does not declare IMAGE input"
            : "the current adapter does not accept IMAGE in "
                + (toolResult ? "tool results" : "user messages");
    return "image resource "
        + resource.name()
        + " ("
        + blob.getMediaType()
        + ") cannot be sent: "
        + reason
        + "; it must not be silently degraded to a text description";
  }

  /** 工具结果媒体两个位置都不支持时的显式失败信息：只包含资源名、MIME 与原因，不包含任何内容。 */
  private static String toolResourceRejectedMessage(
      ProviderResourceBlock resource,
      StorageBlob blob,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities) {
    ModelInputModality modality = modalityOf(blob.getMediaType());
    String reason;
    if (modality == null || !inputModalities.contains(modality)) {
      reason = "the selected model does not declare " + modality + " input";
    } else {
      reason =
          "the current adapter accepts " + modality + " in neither user messages nor tool results";
    }
    return "tool result resource "
        + resource.name()
        + " ("
        + blob.getMediaType()
        + ") cannot be sent: "
        + reason
        + "; it must not be silently degraded to a text description";
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

  /**
   * 外部化文本的模型声明：稳定 Session 资源 URI、名字、总量、不完整预览，以及用 read 分页读取完整输出的指引。
   *
   * <p>URI 由 {@link SessionResourceUri#format} 从 blobId 派生，只暴露规范 {@code
   * kkstudio:/resources/<blobId>}，绝不包含 S3 object key、上传 id 或任何宿主路径；URI 只是标识，实际读取仍走原有 Session Blob
   * 鉴权。
   */
  private static String formatExternalizedText(ProviderResourceBlock resource) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "[Output externalized. The preview below is incomplete; do not treat it as the full"
            + " result.\n\n");
    sb.append("The complete output is available as a session resource:\n")
        .append(SessionResourceUri.format(resource.blobId()))
        .append("\n");
    sb.append("Name: ").append(resource.name()).append("\n");
    sb.append("Size: ")
        .append(resource.totalBytes())
        .append(" bytes, ")
        .append(resource.totalLines())
        .append(" lines\n");
    sb.append(
        "Use the read tool with this resource URI and offset/limit to page through the complete"
            + " output.]");
    sb.append("\n\n--- preview ---");
    if (resource.preview() != null && !resource.preview().isEmpty()) {
      sb.append("\n").append(resource.preview());
    }
    return sb.toString();
  }

  /** 被移动到后续 USER 消息的工具结果媒体在 tool result 内保留的确定性文本事实。 */
  private static String deferredToolMediaText(ProviderResourceBlock resource, StorageBlob blob) {
    return fallbackText(resource, blob)
        + "\nThe media is delivered to you as a separate user message after this tool result.";
  }

  /**
   * 确定性文本回退：始终包含 name 与可读 Session 资源 URI；durable preview 非空时始终附带；mediaType/size 只在存在 ACTIVE storage
   * 事实时附带。
   */
  private static String fallbackText(ProviderResourceBlock resource, StorageBlob blob) {
    StringBuilder text = new StringBuilder("[Resource: ").append(resource.name()).append("]");
    text.append("\nuri: ").append(SessionResourceUri.format(resource.blobId()));
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

    private long remaining() {
      return maxChars - usedChars;
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

  /** 工具结果中因协议原生位置不可用而延迟到 USER 消息的媒体：保留真实来源与已构造的 attempt-only media 块。 */
  private record DeferredMedia(
      String toolCallId,
      UUID blobId,
      String name,
      String mediaType,
      long sizeBytes,
      ProviderContentBlock media) {}
}
