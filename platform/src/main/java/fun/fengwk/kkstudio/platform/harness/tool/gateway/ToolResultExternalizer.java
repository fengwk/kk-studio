package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
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
 * Terminal {@link ToolResult} 的 managed Resource 外部化。
 *
 * <p>所有 {@link BinaryResultContent}（daemon wire 解码后的瞬时字节）与超过 {@link #INLINE_RESULT_UTF8_BYTES}
 * 内联阈值的 Text/Json content 都写入注入的 {@link ResourceStore} 并替换为 {@link ResourceResultContent}；已有规范
 * Resource 引用（含 preview）原样透传。Text/Json 外部化时附带至多 16 KiB 的 UTF-8 安全
 * preview：完整内容能容纳时原样保留，否则使用确定性前缀和截断标记；Binary preview 保持 null。外部化是 all-or-nothing：先对全部 content
 * 做确定性校验并计算写计划（第一个 put 之前不产生任何 存储副作用），再执行写入—— 计划阶段用 {@link ResourceRef#utf8LengthUpTo} 做 bounded
 * UTF-8 长度测量（严格 Resource 语义：未配对 surrogate 确定性拒绝， 不物化任何 UTF-8 byte[]）逐项校验 {@code resourceMaxBytes}
 * 上限，超限即确定性拒绝；对每个待存项流式计算 SHA-256（Text/Json 走严格 UTF-8 流式编码摘要，Binary 一次一项拷贝），经无副作用的 {@link
 * ResourceStore#reference} 计划精确 ResourceRef，构造「外部化后 + 外部化后」的精确投影 {@link ToolResult} 并用 {@link
 * ToolResultJsonCodec#exceedsEncodedUtf8Bytes} 验证 canonical JSON ≤ {@link
 * ToolResultSizeLimits#MAX_TERMINAL_RESULT_UTF8_BYTES}——即 Runtime 持久化前会做的同一校验，全部在第一个 put 之前完成。
 * 编码只发生在写入循环里、一次一项；每次 put 返回的引用必须与计划引用精确相等，不等即存储契约违反。确定性非法输入（含 store 的 {@link
 * IllegalArgumentException}）返回 {@link Outcome.Invalid}；store 存储/IO 失败或契约违反（外部 Tool 可能已完成） 返回 {@link
 * Outcome.StoreFailed}，调用方映射为 onUnknown 而非可重试协议失败。
 */
final class ToolResultExternalizer {

  /** 单条 Text/Json content 的内联 UTF-8 字节阈值；超过则外部化为 ResourceResultContent。 */
  static final int INLINE_RESULT_UTF8_BYTES = 8 * 1024;

  /** 超过 preview 上限时追加的稳定 ASCII 标记；标记本身计入 16 KiB 上限。 */
  static final String PREVIEW_TRUNCATION_MARKER = "\n[preview truncated]";

  static final String INVALID_RESULT_KIND = "INVALID_RESULT";
  static final String RESOURCE_STORE_FAILED_KIND = "RESOURCE_STORE_FAILED";

  private static final String RESOURCE_STORE_FAILED_MESSAGE =
      "Tool result resource persistence failed; outcome is unknown.";

  /** ResourceStore 契约的 canonical 小写 type/subtype（ResourceRef 同一来源）。 */
  private static final Pattern CANONICAL_MEDIA_TYPE =
      Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");

  private final ResourceStore resourceStore;
  private final int resourceMaxBytes;

  ToolResultExternalizer(ResourceStore resourceStore, int resourceMaxBytes) {
    this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
    if (resourceMaxBytes <= 0) {
      throw new IllegalArgumentException("resourceMaxBytes must be positive");
    }
    this.resourceMaxBytes = resourceMaxBytes;
  }

  /** 全有或全无的外部化：先 {@link #plan} 校验并计算写计划（含精确投影的尺寸校验），再逐项编码写入并核对返回引用。 */
  Outcome externalize(String toolName, ToolResult result) {
    Objects.requireNonNull(toolName, "toolName");
    List<Action> plan;
    try {
      plan = plan(toolName, result);
    } catch (IllegalArgumentException invalid) {
      return Outcome.invalid(invalid);
    }
    List<ResultContent> contents = new ArrayList<>(plan.size());
    for (Action action : plan) {
      if (action instanceof Action.Inline inline) {
        contents.add(inline.content());
        continue;
      }
      if (action instanceof Action.KeepResource keep) {
        contents.add(keep.content());
        continue;
      }
      // 编码只发生在写入循环、一次一项：计划阶段已确定性校验尺寸/摘要并计划引用，getBytes 结果必然有界。
      Action.Store store = (Action.Store) action;
      ResourceRef returned;
      try {
        returned =
            resourceStore.put(
                store.planned().mediaType(), store.planned().name(), bytes(store.source()));
      } catch (RuntimeException failure) {
        // reference() 已用完全相同的输入完成确定性校验；put 再抛任何异常都属于存储失败或契约违反。
        // 此时外部 Tool 已完成，且前序计划项可能已写入，必须收敛 UNKNOWN，不能伪装为 INVALID_RESULT。
        return Outcome.storeFailed(failure);
      }
      if (!store.planned().equals(returned)) {
        // 存储契约违反：返回引用与计划（size/sha/uri/mediaType/name）不一致，结果无法确认。
        return Outcome.storeFailed(
            new IllegalStateException(
                "resource store returned a different reference than planned; outcome cannot be confirmed"));
      }
      contents.add(new ResourceResultContent(returned, store.preview()));
    }
    return Outcome.success(
        new ToolResult(result.toolCallId(), contents, result.error(), result.detailsJson()));
  }

  /**
   * 校验全部 content 并计算写计划；任何确定性非法输入（含超 {@code resourceMaxBytes} 的项、未配对 surrogate 文本、外部化后 投影结果超过
   * canonical JSON 上限）在第一个 put 之前抛出。计划阶段用 {@link ResourceRef#utf8LengthUpTo} 做 bounded UTF-8
   * 长度测量（严格 Resource 语义、不物化 byte[]），流式计算待存项摘要，经 {@link ResourceStore#reference} 计划精确
   * 引用，不编码任何字符串、不触碰存储。
   */
  private List<Action> plan(String toolName, ToolResult result) {
    List<ResultContent> source = result.contents();
    List<Action> plan = new ArrayList<>(source.size());
    List<ResultContent> projected = new ArrayList<>(source.size());
    for (int index = 0; index < source.size(); index++) {
      ResultContent content = source.get(index);
      if (content instanceof BinaryResultContent binary) {
        requireCanonicalMediaType(binary.mediaType());
        requireWithinResourceMax(index, binary.size());
        String name = resourceName(toolName, index + 1, null);
        ResourceRef planned =
            resourceStore.reference(
                binary.mediaType(), name, binary.size(), sha256(binary.content()));
        plan.add(new Action.Store(planned, binary, null));
        projected.add(new ResourceResultContent(planned));
      } else if (content instanceof ResourceResultContent resource) {
        // 已有规范 Resource 引用直接透传，不做二次外部化。
        plan.add(new Action.KeepResource(resource));
        projected.add(resource);
      } else {
        String text = text(content);
        int utf8Length =
            ResourceRef.utf8LengthUpTo(
                text,
                "content at index " + index,
                Math.max(INLINE_RESULT_UTF8_BYTES, resourceMaxBytes));
        if (utf8Length <= INLINE_RESULT_UTF8_BYTES) {
          plan.add(new Action.Inline(content));
          projected.add(content);
        } else {
          // 需要外部化的项必须同时满足 resourceMaxBytes：超限在第一个 put 之前确定性拒绝。
          requireWithinResourceMax(index, utf8Length);
          boolean json = content instanceof JsonResultContent;
          String mediaType = json ? "application/json" : "text/plain";
          String name = resourceName(toolName, index + 1, json ? "json" : "txt");
          ResourceRef planned =
              resourceStore.reference(
                  mediaType, name, utf8Length, sha256Utf8(text, "content at index " + index));
          String preview = preview(text, utf8Length);
          plan.add(new Action.Store(planned, content, preview));
          projected.add(new ResourceResultContent(planned, preview));
        }
      }
    }
    // 精确投影：第一个 put 之前验证 canonical JSON 尺寸，保证确定性 INVALID_RESULT 绝不发生在 store 副作用之后。
    ToolResult projectedResult =
        new ToolResult(result.toolCallId(), projected, result.error(), result.detailsJson());
    if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
        projectedResult, ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES)) {
      throw new IllegalArgumentException(
          "result must not exceed "
              + ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES
              + " bytes of canonical tool result JSON");
    }
    return plan;
  }

  private void requireWithinResourceMax(int index, int bytes) {
    if (bytes > resourceMaxBytes) {
      throw new IllegalArgumentException(
          "content at index "
              + index
              + " must not exceed "
              + resourceMaxBytes
              + " bytes of stored resource content");
    }
  }

  private static void requireCanonicalMediaType(String mediaType) {
    if (mediaType == null || !CANONICAL_MEDIA_TYPE.matcher(mediaType).matches()) {
      throw new IllegalArgumentException(
          "mediaType must be canonical lowercase type/subtype without parameters");
    }
  }

  /** 稳定可读的资源展示名：{toolName}-result-{callIndex}[.txt|.json]。 */
  private static String resourceName(String toolName, int callIndex, String extension) {
    return toolName + "-result-" + callIndex + (extension == null ? "" : "." + extension);
  }

  private static String text(ResultContent content) {
    if (content instanceof TextResultContent text) {
      return text.text();
    }
    if (content instanceof JsonResultContent json) {
      return json.json();
    }
    throw new IllegalArgumentException(
        "unsupported tool content: " + content.getClass().getSimpleName());
  }

  private static byte[] bytes(ResultContent content) {
    if (content instanceof BinaryResultContent binary) {
      return binary.content();
    }
    if (content instanceof TextResultContent text) {
      return text.text().getBytes(StandardCharsets.UTF_8);
    }
    if (content instanceof JsonResultContent json) {
      return json.json().getBytes(StandardCharsets.UTF_8);
    }
    throw new IllegalArgumentException(
        "unsupported tool content: " + content.getClass().getSimpleName());
  }

  /**
   * 生成 deterministic UTF-8 安全 preview：完整内容不超过上限时原样返回；否则按 code point 截取可容纳前缀并追加稳定标记。
   *
   * <p>{@code utf8Length} 已由计划阶段按严格 Unicode 语义完整计算；本方法只扫描至 preview 边界，不编码完整字符串。
   */
  private static String preview(String text, int utf8Length) {
    if (utf8Length <= ResourceRef.MAX_PREVIEW_UTF8_BYTES) {
      return text;
    }
    int markerBytes =
        ResourceRef.utf8Length(PREVIEW_TRUNCATION_MARKER, "preview truncation marker");
    int prefixMaxBytes = ResourceRef.MAX_PREVIEW_UTF8_BYTES - markerBytes;
    int prefixBytes = 0;
    int end = 0;
    while (end < text.length()) {
      int codePoint = text.codePointAt(end);
      int codePointBytes = utf8Bytes(codePoint);
      if (codePointBytes > prefixMaxBytes - prefixBytes) {
        break;
      }
      prefixBytes += codePointBytes;
      end += Character.charCount(codePoint);
    }
    return text.substring(0, end) + PREVIEW_TRUNCATION_MARKER;
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

  private static String sha256(byte[] content) {
    MessageDigest digest = newSha256();
    digest.update(content);
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * 流式严格 UTF-8 摘要：逐块编码（bounded 缓冲、不物化完整 byte[]），未配对 surrogate 确定性拒绝（与 {@link
   * ResourceRef#utf8LengthUpTo} 同一严格语义；计划阶段已先行校验，此处为防御）。
   */
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

  /** 外部化结果：Success 投递成功；Invalid 确定性失败（onFailed，非 retryable）；StoreFailed 存储失败（onUnknown）。 */
  sealed interface Outcome permits Outcome.Success, Outcome.Invalid, Outcome.StoreFailed {

    record Success(ToolResult result) implements Outcome {}

    record Invalid(ToolInvocationError error) implements Outcome {}

    record StoreFailed(ToolInvocationError error) implements Outcome {}

    static Outcome success(ToolResult result) {
      return new Success(result);
    }

    static Outcome invalid(IllegalArgumentException invalid) {
      String message = invalid.getMessage();
      return new Invalid(
          new ToolInvocationError(
              INVALID_RESULT_KIND,
              message == null || message.isBlank() ? "Tool result content is invalid." : message));
    }

    static Outcome storeFailed(RuntimeException failure) {
      String message = failure.getMessage();
      return new StoreFailed(
          new ToolInvocationError(
              RESOURCE_STORE_FAILED_KIND,
              message == null || message.isBlank() ? RESOURCE_STORE_FAILED_MESSAGE : message));
    }
  }

  /** 写计划：内联保留 / 透传已有资源 / 写入 store（计划引用和 preview 精确，source 在写入循环内才编码为字节）。 */
  private sealed interface Action permits Action.Inline, Action.KeepResource, Action.Store {

    record Inline(ResultContent content) implements Action {}

    record KeepResource(ResourceResultContent content) implements Action {}

    record Store(ResourceRef planned, ResultContent source, String preview) implements Action {}
  }
}
