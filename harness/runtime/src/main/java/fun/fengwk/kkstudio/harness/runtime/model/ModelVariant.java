package fun.fengwk.kkstudio.harness.runtime.model;

import java.util.Locale;
import java.util.Set;

/**
 * 模型的一组可命名推理预设（variant）。
 *
 * <p>variant 只表达 reasoning effort：{@code id} 为稳定的 variant 身份，{@code reasoningEffort}
 * 为可空的推理强度。合法取值固定为 {@code high} / {@code medium} / {@code low} / {@code off}；{@code off}
 * 表示显式关闭推理（下发厂商的关闭语义），{@code null} 表示不声明、由协议默认决定，两者绝不互相静默映射。
 *
 * <p>采样参数与单次输出上限不进入 variant：输出预算由 Model 级 {@code limit.output} 与本次请求的剩余上下文计算，采样参数使用厂商默认。
 */
public record ModelVariant(String id, String reasoningEffort) {

  /** 合法 reasoning effort 取值；{@code off} 为显式关闭。 */
  public static final Set<String> REASONING_EFFORTS = Set.of("high", "medium", "low", "off");

  public ModelVariant {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (!id.equals(id.trim())) {
      throw new IllegalArgumentException("id must not have surrounding whitespace");
    }
    if (reasoningEffort != null) {
      String normalized = reasoningEffort.trim().toLowerCase(Locale.ROOT);
      if (!REASONING_EFFORTS.contains(normalized)) {
        throw new IllegalArgumentException(
            "reasoningEffort must be one of " + REASONING_EFFORTS + ", got: " + reasoningEffort);
      }
      reasoningEffort = normalized;
    }
  }

  /** 仅按 id 构造、不声明 reasoning effort 的 variant。 */
  public ModelVariant(String id) {
    this(id, null);
  }

  /** 是否显式关闭推理。 */
  public boolean reasoningOff() {
    return "off".equals(reasoningEffort);
  }
}
