package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Terminal {@link ToolResult} 的结果级统一终态化器（{@code ToolResultFinalizer}）。
 *
 * <p>替换原有的 per-content 双截断 externalizer：
 *
 * <ul>
 *   <li>按原始 content 顺序用两个换行（{@code \n\n}）拼接所有 Text/Json 内容，形成唯一 canonical textual projection；
 *   <li>若 projection 同时满足内联阈值（&le; 50 KiB 且 &le; 2000 物理行），原样内联保留；
 *   <li>任一阈值超出时，将完整 projection 存为一个 managed Resource，删除所有原 Text/Json 内容， 并在第一个原文本位置插入携带小 raw
 *       preview（&le; 2 KiB 且 &le; 20 行）和 {@link TextArtifactMetadata} 的 {@link
 *       ResourceResultContent}，其余非文本 Resource 顺序保持不变；
 *   <li>所有 {@link BinaryResultContent} 外部化为 managed Resource；已有 Resource 必须由同一 ResourceStore
 *       管理并通过完整性读取复核后才透传；文本工件的 preview 只从受信 bytes 重新计算；
 *   <li>All-or-nothing plan：第一个 put 之前完成所有确定性校验、全文有界测量、硬上限（默认 16 MiB，超限报 {@code
 *       OUTPUT_TOO_LARGE}）、元数据规划与投影 canonical JSON 尺寸（&le; 1 MiB）验证；
 *   <li>存储失败或契约违反收敛为 {@code UNKNOWN / RESOURCE_STORE_FAILED}。
 * </ul>
 */
public final class ToolResultFinalizer {

  /** terminal textual projection 的完整内联字节上限（50 KiB）。 */
  public static final int INLINE_MAX_UTF8_BYTES = 50 * 1024;

  /** terminal textual projection 的完整内联物理行数上限（2000 行）。 */
  public static final int INLINE_MAX_LINES = 2000;

  /** 大输出 raw preview 的 UTF-8 字节上限（2 KiB）。 */
  public static final int TRUNCATED_PREVIEW_MAX_UTF8_BYTES = 2 * 1024;

  /** 大输出 raw preview 的物理行数上限（20 行）。 */
  public static final int TRUNCATED_PREVIEW_MAX_LINES = 20;

  /** 单次可持久化 Tool Artifact 的默认硬上限（16 MiB）。 */
  public static final int DEFAULT_RESOURCE_MAX_BYTES = 16 * 1024 * 1024;

  /** 外部化后的 canonical ToolResult JSON 上限（1 MiB）。 */
  public static final int MAX_TERMINAL_RESULT_UTF8_BYTES =
      ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES;

  /** 单条 live partial 上限（256 KiB）。 */
  public static final int MAX_PARTIAL_RESULT_UTF8_BYTES =
      ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES;

  public static final String INVALID_RESULT_KIND = "INVALID_RESULT";
  public static final String OUTPUT_TOO_LARGE_KIND = "OUTPUT_TOO_LARGE";
  public static final String RESOURCE_STORE_FAILED_KIND = "RESOURCE_STORE_FAILED";

  private static final String RESOURCE_STORE_FAILED_MESSAGE =
      "Tool result resource persistence failed; outcome is unknown.";

  private static final Pattern CANONICAL_MEDIA_TYPE =
      Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");

  private final ResourceStore resourceStore;
  private final int resourceMaxBytes;

  public ToolResultFinalizer(ResourceStore resourceStore) {
    this(resourceStore, DEFAULT_RESOURCE_MAX_BYTES);
  }

  public ToolResultFinalizer(ResourceStore resourceStore, int resourceMaxBytes) {
    this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
    if (resourceMaxBytes <= 0) {
      throw new IllegalArgumentException("resourceMaxBytes must be positive");
    }
    this.resourceMaxBytes = resourceMaxBytes;
  }

  /**
   * 全有或全无的终态化处理：先计算完整投影计划并在首个 put 前完成所有尺寸与格式校验，然后执行必要存储并核对返回引用。
   *
   * @param toolName 工具名称
   * @param result 待终态化的工具结果
   * @return 终态化结果
   */
  public Outcome finalizeResult(String toolName, ToolResult result) {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(result, "result");

    Plan plan;
    try {
      plan = plan(toolName, result);
    } catch (OutputTooLargeException tooLarge) {
      return Outcome.outputTooLarge(tooLarge.getMessage());
    } catch (IllegalArgumentException invalid) {
      return Outcome.invalid(invalid.getMessage());
    } catch (ResourceStoreException storeFailure) {
      return Outcome.storeFailed();
    }

    List<ResultContent> finalContents = new ArrayList<>(plan.items().size());
    for (PlanItem item : plan.items()) {
      if (item instanceof PlanItem.PassThrough pass) {
        finalContents.add(pass.content());
      } else if (item instanceof PlanItem.StoreToResource store) {
        ResourceRef returned;
        try {
          returned =
              resourceStore.put(store.planned().mediaType(), store.planned().name(), store.bytes());
        } catch (RuntimeException failure) {
          return Outcome.storeFailed();
        }
        if (!store.planned().equals(returned)) {
          return Outcome.storeFailed();
        }
        finalContents.add(new ResourceResultContent(returned, store.preview(), store.metadata()));
      }
    }

    return Outcome.success(
        new ToolResult(result.toolCallId(), finalContents, result.error(), result.detailsJson()));
  }

