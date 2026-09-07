package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** Provider 一次成功流式或单次调用完成的结果组合，包含规范化响应与可选的 native replay 状态。 */
public record ProviderCompletion(ProviderResponse response, ProviderReplayState replayState) {

  public ProviderCompletion {
    Objects.requireNonNull(response, "response");
  }

  public ProviderCompletion(ProviderResponse response) {
    this(response, null);
  }
}
