package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

/**
 * 单个 Agent 模型变体，表达 reasoning effort 与厂商原生协议请求选项。
 *
 * <p>{@code reasoningEffort} 可空：由厂商自定义（如 {@code high} / {@code medium} / {@code low} / {@code max}
 * / {@code xhigh} 等）；{@code off} 表示显式关闭推理，{@code null} 表示不下发该字段、由协议默认决定。两者语义不同，绝不互相静默映射。 非 null 的
 * reasoningEffort 会被 trim 并做 locale-safe 小写归一化，禁止空白且长度不能超过 64 个字符。
 *
 * <p>{@code protocolOptionsJson} 可空：JSON object 文本形态的厂商原生请求选项，{@code null} 与 {@code {}} 等价，非 object
 * 一律拒绝；数字文本绝不经由前端浮点解析。选项只允许官方协议的非传输字段，Provider 凭据、endpoint、 传输模式与 runtime
 * 冻结事实不允许被覆盖，具体合并规则由各协议编码器负责。
 */
@Data
public class AgentModelVariantDTO {

  /** 必填变体 id：非空白、无环绕空白，且在 {@code config.variants} 内唯一。 */
  private String id;

  /** 可空 reasoning effort：由厂商自定义，{@code off} 显式关闭，null 表示协议默认；最大长度 64。 */
  private String reasoningEffort;

  /** 厂商原生协议选项的 JSON object 文本；传输与持久化均不解码其中的数值。 */
  @JsonDeserialize(using = ProtocolOptionsJsonStringDeserializer.class)
  private String protocolOptionsJson;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Model variant field: " + name);
  }
}
