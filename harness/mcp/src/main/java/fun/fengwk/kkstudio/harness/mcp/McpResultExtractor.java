package fun.fengwk.kkstudio.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpToolResultExtractor;
import dev.langchain4j.service.tool.ToolExecutionResult;

import fun.fengwk.kkstudio.harness.common.json.BoundedOutputStream;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 完整保留 MCP text/image/resource 等 SDK 内容的工具执行结果抽取器。
 *
 * <ul>
 *   <li>text 内容映射为 {@link TextResultContent}，并计入单次响应的累计内容预算；
 *   <li>image 内容在解码前按 encoded 长度检查 decoded 上界，再映射为 {@link BinaryResultContent}；
 *   <li>resource 或其他结构化内容先用 {@link BoundedOutputStream} 在 {@link
 *       JsonResultContent#MAX_JSON_UTF8_BYTES} 内写出，再映射为 {@link JsonResultContent}；超限 fail
 *       closed，不退化为同尺寸无界文本；
 *   <li>非法 Base64 等受控结构仍保留为有界 JSON，不因单条畸形内容二次失败。
 * </ul>
 *
 * <p>本类是模块内<strong>唯一的 Jackson 2 适配边界</strong>：SDK 的 {@link McpToolResultExtractor} 签名固定接收 {@code
 * com.fasterxml.jackson.databind.JsonNode}，因此这里用一个 J2 mapper 只把 SDK 节点有界写回 JSON 文本；模块自有的 JSON（工具
 * schema、调用详情）一律走 Jackson 3。J2 mapper 保持默认 UTF-8 编码 profile，不额外改编码开关。
 *
 * <p>累计预算是单次 {@code extract} 内全部 text 与 binary 字节之和，上限为 {@link #MAX_CONTENT_BUDGET_BYTES}（64
 * MiB）。该数字对齐既有 terminal 工具产物硬上限的数量级（daemon multiline 扫描与 terminal 结果尺寸检查使用的 64 MiB）， 不是新的调用配置：MCP
 * client 没有独立的结果预算配置项。
 */
public final class McpResultExtractor implements McpToolResultExtractor {

  /**
   * 单次 MCP 响应全部内容的累计字节预算：64 MiB。
   *
   * <p>与既有 terminal/daemon 64 MiB 上界对齐，不新增配置。
   */
  public static final int MAX_CONTENT_BUDGET_BYTES = 64 * 1024 * 1024;

  /**
   * 只用于把 SDK 的 Jackson 2 内容节点有界写回 JSON 文本的 mapper；保留其默认 UTF-8 编码 profile，模块自有 JSON 一律走 Jackson
   * 3（{@code tools.jackson}）。
   */
  private static final ObjectMapper SDK_NODE_MAPPER = new ObjectMapper();

  @Override
  public ToolExecutionResult extract(JsonNode contentNode, boolean isError) {
    List<ResultContent> contents = new ArrayList<>();
    ContentBudget budget = new ContentBudget();
    if (contentNode != null) {
      if (contentNode.isArray()) {
        for (JsonNode item : contentNode) {
          contents.add(parseItem(item, budget));
        }
      } else {
        contents.add(parseItem(contentNode, budget));
      }
    }
    if (contents.isEmpty()) {
      contents.add(new TextResultContent(""));
    }
    return ToolExecutionResult.builder()
        .isError(isError)
        .result(contents)
        .resultText(extractCombinedText(contents))
        .build();
  }

  private static ResultContent parseItem(JsonNode item, ContentBudget budget) {
    if (item == null || item.isNull()) {
      return chargeText("", budget);
    }
    if (item.isTextual()) {
      return chargeText(item.asText(""), budget);
    }
    String type = item.has("type") ? item.get("type").asText() : "";
    if ("text".equals(type) && item.has("text")) {
      return chargeText(item.get("text").asText(""), budget);
    }
    if ("image".equals(type) && item.has("data")) {
      String data = item.get("data").asText("");
      String mimeType =
          item.has("mimeType") && !item.get("mimeType").asText("").isBlank()
              ? item.get("mimeType").asText()
              : "application/octet-stream";
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
        return structuredJson(item, budget);
      }
    }
    return structuredJson(item, budget);
  }

  /**
   * 结构化节点先按 JSON 单条上限有界写出。超限或无法编码时 fail closed，绝不把同一节点退化为无界文本。
   *
   * <p>写出成功后的文本同时计入累计预算：JSON 文本本身就是将要驻留的内容。
   */
  private static ResultContent structuredJson(JsonNode node, ContentBudget budget) {
    if (node == null || node.isNull()) {
      return chargeText("", budget);
    }
    if (node.isTextual()) {
      return chargeText(node.asText(""), budget);
    }
    String json = writeBoundedJson(node, JsonResultContent.MAX_JSON_UTF8_BYTES);
    if (json == null) {
      throw new McpException(
          "MCP content exceeds "
              + JsonResultContent.MAX_JSON_UTF8_BYTES
              + " UTF-8 bytes and cannot be retained");
    }
    chargeUtf8(json, "json", budget);
    return new JsonResultContent(json);
  }

  /**
   * 把 SDK 的 Jackson 2 节点写成不超过 {@code maxBytes} 的 UTF-8 JSON 文本。
   *
   * <p>上限判定只认有界输出流自身的 {@link BoundedOutputStream.LimitExceededException}（直接抛出或沿 cause 链），超限时返回
   * {@code null}，把「超限」决策留给调用方；其它序列化/IO 失败一律收敛为固定不透明 {@link McpException}，绝不回显节点内容或保留底层 cause。
   */
  static String writeBoundedJson(JsonNode node, int maxBytes) {
    BoundedOutputStream out = new BoundedOutputStream(maxBytes, true);
    try {
      SDK_NODE_MAPPER.writeValue(out, node);
    } catch (IOException error) {
      if (isOverLimit(error)) {
        return null;
      }
      // 序列化失败的消息可能携带节点片段或本地路径，不能跨越模块边界。
      throw new McpException("MCP content cannot be encoded as bounded JSON");
    }
    return out.toUtf8String();
  }

  /** 仅当 cause 链中出现有界输出流自身的超限中止时判定为超限；其它序列化/IO 失败不视为超限。 */
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

  private static McpException contentBudgetExceeded() {
    return new McpException(
        "MCP content exceeds " + MAX_CONTENT_BUDGET_BYTES + " byte aggregate budget");
  }

  private static String extractCombinedText(List<ResultContent> contents) {
    StringBuilder sb = new StringBuilder();
    for (ResultContent content : contents) {
      if (content instanceof TextResultContent textContent) {
        if (!sb.isEmpty()) {
          sb.append("\n");
        }
        sb.append(textContent.text());
      }
    }
    return sb.toString();
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
