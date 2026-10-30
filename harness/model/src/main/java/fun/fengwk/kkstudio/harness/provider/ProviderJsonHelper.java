package fun.fengwk.kkstudio.harness.provider;

import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;

/**
 * Provider 协议共用的 JSON 解析与 usage 数值校验纯辅助。
 *
 * <p>所有解析共用同一个严格读取器：重复键与尾随 token 在解析层拒绝，协议差异只体现在调用方传入的错误文案与错误类别。
 */
public final class ProviderJsonHelper {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

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
      if (node == null || (requireObject && !node.isObject()) || parser.nextToken() != null) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
      }
      return node;
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
      JsonNode node = OBJECT_MAPPER.readTree(text);
      return node != null && node.isObject() ? node : null;
    } catch (Exception ignored) {
      // 宽容解析：调用方只在能得到对象时使用结果，其余情况按“无对象”继续。
      return null;
    }
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
