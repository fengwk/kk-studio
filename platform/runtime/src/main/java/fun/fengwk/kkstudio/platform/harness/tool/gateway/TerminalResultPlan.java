package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.result.ToolOutputLimit;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * terminal {@link ToolResult} 的只读规划阶段：分类内容、聚合文本投影、组装计划项并核验最终 JSON 尺寸。
 *
 * <p>本阶段无任何副作用：所有确定性校验与尺寸测量都在首个资源写入之前完成，因此 {@link ToolResultFinalizer} 的执行阶段只需 逐个落实 {@link
 * Plan#items()}，任一失败都可以整体放弃已写入的部分。
 */
final class TerminalResultPlan {

  private static final Pattern CANONICAL_MEDIA_TYPE =
      Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");

  private TerminalResultPlan() {}

  /** 规划完整终态化结果：分类 → 文本投影 → 计划项 → canonical JSON 尺寸核验。 */
  static Plan plan(String toolName, ToolResult result, int maxOutputBytes) {
    Contents contents = classifyContents(toolName, result.contents(), maxOutputBytes);
    TextProjection text = projectText(toolName, contents.textPieces(), maxOutputBytes);
    Projection projection = assembleItems(result.contents(), contents, text);
    requireTerminalSize(result, projection.projected());
    return new Plan(projection.items());
  }

  /** 逐项分类并校验内容形状；顺序与错误上下文都由原始 content 下标决定。 */
  private static Contents classifyContents(
      String toolName, List<ResultContent> source, int maxOutputBytes) {
    List<TextPiece> textPieces = new ArrayList<>();
    List<BinaryPiece> binaryPieces = new ArrayList<>();
    List<ResourcePiece> resourcePieces = new ArrayList<>();

    for (int index = 0; index < source.size(); index++) {
      ResultContent content = source.get(index);
      if (content instanceof TextResultContent text) {
        TerminalTextMetrics.validateStrictUnicode(text.text(), "content at index " + index);
        textPieces.add(new TextPiece(index, text.text(), false));
      } else if (content instanceof JsonResultContent json) {
        TerminalTextMetrics.validateStrictUnicode(json.json(), "content at index " + index);
        textPieces.add(new TextPiece(index, json.json(), true));
      } else if (content instanceof BinaryResultContent binary) {
        requireCanonicalMediaType(binary.mediaType());
        if (binary.size() > maxOutputBytes) {
          throw new OutputTooLargeException(
              ToolOutputLimit.exceededMessage(binary.size(), maxOutputBytes));
        }
        byte[] bytes = binary.content();
        TextArtifact textArtifact =
            binary.textMetadata() == null
                ? null
                : validateTextArtifact(
                    bytes, binary.textMetadata(), "binary content at index " + index);
        String name = resourceName(toolName, index + 1, null);
        ResourceRef planned =
            plannedReference(binary.mediaType(), name, (long) binary.size(), Sha256.hex(bytes));
        binaryPieces.add(new BinaryPiece(index, planned, bytes, textArtifact));
      } else if (content instanceof ResourceResultContent res) {
        ResourceRef ref = res.resource();
        requireCanonicalMediaType(ref.mediaType());
        if (ref.size() == null || ref.sha256() == null) {
          throw new IllegalArgumentException(
              "resource at index " + index + " must declare size and sha256");
        }
        if (ref.sessionBlobId() != null) {
          // 已授权的 Session Blob durable 引用：字节已在全局对象存储且已按 Session 引用鉴权，本阶段只做形状校验并原样透传。
          if (res.textMetadata() != null) {
            throw new IllegalArgumentException(
                "session resource at index " + index + " must not declare text metadata");
          }
          resourcePieces.add(new ResourcePiece(index, res));
          continue;
        }
        if (ref.blobUploadId() == null) {
          throw new IllegalArgumentException(
              "resource at index " + index + " must be a staged upload or a session resource");
        }
        if (ref.size() > maxOutputBytes) {
          throw new OutputTooLargeException(
              ToolOutputLimit.exceededMessage(ref.size(), maxOutputBytes));
        }
        // 已由 Daemon 或插件直传对象存储的上传引用：本阶段只做形状校验并原样透传。
        if (res.textMetadata() != null) {
          throw new IllegalArgumentException(
              "canonical upload resource at index " + index + " must not declare text metadata");
        }
        resourcePieces.add(new ResourcePiece(index, res));
      } else {
        throw new IllegalArgumentException(
            "unsupported tool content: " + content.getClass().getSimpleName());
      }
    }
    return new Contents(textPieces, binaryPieces, resourcePieces);
  }

  /**
   * 聚合全部 Text/Json 内容并决定是否外化。
   *
   * <p>顺序固定：先拼接，再按全文 UTF-8 字节做硬上限判定，然后才比较内联阈值——超出硬上限必须报 {@code OUTPUT_TOO_LARGE}，不能被外化路径吞掉。
   */
  private static TextProjection projectText(
      String toolName, List<TextPiece> textPieces, int maxOutputBytes) {
    int firstTextIndex = textPieces.isEmpty() ? -1 : textPieces.get(0).index();
    if (textPieces.isEmpty()) {
      return new TextProjection(false, firstTextIndex, null, null, null, null);
    }

    String joinedText = joinText(textPieces);
    // Measure without allocating an encoded copy so the error can report the exact byte count.
    long totalTextBytes = TerminalTextMetrics.utf8Length(joinedText);
    if (totalTextBytes > maxOutputBytes) {
      throw new OutputTooLargeException(
          ToolOutputLimit.exceededMessage(totalTextBytes, maxOutputBytes));
    }
    long totalTextLines = TerminalTextMetrics.countPhysicalLines(joinedText);
    if (totalTextBytes > ToolResultFinalizer.inlineMaxUtf8Bytes(toolName)
        || totalTextLines > ToolResultFinalizer.inlineMaxLines(toolName)) {
      boolean singleJson = textPieces.size() == 1 && textPieces.get(0).isJson();
      String mediaType = singleJson ? "application/json" : "text/plain";
      String ext = singleJson ? "json" : "txt";
      String name = toolName + "-result." + ext;
      String sha = TerminalTextMetrics.sha256Utf8(joinedText, "tool output");
      ResourceRef plannedTextRef = plannedReference(mediaType, name, totalTextBytes, sha);
      String textPreview = TerminalTextMetrics.extractRawPreview(joinedText);
      TextArtifactMetadata textMeta = new TextArtifactMetadata(totalTextBytes, totalTextLines);
      byte[] textBytes = joinedText.getBytes(StandardCharsets.UTF_8);
      return new TextProjection(
          true, firstTextIndex, plannedTextRef, textPreview, textMeta, textBytes);
    }
    return new TextProjection(false, firstTextIndex, null, null, null, null);
  }

  private static String joinText(List<TextPiece> textPieces) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < textPieces.size(); i++) {
      if (i > 0) {
        sb.append("\n\n");
      }
      sb.append(textPieces.get(i).text());
    }
    return sb.toString();
  }

  /** 按原始 content 顺序组装计划项与投影内容；两者必须逐项锁步。 */
  private static Projection assembleItems(
      List<ResultContent> source, Contents contents, TextProjection text) {
    List<PlanItem> items = new ArrayList<>();
    List<ResultContent> projected = new ArrayList<>();

    for (int index = 0; index < source.size(); index++) {
      if (index == text.firstTextIndex() && text.externalize()) {
        items.add(
            new PlanItem.StoreToResource(
                text.plannedRef(), text.preview(), text.metadata(), text.bytes()));
        projected.add(
            new ResourceResultContent(text.plannedRef(), text.preview(), text.metadata()));
        continue;
      }

      int currentIndex = index;
      boolean isTextPiece =
          contents.textPieces().stream().anyMatch(piece -> piece.index() == currentIndex);
      if (isTextPiece) {
        // 外化时除首段位置以外的文本块都被移除，只保留承载完整投影的第一个位置。
        if (!text.externalize()) {
          ResultContent original = source.get(index);
          items.add(new PlanItem.PassThrough(original));
          projected.add(original);
        }
        continue;
      }

      BinaryPiece binaryPiece =
          contents.binaryPieces().stream()
              .filter(piece -> piece.index() == currentIndex)
              .findFirst()
              .orElse(null);
      if (binaryPiece != null) {
        TextArtifact artifact = binaryPiece.textArtifact();
        items.add(
            new PlanItem.StoreToResource(
                binaryPiece.planned(),
                artifact == null ? null : artifact.preview(),
                artifact == null ? null : artifact.metadata(),
                binaryPiece.content()));
        projected.add(
            new ResourceResultContent(
                binaryPiece.planned(),
                artifact == null ? null : artifact.preview(),
                artifact == null ? null : artifact.metadata()));
        continue;
      }

      ResourcePiece resourcePiece =
          contents.resourcePieces().stream()
              .filter(piece -> piece.index() == currentIndex)
              .findFirst()
              .orElse(null);
      if (resourcePiece != null) {
        items.add(new PlanItem.PassThrough(resourcePiece.content()));
        projected.add(resourcePiece.content());
      }
    }
    return new Projection(items, projected);
  }

  /** 投影结果的 canonical JSON 必须在 1 MiB 内；超限是确定性拒绝，必须在任何写入之前抛出。 */
  private static void requireTerminalSize(ToolResult result, List<ResultContent> projected) {
    ToolResult projectedResult =
        new ToolResult(result.toolCallId(), projected, result.error(), result.detailsJson());
    if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
        projectedResult, ToolResultFinalizer.MAX_TERMINAL_RESULT_UTF8_BYTES)) {
      throw new IllegalArgumentException(
          "result must not exceed "
              + ToolResultFinalizer.MAX_TERMINAL_RESULT_UTF8_BYTES
              + " bytes of canonical tool result JSON");
    }
  }

  private static TextArtifact validateTextArtifact(
      byte[] bytes, TextArtifactMetadata declared, String context) {
    if (declared.totalBytes() != bytes.length) {
      throw new IllegalArgumentException(
          context + " size does not match declared text artifact metadata");
    }
    String text = TerminalTextMetrics.decodeStrictUtf8(bytes, context);
    long totalLines = TerminalTextMetrics.countPhysicalLines(text);
    if (declared.totalLines() != totalLines) {
      throw new IllegalArgumentException(
          context + " line count does not match declared text artifact metadata");
    }
    return new TextArtifact(
        TerminalTextMetrics.extractRawPreview(text),
        new TextArtifactMetadata(bytes.length, totalLines));
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

  private static ResourceRef plannedReference(
      String mediaType, String name, Long size, String sha256) {
    return new ResourceRef(
        ResourceRef.blobUploadUri(new UUID(0L, 0L)), mediaType, name, size, sha256);
  }

  /** 计划项：原样透传已有内容，或把内容写成一个 managed Resource。 */
  sealed interface PlanItem permits PlanItem.PassThrough, PlanItem.StoreToResource {

    record PassThrough(ResultContent content) implements PlanItem {}

    record StoreToResource(
        ResourceRef planned, String preview, TextArtifactMetadata metadata, byte[] bytes)
        implements PlanItem {}
  }

  /** 已完成的只读计划；执行阶段按序落实每个 {@link PlanItem}。 */
  record Plan(List<PlanItem> items) {}

  /** 内容分类结果：三类 piece 各自保留下标，组装阶段据此还原原始顺序。 */
  private record Contents(
      List<TextPiece> textPieces,
      List<BinaryPiece> binaryPieces,
      List<ResourcePiece> resourcePieces) {}

  /** 文本投影决策：{@code externalize} 为 false 时全部文本原样内联，其余字段为 null；为 true 时字段描述唯一的 managed Resource。 */
  private record TextProjection(
      boolean externalize,
      int firstTextIndex,
      ResourceRef plannedRef,
      String preview,
      TextArtifactMetadata metadata,
      byte[] bytes) {}

  /** 计划项与投影内容按原始 content 顺序逐项对应。 */
  private record Projection(List<PlanItem> items, List<ResultContent> projected) {}

  private record TextPiece(int index, String text, boolean isJson) {}

  private record BinaryPiece(
      int index, ResourceRef planned, byte[] content, TextArtifact textArtifact) {}

  private record ResourcePiece(int index, ResourceResultContent content) {}

  private record TextArtifact(String preview, TextArtifactMetadata metadata) {}

  /** 全文超出输出硬上限：plan 阶段据此投影为 {@code OUTPUT_TOO_LARGE}，绝不触达资源存储。 */
  static final class OutputTooLargeException extends RuntimeException {
    OutputTooLargeException(String message) {
      super(message);
    }
  }
}
