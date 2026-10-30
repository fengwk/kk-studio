package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.json.BoundedOutputStream;
import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整保留 MCP text/image/audio/resource 等 SDK 内容的工具执行结果抽取器。
 *
 * <ul>
 *   <li>text 内容映射为 {@link TextResultContent}，并计入单次响应的累计内容预算；
 *   <li>image/audio 内容在解码前按 encoded 长度检查 decoded 上界，再映射为 {@link BinaryResultContent}；
 *   <li>resource 或其他结构化内容先用 {@link BoundedOutputStream} 在 {@link
 *       JsonResultContent#MAX_JSON_UTF8_BYTES} 内写出，再映射为 {@link JsonResultContent}；超限 fail
 *       closed，不退化为同尺寸无界文本；
 *   <li>非法 Base64 等受控结构仍保留为有界 JSON，不因单条畸形内容二次失败。
 * </ul>
 *
 * <p>普通 content 优先，保持既有工具结果映射；只有 content 为空时才把结构化结果写成可读 JSON 文本。模块自有 JSON 与 SDK 内容序列化一律走 Jackson
 * 3（{@code tools.jackson}）。
 *
 * <p>累计预算是单次 {@code extract} 内全部 text 与 binary 字节之和，上限为 {@link #MAX_CONTENT_BUDGET_BYTES}（64
 * MiB）。该数字对齐既有 terminal 工具产物硬上限的数量级（daemon multiline 扫描与 terminal 结果尺寸检查使用的 64 MiB），不是新的调用配置：MCP
 * client 没有独立的结果预算配置项。
 */
public final class McpResultExtractor {

  /**
   * 单次 MCP 响应全部内容的累计字节预算：64 MiB。
   *
   * <p>与既有 terminal/daemon 64 MiB 上界对齐，不新增配置。
   */
  public static final int MAX_CONTENT_BUDGET_BYTES = 64 * 1024 * 1024;

  /**
   * 模块 JSON 与结构化内容共用的 Jackson 3 mapper。
   *
   * <p>显式开启 {@link SerializationFeature#FAIL_ON_EMPTY_BEANS}：Jackson 3 默认关闭该开关，会把无可序列化属性的 value 写成
   * {@code {}}；这里保持既有约定，让无法编码的详情继续走失败回退，而不是产出看似合法的空对象 JSON。
   */
  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

  /** 抽取一次工具调用结果；error 结果映射为可读错误文本，不向模块边界泄漏 SDK 异常。 */
  public McpToolCallResult extract(McpSchema.CallToolResult result) {
    boolean isError = Boolean.TRUE.equals(result.isError());
    if (isError) {
      return McpToolCallResult.errorText(extractErrorText(result));
    }
    ContentBudget budget = new ContentBudget();
    List<ResultContent> contents = new ArrayList<>();
    if (!result.content().isEmpty()) {
      for (McpSchema.Content item : result.content()) {
        contents.add(parseItem(item, budget));
      }
    } else if (result.structuredContent() != null) {
      contents.add(chargeStructuredText(result.structuredContent(), budget));
    }
    if (contents.isEmpty()) {
      contents.add(new TextResultContent(""));
    }
    return new McpToolCallResult(false, List.copyOf(contents), detailsJson(result.meta()));
  }

  private static ResultContent parseItem(McpSchema.Content item, ContentBudget budget) {
    if (item == null) {
      return chargeText("", budget);
    }
    if (item instanceof McpSchema.TextContent textContent) {
      return chargeText(textContent.text(), budget);
    }
    if (item instanceof McpSchema.ImageContent imageContent) {
      String mimeType =
          imageContent.mimeType() == null || imageContent.mimeType().isBlank()
              ? "application/octet-stream"
              : imageContent.mimeType();
      return parseBase64Binary(imageContent.data(), mimeType, item, budget);
    }
    if (item instanceof McpSchema.AudioContent audioContent) {
      String mimeType =
          audioContent.mimeType() == null || audioContent.mimeType().isBlank()
              ? "audio/wav"
              : audioContent.mimeType();
      return parseBase64Binary(audioContent.data(), mimeType, item, budget);
    }
    return structuredJson(item, budget);
  }

  private static ResultContent parseBase64Binary(
      String data, String mimeType, McpSchema.Content fallback, ContentBudget budget) {
    int decodedUpperBound = decodedBase64UpperBound(data);
    if (decodedUpperBound < 0 || !budget.tryCharge(decodedUpperBound)) {
      throw contentBudgetExceeded();
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(data);
      budget.refund(decodedUpperBound - bytes.length);
      return new BinaryResultContent(mimeType, bytes);
    } catch (IllegalArgumentException ignored) {
      // 非法 Base64 不物化解码结果；已预扣的 decoded 上界退回，结构本身走有界 JSON。
      budget.refund(decodedUpperBound);
      return structuredJson(fallback, budget);
    }
  }

  /**
   * 结构化节点先按 JSON 单条上限有界写出。超限或无法编码时 fail closed，绝不把同一节点退化为无界文本。
   *
   * <p>写出成功后的文本同时计入累计预算：JSON 文本本身就是将要驻留的内容。
   */
  private static ResultContent structuredJson(McpSchema.Content content, ContentBudget budget) {
    String json =
        writeBounded(
            OBJECT_MAPPER.writerFor(McpSchema.Content.class),
            content,
            JsonResultContent.MAX_JSON_UTF8_BYTES);
    if (json == null) {
      throw contentItemLimitExceeded();
    }
    chargeUtf8(json, "json", budget);
    return new JsonResultContent(json);
  }

  private static ResultContent chargeStructuredText(
      Object structuredContent, ContentBudget budget) {
    String json = writeBoundedJson(structuredContent, JsonResultContent.MAX_JSON_UTF8_BYTES);
    if (json == null) {
      throw contentItemLimitExceeded();
    }
    return chargeText(json, budget);
  }

  private static String extractErrorText(McpSchema.CallToolResult result) {
    ContentBudget budget = new ContentBudget();
    StringBuilder text = new StringBuilder();
    for (McpSchema.Content item : result.content()) {
      if (item instanceof McpSchema.TextContent textContent) {
        chargeUtf8(textContent.text(), "text", budget);
        if (!text.isEmpty()) {
          charge(1, budget);
          text.append('\n');
        }
        text.append(textContent.text());
      }
    }
    String errorText = text.toString();
    if (!errorText.isBlank()) {
      return errorText;
    }
    if (result.structuredContent() != null) {
      String json = writeBoundedJson(result.structuredContent(), MAX_CONTENT_BUDGET_BYTES);
      if (json == null) {
        throw contentBudgetExceeded();
      }
      return json;
    }
    return "MCP tool returned an error result";
  }

  /**
   * 把任意 JSON 值写成不超过 {@code maxBytes} 的 UTF-8 JSON 文本。
   *
   * <p>上限判定只认有界输出流自身的 {@link BoundedOutputStream.LimitExceededException}（直接抛出或沿 cause 链），超限时返回
   * {@code null}，把「超限」决策留给调用方；其它序列化失败一律收敛为固定不透明 {@link McpException}，绝不回显内容或保留底层 cause。
   */
  static String writeBoundedJson(Object node, int maxBytes) {
    return writeBounded(OBJECT_MAPPER.writer(), node, maxBytes);
  }

  private static String writeBounded(ObjectWriter writer, Object node, int maxBytes) {
    BoundedOutputStream out = new BoundedOutputStream(maxBytes, true);
    try {
      writer.writeValue(out, node);
    } catch (JacksonException error) {
      if (isOverLimit(error)) {
        return null;
      }
      throw new McpException("MCP content cannot be encoded as bounded JSON");
    }
    return out.toUtf8String();
  }

  /** 仅当 cause 链中出现有界输出流自身的超限中止时判定为超限；其它序列化失败不视为超限。 */
  private static boolean isOverLimit(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof BoundedOutputStream.LimitExceededException) {
        return true;
      }
    }
    return false;
  }

  private static TextResultContent chargeText(String text, ContentBudget budget) {
    chargeUtf8(text, "text", budget);
    return new TextResultContent(text);
  }

  /** 按 UTF-8 字节数把文本计入累计预算；超过剩余预算即 fail closed。 */
  private static void chargeUtf8(String text, String name, ContentBudget budget) {
    int limit = budget.remaining();
    int measured = ResourceRef.utf8LengthUpTo(text, name, limit);
    // utf8LengthUpTo 在越过上限时返回的计数可能比 limit 大 1..4，不能把该值直接扣进预算。
    if (measured > limit) {
      throw contentBudgetExceeded();
    }
    charge(measured, budget);
  }

  private static void charge(int bytes, ContentBudget budget) {
    if (bytes < 0 || !budget.tryCharge(bytes)) {
      throw contentBudgetExceeded();
    }
  }

  /**
   * Base64 解码后的字节上界：按 encoded 长度计算，不分配 decoded 数组。
   *
   * <p>基本 decoder 不接受空白与非法字符，因此按原长度估算只会更保守、绝不低估实际 decoded 规模；解码前用它拦截超预算输入。
   * 返回负数表示长度已大到无法安全换算（必然也超过预算）。
   */
  private static int decodedBase64UpperBound(String encoded) {
    long decoded = ((long) encoded.length() + 3L) / 4L * 3L;
    return decoded > Integer.MAX_VALUE ? -1 : (int) decoded;
  }

  private static McpException contentItemLimitExceeded() {
    return new McpException(
        "MCP content exceeds "
            + JsonResultContent.MAX_JSON_UTF8_BYTES
            + " UTF-8 bytes and cannot be retained");
  }

  private static McpException contentBudgetExceeded() {
    return new McpException(
        "MCP content exceeds " + MAX_CONTENT_BUDGET_BYTES + " byte aggregate budget");
  }

  /** 提取 `_meta` 中的业务详情；MCP 规范保留的协议元数据不进入工具结果。 */
  private static String detailsJson(Map<String, Object> meta) {
    if (meta == null || meta.isEmpty()) {
      return "{}";
    }
    Map<String, Object> details = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : meta.entrySet()) {
      if (!isReservedByMcp(entry.getKey())) {
        details.put(entry.getKey(), entry.getValue());
      }
    }
    if (details.isEmpty()) {
      return "{}";
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(details);
    } catch (JacksonException ignored) {
      return "{}";
    }
  }

  /** `_meta` 中以 `*.modelcontextprotocol/` 或 `*.mcp/` 开头的键属于协议事实，不属于工具结果。 */
  private static boolean isReservedByMcp(String key) {
    int slashIndex = key.indexOf('/');
    if (slashIndex < 0) {
      return false;
    }
    String[] labels = key.substring(0, slashIndex).split("\\.");
    return labels.length > 1
        && ("modelcontextprotocol".equals(labels[1]) || "mcp".equals(labels[1]));
  }

  /** 单次 extract 的剩余累计预算；只在抽取线程上使用。 */
  private static final class ContentBudget {
    private int remaining = MAX_CONTENT_BUDGET_BYTES;

    private int remaining() {
      return remaining;
    }

    private boolean tryCharge(int bytes) {
      if (bytes > remaining) {
        return false;
      }
      remaining -= bytes;
      return true;
    }

    private void refund(int bytes) {
      remaining += bytes;
    }
  }
}
