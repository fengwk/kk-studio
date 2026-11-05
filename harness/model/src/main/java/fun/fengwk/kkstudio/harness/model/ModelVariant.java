package fun.fengwk.kkstudio.harness.model;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;

import java.util.Objects;

/**
 * 模型的一组可命名推理预设（variant）。
 *
 * <p>variant 表达 reasoning effort、提示缓存留存档位与厂商原生协议选项：{@code id} 为稳定的 variant 身份，{@code
 * reasoningEffort} 为可空的推理强度。由厂商自定义（如 {@code high} / {@code medium} / {@code low} / {@code max} /
 * {@code xhigh} 等）； {@code off} 表示显式关闭推理（下发厂商的关闭语义），{@code null} 表示不声明、由协议默认决定，两者绝不互相静默映射。
 *
 * <p>非 null 的 reasoningEffort 只去除首尾空白并保留原始大小写（厂商自定义自由字符串，不做大小写归一化），禁止空白且长度不能超过 64 个字符。
 *
 * <p>{@code protocolOptions} 是严格 JSON object 形态的厂商原生请求选项，{@code null} 与空 object 等价（见 {@link
 * ProviderProtocolOptions}）；它承载规范化核心之外、但官方协议允许的请求事实，由各协议编码器在保护运行时所有权字段的前提下 合并进请求体。因此本值对象只做严格校验与
 * canonical 化，不解释任何具体协议字段。
 *
 * <p>采样参数与单次输出上限不进入 variant：输出预算由 Model 级 {@code limit.output} 与本次请求的剩余上下文计算，采样参数使用厂商默认。
 */
public record ModelVariant(
    String id,
    String reasoningEffort,
    PromptCacheRetention promptCacheRetention,
    ProviderProtocolOptions protocolOptions) {

  /** reasoning effort 最大字符长度。 */
  public static final int MAX_REASONING_EFFORT_LENGTH = 64;

  public ModelVariant {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (!id.equals(id.trim())) {
      throw new IllegalArgumentException("id must not have surrounding whitespace");
    }
    if (reasoningEffort != null) {
      if (reasoningEffort.isBlank()) {
        throw new IllegalArgumentException("reasoningEffort must not be blank");
      }
      String trimmed = reasoningEffort.trim();
      if (trimmed.length() > MAX_REASONING_EFFORT_LENGTH) {
        throw new IllegalArgumentException(
            "reasoningEffort length must not exceed "
                + MAX_REASONING_EFFORT_LENGTH
                + " characters");
      }
      reasoningEffort = trimmed;
    }
    Objects.requireNonNull(promptCacheRetention, "promptCacheRetention");
    protocolOptions = protocolOptions == null ? ProviderProtocolOptions.EMPTY : protocolOptions;
  }

  /** 是否显式关闭推理。 */
  public boolean reasoningOff() {
    return "off".equals(reasoningEffort);
  }
}
