package fun.fengwk.kkstudio.harness.agent.extension;

import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import java.util.List;
import java.util.Objects;

/** 按 Host 提供的顺序串行变换标准 Provider 请求。 */
public final class ProviderRequestInterceptorChain {

  private final List<BeforeProviderRequestInterceptor> interceptors;

  public ProviderRequestInterceptorChain(List<BeforeProviderRequestInterceptor> interceptors) {
    this.interceptors = List.copyOf(interceptors);
  }

  public ProviderRequest intercept(ProviderRequest request) {
    ProviderRequest current = Objects.requireNonNull(request, "request");
    for (int index = 0; index < interceptors.size(); index++) {
      current = interceptors.get(index).intercept(current);
      if (current == null) {
        throw new IllegalStateException(
            "before provider request interceptor at index " + index + " returned null");
      }
    }
    return current;
  }
}
