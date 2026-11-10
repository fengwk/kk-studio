package fun.fengwk.kkstudio.harness.model.provider;

import fun.fengwk.kkstudio.harness.model.limits.DocumentRequestLimits;
import fun.fengwk.kkstudio.harness.model.limits.ImageRequestLimits;
import fun.fengwk.kkstudio.harness.model.limits.RequestLimits;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Provider 请求的最终编码与计量（PLAN 11.2）。
 *
 * <p>适配器先把请求编码为最终 UTF-8 body，再用本类计量 body 字节、bodyHash 与每个内联媒体的 RAW/BASE64 字节，并按端点 {@link
 * RequestLimits} 做发送前检查。HTTP 发送的正是 {@link #body()} 这份字节，计量与发送不会出现两次编码。
 *
 * <p>只检查已知限制：未知项缺省，依赖上游拒绝后的错误分类。图片边长需要像素事实，由媒体选择在物化前保证，不在此处解析图片。
 */
public final class PreparedProviderRequest {

  /** 媒体种类。 */
  public enum MediaKind {
    IMAGE,
    DOCUMENT,
    AUDIO,
    VIDEO
  }

  /**
   * 一个内联媒体的计量事实。
   *
   * @param kind 媒体种类
   * @param rawBytes 解码后的原始字节数
   * @param encodedBytes Base64 编码后的字节数
   */
  public record MediaFact(MediaKind kind, long rawBytes, long encodedBytes) {

    public MediaFact {
      Objects.requireNonNull(kind, "kind");
    }
  }

  private static final String BASE64_MARKER = ";base64,";

  private final byte[] body;
  private final String bodyHash;
  private final List<MediaFact> media;
  private final RequestLimits limits;

  private PreparedProviderRequest(
      byte[] body, String bodyHash, List<MediaFact> media, RequestLimits limits) {
    this.body = body;
    this.bodyHash = bodyHash;
    this.media = media;
    this.limits = limits;
  }

  /**
   * 计量已编码的请求。
   *
   * @param request 编码前的请求，用于枚举内联媒体
   * @param body 最终 UTF-8 body 字节；之后发送的必须是这份字节
   * @param limits 端点限制快照
   */
  public static PreparedProviderRequest prepare(
      ProviderRequest request, byte[] body, RequestLimits limits) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(limits, "limits");
    List<MediaFact> media = new ArrayList<>();
    for (ProviderMessage message : request.messages()) {
      collectMedia(message.contents(), media);
    }
    byte[] copy = body.clone();
    return new PreparedProviderRequest(copy, sha256(copy), List.copyOf(media), limits);
  }

  /** 发送用的最终 body 字节（副本）。 */
  public byte[] body() {
    return body.clone();
  }

  /** body 的 SHA-256（小写十六进制）。 */
  public String bodyHash() {
    return bodyHash;
  }

  /** body 字节数。 */
  public long bodyBytes() {
    return body.length;
  }

  /** 按出现顺序排列的内联媒体计量。 */
  public List<MediaFact> media() {
    return media;
  }

  /** 本次检查使用的限制快照。 */
  public RequestLimits limits() {
    return limits;
  }

  /**
   * 第一个被违反的已知限制；全部满足或均未知时为空。
   *
   * <p>body 超限归为 {@link ProviderErrorKind#REQUEST_BODY_LIMIT}，媒体数量与大小超限归为 {@link
   * ProviderErrorKind#MEDIA_LIMIT}。
   */
  public Optional<ProviderException> limitViolation() {
    if (limits.bodyMaxBytes() != null && bodyBytes() > limits.bodyMaxBytes()) {
      return violation(
          ProviderErrorKind.REQUEST_BODY_LIMIT,
          "request body " + bodyBytes() + " bytes exceeds " + limits.bodyMaxBytes() + " bytes");
    }
    Optional<ProviderException> image = imageViolation(limits.image());
    if (image.isPresent()) {
      return image;
    }
    return documentViolation(limits.document());
  }

  private Optional<ProviderException> imageViolation(ImageRequestLimits image) {
    List<MediaFact> images = ofKind(MediaKind.IMAGE);
    if (image.maxCount() != null && images.size() > image.maxCount()) {
      return media("request has " + images.size() + " images; limit is " + image.maxCount());
    }
    long totalEncoded = 0;
    for (MediaFact fact : images) {
      if (image.singleRawBytes() != null && fact.rawBytes() > image.singleRawBytes()) {
        return media("image " + fact.rawBytes() + " bytes exceeds " + image.singleRawBytes());
      }
      if (image.singleEncodedBytes() != null && fact.encodedBytes() > image.singleEncodedBytes()) {
        return media(
            "image base64 " + fact.encodedBytes() + " bytes exceeds " + image.singleEncodedBytes());
      }
      totalEncoded += fact.encodedBytes();
    }
    if (image.totalBytes() != null && totalEncoded > image.totalBytes()) {
      return media("images total " + totalEncoded + " bytes exceeds " + image.totalBytes());
    }
    return Optional.empty();
  }

  private Optional<ProviderException> documentViolation(DocumentRequestLimits document) {
    long totalRaw = 0;
    for (MediaFact fact : ofKind(MediaKind.DOCUMENT)) {
      if (document.singleRawBytes() != null && fact.rawBytes() > document.singleRawBytes()) {
        return media("document " + fact.rawBytes() + " bytes exceeds " + document.singleRawBytes());
      }
      totalRaw += fact.rawBytes();
    }
    if (document.totalRawBytes() != null && totalRaw > document.totalRawBytes()) {
      return media("documents total " + totalRaw + " bytes exceeds " + document.totalRawBytes());
    }
    return Optional.empty();
  }

  private List<MediaFact> ofKind(MediaKind kind) {
    return media.stream().filter(fact -> fact.kind() == kind).toList();
  }

  private static Optional<ProviderException> media(String message) {
    return violation(ProviderErrorKind.MEDIA_LIMIT, message);
  }

  private static Optional<ProviderException> violation(ProviderErrorKind kind, String message) {
    return Optional.of(new ProviderException(kind, message));
  }

  private static void collectMedia(List<ProviderContentBlock> contents, List<MediaFact> media) {
    for (ProviderContentBlock block : contents) {
      switch (block) {
        case ProviderImageBlock image -> media.add(fact(MediaKind.IMAGE, image.source()));
        case ProviderDocumentBlock document -> media.add(
            fact(MediaKind.DOCUMENT, document.source()));
        case ProviderAudioBlock audio -> media.add(fact(MediaKind.AUDIO, audio.source()));
        case ProviderVideoBlock video -> media.add(fact(MediaKind.VIDEO, video.source()));
        case ProviderToolResultBlock result -> collectMedia(result.contents(), media);
        default -> {
          // 文本、思考、工具调用等不是内联媒体。
        }
      }
    }
  }

  /** 内联媒体都是 Base64 data URI；其他形态的来源在编码阶段已被拒绝，这里只按字符数计入编码字节。 */
  private static MediaFact fact(MediaKind kind, String source) {
    int marker = source.startsWith("data:") ? source.indexOf(BASE64_MARKER) : -1;
    if (marker < 0) {
      return new MediaFact(kind, 0, source.length());
    }
    int payloadLength = source.length() - marker - BASE64_MARKER.length();
    return new MediaFact(kind, decodedLength(source, payloadLength), payloadLength);
  }

  /** Base64 解码长度：按长度与 padding 计算，不分配解码缓冲。 */
  private static long decodedLength(String source, int payloadLength) {
    int padding = 0;
    if (source.endsWith("==")) {
      padding = 2;
    } else if (source.endsWith("=")) {
      padding = 1;
    }
    return payloadLength * 3L / 4 - padding;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
