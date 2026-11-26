package fun.fengwk.kkstudio.harness.model.provider.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.common.json.JsonCodecSupport;
import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;

import java.util.regex.Pattern;

/**
 * Provider 协议共用的 JSON 解析与 usage 数值校验纯辅助。
 *
 * <p>所有解析共用同一个严格读取器：重复键与尾随 token 在解析层拒绝，协议差异只体现在调用方传入的错误文案与错误类别。
 */
public final class ProviderJsonHelper {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;
  private static final Pattern TOOL_IDENTIFIER = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

  private ProviderJsonHelper() {}

  /**
   * 严格解析 JSON 文本并要求根节点为对象。
   *
   * @param json JSON 文本
   * @param errorMessage 输入为空、语法非法、重复键、尾随 token 或根节点非对象时的错误文案
   * @return 解析后的对象节点
   * @throws ProviderException 校验失败时以 {@link ProviderErrorKind#INVALID_REQUEST} 抛出
   */
  public static JsonNode parseStrictJsonObject(String json, String errorMessage) {
    return parseStrictJsonInternal(json, true, errorMessage);
  }

  /**
   * 严格解析 JSON 文本，根节点可以是任意 JSON 值。
   *
   * @param json JSON 文本
   * @param errorMessage 输入为空、语法非法、重复键或存在尾随 token 时的错误文案
   * @return 解析后的 JSON 节点
   * @throws ProviderException 校验失败时以 {@link ProviderErrorKind#INVALID_REQUEST} 抛出
   */
  public static JsonNode parseStrictJson(String json, String errorMessage) {
    return parseStrictJsonInternal(json, false, errorMessage);
  }

  private static JsonNode parseStrictJsonInternal(
      String json, boolean requireObject, String errorMessage) {
    if (json == null || json.isBlank()) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
    try (JsonParser parser = OBJECT_MAPPER.createParser(json)) {
      JsonNode node = OBJECT_MAPPER.readTree(parser);
      if (node == null || parser.nextToken() != null) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
      }
      return requireObject ? JsonCodecSupport.object(node, errorMessage) : node;
    } catch (Exception e) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
  }

  /**
   * 尝试把文本解析为 JSON 对象；输入为空、解析失败或根节点不是对象时返回 {@code null}。
   *
   * @param text 待解析文本
   * @return 对象节点，或 {@code null}
   */
  public static JsonNode tryParseJsonObject(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return JsonCodecSupport.object(OBJECT_MAPPER.readTree(text), "json");
    } catch (Exception ignored) {
      // 宽容解析：调用方只在能得到对象时使用结果，其余情况按“无对象”继续。
      return null;
    }
  }

  /** 完整工具调用的参数必须是严格 JSON 对象；诊断仅包含安全的工具名、解析位置和预定义原因。 */
  public static void requireToolArgumentsObject(String toolName, String arguments) {
    if (arguments == null || arguments.isBlank()) {
      throw toolArgumentsError(toolName, "missing argument text");
    }
    try (JsonParser parser = OBJECT_MAPPER.createParser(arguments)) {
      parser.nextToken();
      TokenStreamLocation rootLocation = parser.currentTokenLocation();
      // readTree uses the same strict mapper as tryParseJsonObject, including trailing token
      // checks.
      JsonNode node = OBJECT_MAPPER.readTree(parser);
      if (node == null || !node.isObject()) {
        throw toolArgumentsError(toolName, "expected a JSON object" + formatPosition(rootLocation));
      }
    } catch (JacksonException error) {
      throw toolArgumentsError(
          toolName,
          "invalid JSON" + formatPosition(error.getLocation()) + "; " + toolArgumentsReason(error));
    }
  }

  private static ProviderException toolArgumentsError(String toolName, String diagnosis) {
    // Never surface a raw provider identifier outside the tool-name grammar or a parser exception.
    String name =
        toolName != null && TOOL_IDENTIFIER.matcher(toolName).matches() ? toolName : "tool call";
    return new ProviderException(
        ProviderErrorKind.INVALID_RESPONSE, name + ": " + diagnosis + "; not executed.");
  }

  private static String formatPosition(TokenStreamLocation location) {
    return location != null && location.getLineNr() > 0 && location.getColumnNr() > 0
        ? " at " + location.getLineNr() + ":" + location.getColumnNr()
        : "";
  }

  private static String toolArgumentsReason(JacksonException error) {
    if (error instanceof StreamConstraintsException) {
      return "JSON input exceeds parser limits";
    }
    if (error instanceof UnexpectedEndOfInputException) {
      return "unexpected end of input";
    }
    // Jackson does not expose structured reasons for all syntax errors. Recognize specific
    // templates, but never include their original text, which can contain argument content.
    String message = error.getOriginalMessage();
    if (message != null) {
      if (message.startsWith("Duplicate Object property ")) {
        return "duplicate object key";
      }
      if (message.startsWith("Trailing token ")) {
        return "unexpected data after the JSON value";
      }
      if (message.startsWith("Unexpected character (")) {
        if (message.endsWith("was expecting comma to separate Object entries")) {
          return "expected ',' or '}'";
        }
        if (message.endsWith("was expecting double-quote to start property name")) {
          return "expected a quoted object key";
        }
      }
    }
    return "invalid token or structure";
  }

  /**
   * 解析 SSE 数据行中的 JSON。
   *
   * @param data SSE 数据行
   * @param errorMessage 解析失败时的错误文案
   * @return 解析后的 JSON 节点
   * @throws ProviderException 解析失败时以 {@link ProviderErrorKind#INVALID_RESPONSE} 抛出
   */
  public static JsonNode parseSseJson(String data, String errorMessage) {
    try {
      return OBJECT_MAPPER.readTree(data);
    } catch (Exception e) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, errorMessage);
    }
  }

  /**
   * 解析 usage 中的非负整数（long 范围）。
   *
   * @param node 字段节点
   * @param fieldName 字段名，用于填充文案模板
   * @param notIntegerFormat 非整数或超出 long 范围时的文案模板，使用 {@code %s} 占位字段名
   * @param negativeFormat 负数时的文案模板，使用 {@code %s} 占位字段名
   * @return 解析出的非负 long 值
   * @throws ProviderException 校验失败时以 {@link ProviderErrorKind#INVALID_RESPONSE} 抛出
   */
  public static long parseNonNegativeLong(
      JsonNode node, String fieldName, String notIntegerFormat, String negativeFormat) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, notIntegerFormat.formatted(fieldName));
    }
    long value = node.asLong(0L);
    if (value < 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, negativeFormat.formatted(fieldName));
    }
    return value;
  }
}