  /** 计划阶段：无副作用完成所有内容校验、文本聚合、尺寸与物理行测量、ResourceRef 规划及最终 ToolResult JSON 尺寸核验。 */
  private Plan plan(String toolName, ToolResult result) {
    List<ResultContent> source = result.contents();
    List<TextPiece> textPieces = new ArrayList<>();
    List<BinaryPiece> binaryPieces = new ArrayList<>();
    List<ResourcePiece> resourcePieces = new ArrayList<>();

    for (int index = 0; index < source.size(); index++) {
      ResultContent content = source.get(index);
      if (content instanceof TextResultContent text) {
        validateStrictUnicode(text.text(), "content at index " + index);
        textPieces.add(new TextPiece(index, text.text(), false));
      } else if (content instanceof JsonResultContent json) {
        validateStrictUnicode(json.json(), "content at index " + index);
        textPieces.add(new TextPiece(index, json.json(), true));
      } else if (content instanceof BinaryResultContent binary) {
        requireCanonicalMediaType(binary.mediaType());
        if (binary.size() > resourceMaxBytes) {
          throw new OutputTooLargeException(
              "content at index "
                  + index
                  + " must not exceed "
                  + resourceMaxBytes
                  + " bytes of stored resource content");
        }
        byte[] bytes = binary.content();
        TextArtifact textArtifact =
            binary.textMetadata() == null
                ? null
                : validateTextArtifact(
                    bytes, binary.textMetadata(), "binary content at index " + index);
        String name = resourceName(toolName, index + 1, null);
        ResourceRef planned =
            resourceStore.reference(binary.mediaType(), name, (long) binary.size(), sha256(bytes));
        binaryPieces.add(new BinaryPiece(index, planned, bytes, textArtifact));
      } else if (content instanceof ResourceResultContent res) {
        ResourceRef ref = res.resource();
        requireCanonicalMediaType(ref.mediaType());
        if (ref.size() == null || ref.sha256() == null) {
          throw new IllegalArgumentException(
              "resource at index " + index + " must declare size and sha256");
        }
        if (ref.size() > resourceMaxBytes) {
          throw new OutputTooLargeException(
              "content at index "
                  + index
                  + " must not exceed "
                  + resourceMaxBytes
                  + " bytes of stored resource content");
        }
        byte[] bytes = readManagedResource(ref, index);
        if (bytes.length != ref.size() || !ref.sha256().equals(sha256(bytes))) {
          throw new ResourceStoreException();
        }
        if (res.textMetadata() != null) {
          TextArtifact textArtifact =
              validateTextArtifact(bytes, res.textMetadata(), "resource content at index " + index);
          resourcePieces.add(
              new ResourcePiece(
                  index,
                  new ResourceResultContent(ref, textArtifact.preview(), textArtifact.metadata())));
        } else {
          resourcePieces.add(new ResourcePiece(index, res));
        }
      } else {
        throw new IllegalArgumentException(
            "unsupported tool content: " + content.getClass().getSimpleName());
      }
    }

    // 处理文本投影
    boolean externalizeText = false;
    String joinedText = null;
    long totalTextBytes = 0;
    long totalTextLines = 0;
    ResourceRef plannedTextRef = null;
    String textPreview = null;
    TextArtifactMetadata textMeta = null;
    byte[] textBytes = null;

    if (!textPieces.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < textPieces.size(); i++) {
        if (i > 0) {
          sb.append("\n\n");
        }
        sb.append(textPieces.get(i).text());
      }
      joinedText = sb.toString();

      // 有界测量 UTF-8 长度：超过 resourceMaxBytes 立即抛出
      if (joinedText.length() > resourceMaxBytes) {
        throw new OutputTooLargeException(
            "tool output exceeded " + resourceMaxBytes + " bytes hard limit");
      }
      int utf8Len = ResourceRef.utf8LengthUpTo(joinedText, "tool output", resourceMaxBytes);
      if (utf8Len > resourceMaxBytes) {
        throw new OutputTooLargeException(
            "tool output exceeded " + resourceMaxBytes + " bytes hard limit");
      }
      totalTextBytes = utf8Len;
      totalTextLines = countPhysicalLines(joinedText);

      if (totalTextBytes > INLINE_MAX_UTF8_BYTES || totalTextLines > INLINE_MAX_LINES) {
        externalizeText = true;
        boolean singleJson = textPieces.size() == 1 && textPieces.get(0).isJson();
        String mediaType = singleJson ? "application/json" : "text/plain";
        String ext = singleJson ? "json" : "txt";
        String name = toolName + "-result." + ext;
        String sha = sha256Utf8(joinedText, "tool output");
        plannedTextRef = resourceStore.reference(mediaType, name, totalTextBytes, sha);
        textPreview = extractRawPreview(joinedText);
        textMeta = new TextArtifactMetadata(totalTextBytes, totalTextLines);
        textBytes = joinedText.getBytes(StandardCharsets.UTF_8);
      }
    }

