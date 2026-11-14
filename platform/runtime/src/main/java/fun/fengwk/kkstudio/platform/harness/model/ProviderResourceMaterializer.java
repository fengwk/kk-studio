package fun.fengwk.kkstudio.platform.harness.model;

import com.github.benmanes.caffeine.cache.Ticker;

import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

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
 * MIME。支持映射为 IMAGE/AUDIO/VIDEO 三种媒体块，DOCUMENT 只接受 {@code application/pdf}。
 *
 * <p>工具结果中的媒体优先使用协议原生位置：当 adapter 在 TOOL 位置支持该模态时直接投影为 tool result 内的真实 media 块。当 TOOL 位置不支持、但所选
 * model 声明该模态且 adapter 在 USER 位置支持时，保持同批全部 toolCallResult 配对完整，在该批 TOOL 结果之后追加一条 明确标注来源 toolCallId
 * 与 blobId 的 USER 媒体消息；tool result 内保留确定性文本事实。两个位置都不支持时显式失败，绝不把真实媒体静默 降级成文本描述。其余媒体类型、非 ACTIVE/缺失
 * Blob、外部化文本与用户普通内容能力不匹配保持既有确定性文本回退（图片在用户位置不支持时显式失败）。
 *
 * <p>本类只做模态/位置能力判定、消息顺序与 tool 批次编排；内容读取与预算记账在 {@link ProviderInlineMediaReader}，确定性文本在 {@link
 * ProviderResourceTextFormat}。内联受应用安全上限约束（单文件原始字节与单次 request 全部 data URI 字符总量，重复与嵌套引用同样计入），
 * 供应商侧能力与请求体积约束由各 adapter 独立负责。
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
  private final ProviderInlineMediaReader mediaReader;
  private final long maxRequestInlineChars;

  /**
   * @param defaultImageTier 未携带档位的图片资源使用的档位（系统设置 {@code media.imageTier} 的当前值）
   */
  public ProviderResourceMaterializer(
      StorageBlobManager blobManager,
      StorageBlobContentService blobContentService,
      Supplier<ImageInputTier> defaultImageTier) {
    this(
        blobManager,
        blobContentService,
        defaultImageTier,
        null,
        ProviderInlineBlobReader.Limits.DEFAULT,
        Ticker.systemTicker(),
        MAX_REQUEST_INLINE_CHARS);
  }

  public ProviderResourceMaterializer(
      StorageBlobManager blobManager,
      StorageBlobContentService blobContentService,
      Supplier<ImageInputTier> defaultImageTier,
      ProviderImageModelViewService imageModelViewService) {
    this(
        blobManager,
        blobContentService,
        defaultImageTier,
        Objects.requireNonNull(imageModelViewService, "imageModelViewService"),
        ProviderInlineBlobReader.Limits.DEFAULT,
        Ticker.systemTicker(),
        MAX_REQUEST_INLINE_CHARS);
  }

  /** 测试注入入口：以内联读取器上限、ticker 与 request 内联字符预算覆盖边界。 */
  ProviderResourceMaterializer(
      StorageBlobManager blobManager,
      StorageBlobContentService blobContentService,
      Supplier<ImageInputTier> defaultImageTier,
      ProviderInlineBlobReader.Limits limits,
      Ticker ticker,
      long maxRequestInlineChars) {
    this(
        blobManager,
        blobContentService,
        defaultImageTier,
        null,
        limits,
        ticker,
        maxRequestInlineChars);
  }

  private ProviderResourceMaterializer(
      StorageBlobManager blobManager,
      StorageBlobContentService blobContentService,
      Supplier<ImageInputTier> defaultImageTier,
      ProviderImageModelViewService imageModelViewService,
      ProviderInlineBlobReader.Limits limits,
      Ticker ticker,
      long maxRequestInlineChars) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.mediaReader =
        new ProviderInlineMediaReader(
            Objects.requireNonNull(blobContentService, "blobContentService"),
            defaultImageTier,
            imageModelViewService,
            limits,
            ticker);
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
    ProviderInlineMediaReader.Budget budget = mediaReader.newBudget(maxRequestInlineChars);
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
      ProviderInlineMediaReader.Budget budget) {
    List<ProviderContentBlock> contents = new ArrayList<>(message.contents().size());
    for (ProviderContentBlock content : message.contents()) {
      contents.addAll(
          materializePlainBlocks(
              content, inputModalities, mediaCapabilities, message.role(), budget));
    }
    if (contents.equals(message.contents())) {
      return message;
    }
    return new ProviderMessage(message.role(), List.copyOf(contents), message.reasoningEffort());
  }

  private List<ProviderContentBlock> materializePlainBlocks(
      ProviderContentBlock block,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderMessageRole role,
      ProviderInlineMediaReader.Budget budget) {
    if (!(block instanceof ProviderResourceBlock resource)) {
      return List.of(block);
    }
    return materializeUserResource(resource, inputModalities, mediaCapabilities, role, budget);
  }

  /**
   * 用户普通内容中的 Resource：只有 USER 角色的、model 与用户位置能力同时支持的媒体才内联；ASSISTANT/其他角色一律文本回退。
   *
   * <p>图片在用户位置不支持时显式失败（降级成文本会让模型误以为已看到图片内容）；非图片媒体保持既有文本回退。
   */
  private List<ProviderContentBlock> materializeUserResource(
      ProviderResourceBlock resource,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderMessageRole role,
      ProviderInlineMediaReader.Budget budget) {
    if (resource.isExternalizedText()) {
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.externalizedText(resource)));
    }
    StorageBlob blob = blobManager.getBlob(resource.blobId());
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      // 缺失或非 ACTIVE：绝不读取内容，也绝不暴露陈旧媒体事实。
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.fallback(resource, null)));
    }
    if (role != ProviderMessageRole.USER) {
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.fallback(resource, blob)));
    }
    ModelInputModality modality = modalityOf(blob.getMediaType());
    if (modality == null) {
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.fallback(resource, blob)));
    }
    if (!inputModalities.contains(modality) || !mediaCapabilities.supports(modality, false)) {
      if (modality == ModelInputModality.IMAGE) {
        throw new IllegalArgumentException(
            imageRejectedMessage(resource, blob, inputModalities, false));
      }
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.fallback(resource, blob)));
    }
    if (modality == ModelInputModality.IMAGE) {
      ProviderInlineMediaReader.Projection image = mediaReader.image(resource, blob, budget);
      return List.of(
          new ProviderTextBlock(imageLabel(resource.blobId(), image, null)), image.block());
    }
    return List.of(mediaReader.mediaBlock(resource, blob, modality, budget));
  }

  /** TOOL 消息的物化：结果内媒体优先走原生 TOOL 位置，否则暂存为该批次之后的一条 USER 媒体消息。 */
  private ProviderMessage materializeToolMessage(
      ProviderMessage message,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderInlineMediaReader.Budget budget,
      List<DeferredMedia> deferred) {
    List<ProviderContentBlock> contents = new ArrayList<>(message.contents().size());
    for (ProviderContentBlock content : message.contents()) {
      if (content instanceof ProviderToolResultBlock result) {
        List<ProviderContentBlock> nested = new ArrayList<>(result.contents().size());
        for (ProviderContentBlock block : result.contents()) {
          nested.addAll(
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
    return new ProviderMessage(message.role(), List.copyOf(contents), message.reasoningEffort());
  }

  private List<ProviderContentBlock> materializeToolResultBlock(
      ProviderContentBlock block,
      String toolCallId,
      Set<ModelInputModality> inputModalities,
      ProviderMediaCapabilities mediaCapabilities,
      ProviderInlineMediaReader.Budget budget,
      List<DeferredMedia> deferred) {
    if (!(block instanceof ProviderResourceBlock resource)) {
      return List.of(block);
    }
    if (resource.isExternalizedText()) {
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.externalizedText(resource)));
    }
    StorageBlob blob = blobManager.getBlob(resource.blobId());
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.fallback(resource, null)));
    }
    ModelInputModality modality = modalityOf(blob.getMediaType());
    if (modality == null) {
      // DOCUMENT 只接受 application/pdf：其它应用文件保持既有文本回退，绝不编造媒体。
      return List.of(new ProviderTextBlock(ProviderResourceTextFormat.fallback(resource, blob)));
    }
    boolean modelSupports = inputModalities.contains(modality);
    if (modelSupports && mediaCapabilities.supports(modality, true)) {
      if (modality == ModelInputModality.IMAGE) {
        ProviderInlineMediaReader.Projection image = mediaReader.image(resource, blob, budget);
        return List.of(
            new ProviderTextBlock(imageLabel(resource.blobId(), image, null)), image.block());
      }
      return List.of(mediaReader.mediaBlock(resource, blob, modality, budget));
    }
    if (modelSupports && mediaCapabilities.supports(modality, false)) {
      deferred.add(deferredMedia(resource, blob, modality, toolCallId, budget));
      return List.of(
          new ProviderTextBlock(ProviderResourceTextFormat.deferredToolMedia(resource, blob)));
    }
    throw new IllegalArgumentException(
        toolResourceRejectedMessage(resource, blob, inputModalities, mediaCapabilities));
  }

  /** 协议原生位置不可用的媒体：先按同一预算物化，再延迟到该批次之后的 USER 消息。 */
  private DeferredMedia deferredMedia(
      ProviderResourceBlock resource,
      StorageBlob blob,
      ModelInputModality modality,
      String toolCallId,
      ProviderInlineMediaReader.Budget budget) {
    if (modality == ModelInputModality.IMAGE) {
      ProviderInlineMediaReader.Projection image = mediaReader.image(resource, blob, budget);
      return new DeferredMedia(
          toolCallId,
          resource.blobId(),
          resource.name(),
          blob.getMediaType(),
          blob.getSizeBytes(),
          image.block(),
          image.dimensions());
    }
    return new DeferredMedia(
        toolCallId,
        resource.blobId(),
        resource.name(),
        blob.getMediaType(),
        blob.getSizeBytes(),
        mediaReader.mediaBlock(resource, blob, modality, budget),
        null);
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
      if (item.imageDimensions() != null) {
        blocks.add(
            new ProviderTextBlock(
                ProviderResourceTextFormat.imageLabel(
                    item.blobId(), item.imageDimensions(), item.toolCallId())));
      }
      blocks.add(item.media());
    }
    blocks.add(0, new ProviderTextBlock(note.toString()));
    result.add(new ProviderMessage(ProviderMessageRole.USER, List.copyOf(blocks)));
    deferred.clear();
  }

  private static String imageLabel(
      UUID blobId, ProviderInlineMediaReader.Projection image, String toolCallId) {
    return ProviderResourceTextFormat.imageLabel(blobId, image.dimensions(), toolCallId);
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

  /** 工具结果中因协议原生位置不可用而延迟到 USER 消息的媒体。 */
  private record DeferredMedia(
      String toolCallId,
      UUID blobId,
      String name,
      String mediaType,
      long sizeBytes,
      ProviderContentBlock media,
      ProviderInlineBlobReader.ImageDimensions imageDimensions) {}
}
