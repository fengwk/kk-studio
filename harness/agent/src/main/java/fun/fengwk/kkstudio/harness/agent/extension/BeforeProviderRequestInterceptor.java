package fun.fengwk.kkstudio.harness.agent.extension;

import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;

/** 在请求交给 Model Provider 前修改标准化请求。 */
@FunctionalInterface
public interface BeforeProviderRequestInterceptor {
  ProviderRequest intercept(ProviderRequest request);
}
