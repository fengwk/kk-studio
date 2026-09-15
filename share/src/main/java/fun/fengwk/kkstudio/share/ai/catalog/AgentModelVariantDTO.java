package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 单个 Agent 模型变体，只表达 reasoning effort。
 *
 * <p>{@code reasoningEffort} 可空：由厂商自定义（如 {@code high} / {@code medium} / {@code low} / {@code max}
 * / {@code xhigh} 等）；{@code off} 表示显式关闭推理，{@code null} 表示不下发该字段、由协议默认决定。两者语义不同，绝不互相静默映射。 非 null 的
 * reasoningEffort 会被 trim 并做 locale-safe 小写归一化，禁止空白且长度不能超过 64 个字符。
 */
@Data
public class AgentModelVariantDTO {

  /** 必填变体 id：非空白、无环绕空白，且在 {@code config.variants} 内唯一。 */
  private String id;

  /** 可空 reasoning effort：由厂商自定义，{@code off} 显式关闭，null 表示协议默认；最大长度 64。 */
  private String reasoningEffort;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Model variant field: " + name);
  }
}
