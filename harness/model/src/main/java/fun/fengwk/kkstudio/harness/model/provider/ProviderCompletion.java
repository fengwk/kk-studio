package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/** Provider 一次成功流式或单次调用完成的规范化响应。 */
public record ProviderCompletion(ProviderResponse response) {

  public ProviderCompletion {
    Objects.requireNonNull(response, "response");
  }
}