    // 组装投影内容与计划项
    List<PlanItem> items = new ArrayList<>();
    List<ResultContent> projected = new ArrayList<>();
    int firstTextIndex = textPieces.isEmpty() ? -1 : textPieces.get(0).index();

    for (int index = 0; index < source.size(); index++) {
      if (index == firstTextIndex && externalizeText) {
        PlanItem.StoreToResource storeItem =
            new PlanItem.StoreToResource(plannedTextRef, textPreview, textMeta, textBytes);
        items.add(storeItem);
        projected.add(new ResourceResultContent(plannedTextRef, textPreview, textMeta));
        continue;
      }

      int currentIndex = index;
      boolean isTextPiece = textPieces.stream().anyMatch(tp -> tp.index() == currentIndex);
      if (isTextPiece) {
        if (!externalizeText) {
          ResultContent orig = source.get(index);
          items.add(new PlanItem.PassThrough(orig));
          projected.add(orig);
        }
        // 若 externalizeText 为 true，则除 firstTextIndex 以外的文本块直接被移除
        continue;
      }

      BinaryPiece bp =
          binaryPieces.stream().filter(b -> b.index() == currentIndex).findFirst().orElse(null);
      if (bp != null) {
        PlanItem.StoreToResource storeItem =
            new PlanItem.StoreToResource(
                bp.planned(),
                bp.textArtifact() == null ? null : bp.textArtifact().preview(),
                bp.textArtifact() == null ? null : bp.textArtifact().metadata(),
                bp.content());
        items.add(storeItem);
        projected.add(
            new ResourceResultContent(
                bp.planned(),
                bp.textArtifact() == null ? null : bp.textArtifact().preview(),
                bp.textArtifact() == null ? null : bp.textArtifact().metadata()));
        continue;
      }

      ResourcePiece rp =
          resourcePieces.stream().filter(r -> r.index() == currentIndex).findFirst().orElse(null);
      if (rp != null) {
        items.add(new PlanItem.PassThrough(rp.content()));
        projected.add(rp.content());
      }
    }

