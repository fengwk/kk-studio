package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Provider 流请求。
 *
 * <p>{@code outputTokens} 是本次请求唯一的输出预算：由 Model 级 {@code limit.output} 与当前剩余上下文计算，压缩调用则使用压缩阶段预算。
 * 预算随请求显式传递，绝不回写到 variant 或请求体的自定义字段。
 */
public record ProviderRequest(
    ModelDescriptor model,
    ModelVariant variant,
    int outputTokens,
    List<ProviderMessage> messages,
    List<ProviderToolDefinition> tools,
    ProviderCacheControl cacheControl) {

  public ProviderRequest {
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    if (outputTokens <= 0) {
      throw new IllegalArgumentException("outputTokens must be positive");
    }
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    cacheControl = Objects.requireNonNull(cacheControl, "cacheControl");
  }
}
