package fun.fengwk.kkstudio.web.generated.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import fun.fengwk.kkstudio.share.ai.catalog.DecimalStringSerializer;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** {@code TsContractGeneratorTest} 的契约 fixture；嵌套类型生成 {@code Fixture*} 的 TS 名。 */
public final class FixtureDTO {

  private FixtureDTO() {}

  /** wire 值来自 {@link JsonValue}，不是常量名。 */
  public enum Kind {
    ALPHA,
    BETA;

    @JsonValue
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** 只出现在响应里：覆盖 presence、Long、Instant、UUID、集合与 Map。 */
  public record View(
      String name,
      @Nullable String note,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String always,
      Long count,
      Instant at,
      UUID id,
      List<Kind> kinds,
      Map<String, Integer> scores) {}

  /** 只出现在请求里：Long 写作 number，可空属性可以缺省。 */
  public record Create(Long limit, @Nullable String note) {}

  /** 同时用于请求与响应：Long 保持写出形态。 */
  public record Shared(Long version) {}

  /** sealed 多态：判别属性 kind。 */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Shape.Circle.class, name = "CIRCLE"),
    @JsonSubTypes.Type(value = Shape.Square.class, name = "SQUARE")
  })
  public sealed interface Shape permits Shape.Circle, Shape.Square {

    record Circle(int radius) implements Shape {}

    record Square(int side) implements Shape {}
  }

  /** 白名单字符串序列化器：价格 decimal 写作十进制字符串，属性与 Map 元素都改写为 string。 */
  public record Priced(
      @JsonSerialize(using = DecimalStringSerializer.class) BigDecimal price,
      @JsonSerialize(contentUsing = DecimalStringSerializer.class) Map<String, BigDecimal> tiers,
      BigDecimal plain) {}

  /** 定制序列化改变了 wire 形态。 */
  public record Custom(@JsonSerialize(using = ToStringSerializer.class) Integer value) {}

  /** 契约包外的对象类型没有 TS 映射。 */
  public record Unsupported(URI target) {}
}
