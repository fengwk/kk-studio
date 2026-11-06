package fun.fengwk.kkstudio.harness.model;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Harness 共用的严格 JSON 读取器。
 *
 * <p>普通树、十进制精度和 canonical 选项分别保持原有数值语义；调用方不得改变其配置。
 *
 * <p>{@link #STRICT_DECIMAL} 与 common 的 {@code JsonValues} 树读取配置等价，但本模块是可独立编译的叶子模块，不依赖
 * common，因此模型、runtime 与平台的 codec 统一使用本类；common 层继续由 {@code JsonValues} 服务。两处配置修改必须同步，
 * 否则跨层报文会出现数值精度或重复键语义差异。
 */
public final class HarnessJson {

  public static final ObjectMapper STRICT = strict(JsonMapper.builder()).build();

  public static final ObjectMapper STRICT_DECIMAL = decimal(JsonMapper.builder()).build();

  public static final ObjectMapper STRICT_CANONICAL =
      decimal(JsonMapper.builder())
          .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
          .build();

  public static final ObjectMapper STRICT_OPTIONS =
      decimal(JsonMapper.builder())
          .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
          .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .build();

  private HarnessJson() {}

  /** 使用调用方提供的输入限制，但保留严格十进制解析语义。 */
  public static ObjectMapper strictDecimal(JsonFactory factory) {
    return decimal(JsonMapper.builder(factory)).build();
  }

  private static JsonMapper.Builder strict(JsonMapper.Builder builder) {
    return builder
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static JsonMapper.Builder decimal(JsonMapper.Builder builder) {
    return strict(builder).enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }
}
