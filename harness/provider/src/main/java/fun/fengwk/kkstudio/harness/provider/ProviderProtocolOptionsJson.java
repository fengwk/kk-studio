package fun.fengwk.kkstudio.harness.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;

import java.util.Objects;

/**
 * 把 variant 的厂商原生协议选项还原为可供协议编码器合并的 {@link ObjectNode}。
 *
 * <p>{@link ModelVariant#protocolOptions()} 的 canonical JSON 是唯一事实源，其严格性（重复键、trailing token、非
 * object、字节上限）已在构造时强制；本类只做解析与防御性副本：每次调用都返回可独立修改的新树，编码器任意修改都不会污染 variant
 * 或其他调用者。数值不经过二进制浮点数，避免改写厂商原生数值语义。
 *
 * <p>本类不决定任何协议字段的 merge policy：哪些官方字段可以并入、哪些运行时所有权字段必须优先，由各协议编码器自行负责。
 */
public final class ProviderProtocolOptionsJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    MAPPER.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  private ProviderProtocolOptionsJson() {}

  /**
   * 返回 variant 原生选项的独立可变副本；未声明选项时返回空 object。
   *
   * @param variant 不可为 null；其 protocol options 由构造期校验保证为严格 JSON object
   */
  public static ObjectNode copyOfOptions(ModelVariant variant) {
    Objects.requireNonNull(variant, "variant");
    String json = variant.protocolOptions().canonicalJson();
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("variant protocol options are not valid JSON", error);
    }
    if (!(node instanceof ObjectNode options)) {
      throw new IllegalStateException("variant protocol options are not a JSON object");
    }
    return options;
  }
}
