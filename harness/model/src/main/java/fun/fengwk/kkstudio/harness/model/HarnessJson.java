package fun.fengwk.kkstudio.harness.model;

import static fun.fengwk.kkstudio.common.json.JsonValues.strictBuilder;
import static fun.fengwk.kkstudio.common.json.JsonValues.strictDecimalBuilder;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Harness 共用的 JSON 读取器。
 *
 * <p>普通树、十进制精度和 canonical 选项分别保持原有数值语义；调用方不得改变其配置。{@link #LENIENT_DECIMAL} 只用于读取 上游错误正文及用于推导事件名的原生
 * SSE payload，不用于持久化或回放的业务解码。
 */
public final class HarnessJson {

  public static final ObjectMapper STRICT = strictBuilder(JsonMapper.builder()).build();

  public static final ObjectMapper STRICT_DECIMAL =
      strictDecimalBuilder(JsonMapper.builder()).build();

  /** 保留十进制精度，允许重复键，其余语法检查沿用 Jackson 默认配置。 */
  public static final ObjectMapper LENIENT_DECIMAL =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  public static final ObjectMapper STRICT_CANONICAL =
      strictDecimalBuilder(JsonMapper.builder())
          .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
          .build();

  public static final ObjectMapper STRICT_OPTIONS =
      strictDecimalBuilder(JsonMapper.builder())
          .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
          .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .build();

  private HarnessJson() {}

  /** 使用调用方提供的输入限制，但保留严格十进制解析语义。 */
  public static ObjectMapper strictDecimal(JsonFactory factory) {
    return strictDecimalBuilder(JsonMapper.builder(factory)).build();
  }
}