    // 验证投影结果的 canonical JSON 尺寸 <= 1 MiB
    ToolResult projectedResult =
        new ToolResult(result.toolCallId(), projected, result.error(), result.detailsJson());
    if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
        projectedResult, MAX_TERMINAL_RESULT_UTF8_BYTES)) {
      throw new IllegalArgumentException(
          "result must not exceed "
              + MAX_TERMINAL_RESULT_UTF8_BYTES
              + " bytes of canonical tool result JSON");
    }

    return new Plan(items);
  }

  /** 严格计算物理行数：空文本为 0；否则为 LF 数量加上未以 LF 结尾时的最后一行。 */
  public static long countPhysicalLines(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    long lfCount = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lfCount++;
      }
    }
    return lfCount + (text.charAt(text.length() - 1) != '\n' ? 1 : 0);
  }

  /** 提取 UTF-8 安全且不拆行/码点的 raw preview 前缀（&le; 2 KiB 且 &le; 20 物理行）。 只保留前缀，无 tail，无截断标记。 */
  public static String extractRawPreview(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    int lines = 0;
    int bytes = 0;
    boolean lineStarted = false;

    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      int charCount = Character.charCount(cp);
      String cpStr = text.substring(i, i + charCount);
      int cpBytes = utf8Bytes(cp);
      if (bytes + cpBytes > TRUNCATED_PREVIEW_MAX_UTF8_BYTES) {
        break;
      }
      if (cp == '\n') {
        lines++;
        sb.append(cpStr);
        bytes += cpBytes;
        lineStarted = false;
        if (lines >= TRUNCATED_PREVIEW_MAX_LINES) {
          break;
        }
      } else {
        if (!lineStarted) {
          if (lines >= TRUNCATED_PREVIEW_MAX_LINES) {
            break;
          }
          lineStarted = true;
        }
        sb.append(cpStr);
        bytes += cpBytes;
      }
      i += charCount;
    }
    return sb.toString();
  }

  private static int utf8Bytes(int codePoint) {
    if (codePoint <= 0x7F) {
      return 1;
    }
    if (codePoint <= 0x7FF) {
      return 2;
    }
    if (codePoint <= 0xFFFF) {
      return 3;
    }
    return 4;
  }

  private static void validateStrictUnicode(String text, String name) {
    try {
      StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(text));
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(name + " must be valid Unicode", error);
    }
  }

  private TextArtifact validateTextArtifact(
      byte[] bytes, TextArtifactMetadata declared, String context) {
    if (declared.totalBytes() != bytes.length) {
      throw new IllegalArgumentException(
          context + " size does not match declared text artifact metadata");
    }
    String text = decodeStrictUtf8(bytes, context);
    long totalLines = countPhysicalLines(text);
    if (declared.totalLines() != totalLines) {
      throw new IllegalArgumentException(
          context + " line count does not match declared text artifact metadata");
    }
    return new TextArtifact(
        extractRawPreview(text), new TextArtifactMetadata(bytes.length, totalLines));
  }

  private byte[] readManagedResource(ResourceRef ref, int index) {
    try {
      byte[] bytes = resourceStore.read(ref);
      if (bytes == null) {
        throw new ResourceStoreException();
      }
      return bytes;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "resource at index " + index + " is not managed by the platform resource store");
    } catch (ResourceStoreException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new ResourceStoreException();
    }
  }

  private static String decodeStrictUtf8(byte[] bytes, String context) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException(context + " must be valid UTF-8");
    }
  }

  private static void requireCanonicalMediaType(String mediaType) {
    if (mediaType == null || !CANONICAL_MEDIA_TYPE.matcher(mediaType).matches()) {
      throw new IllegalArgumentException(
          "mediaType must be canonical lowercase type/subtype without parameters");
    }
  }

  private static String resourceName(String toolName, int callIndex, String extension) {
    return toolName + "-result-" + callIndex + (extension == null ? "" : "." + extension);
  }

  private static String sha256(byte[] content) {
    MessageDigest digest = newSha256();
    digest.update(content);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String sha256Utf8(String text, String name) {
    MessageDigest digest = newSha256();
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer chars = CharBuffer.wrap(text);
    ByteBuffer bytes = ByteBuffer.allocate(8192);
    try {
      while (true) {
        CoderResult result = encoder.encode(chars, bytes, true);
        if (result.isError()) {
          result.throwException();
        }
        bytes.flip();
        digest.update(bytes);
        bytes.clear();
        if (result.isUnderflow()) {
          break;
        }
      }
      CoderResult flush = encoder.flush(bytes);
      if (flush.isError()) {
        flush.throwException();
      }
      bytes.flip();
      digest.update(bytes);
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(name + " must be valid Unicode", error);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  /** 终态化结果：Success 投递成功；Failed 确定性失败；StoreFailed 存储失败（收敛为 UNKNOWN）。 */
  public sealed interface Outcome permits Outcome.Success, Outcome.Failed, Outcome.StoreFailed {

    record Success(ToolResult result) implements Outcome {}

    record Failed(ToolInvocationError error) implements Outcome {}

    record StoreFailed(ToolInvocationError error) implements Outcome {}

    static Outcome success(ToolResult result) {
      return new Success(result);
    }

    static Outcome invalid(String message) {
      return new Failed(
          new ToolInvocationError(
              INVALID_RESULT_KIND,
              message == null || message.isBlank() ? "Tool result content is invalid." : message));
    }

    static Outcome outputTooLarge(String message) {
      return new Failed(
          new ToolInvocationError(
              OUTPUT_TOO_LARGE_KIND,
              message == null || message.isBlank()
                  ? "Tool output exceeded resource hard limit."
                  : message));
    }

    static Outcome storeFailed() {
      return new StoreFailed(
          new ToolInvocationError(RESOURCE_STORE_FAILED_KIND, RESOURCE_STORE_FAILED_MESSAGE));
    }
  }

  private record TextPiece(int index, String text, boolean isJson) {}

  private record BinaryPiece(
      int index, ResourceRef planned, byte[] content, TextArtifact textArtifact) {}

  private record ResourcePiece(int index, ResourceResultContent content) {}

  private record TextArtifact(String preview, TextArtifactMetadata metadata) {}

  private record Plan(List<PlanItem> items) {}

  private sealed interface PlanItem permits PlanItem.PassThrough, PlanItem.StoreToResource {

    record PassThrough(ResultContent content) implements PlanItem {}

    record StoreToResource(
        ResourceRef planned, String preview, TextArtifactMetadata metadata, byte[] bytes)
        implements PlanItem {}
  }

  private static final class OutputTooLargeException extends RuntimeException {
    OutputTooLargeException(String message) {
      super(message);
    }
  }

  private static final class ResourceStoreException extends RuntimeException {}
}
