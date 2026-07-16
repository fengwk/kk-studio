package fun.fengwk.kkstudio.harness.agent.extension;

import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 按 Host 提供的顺序串行变换标准 Provider 请求。 */
public final class ProviderRequestInterceptorChain {

  private final List<BeforeProviderRequestInterceptor> interceptors;

  public ProviderRequestInterceptorChain(List<BeforeProviderRequestInterceptor> interceptors) {
    this.interceptors = List.copyOf(interceptors);
  }

  /**
   * 在当前所有原 hooks 之后追加一个 interceptor，返回新不可变 chain；当前 chain 状态不变。
   *
   * <p>追加顺序严格保持：所有原有 hooks 先执行，新追加的 interceptor 最后执行。
   *
   * @param next 非空
   * @return 当前 chain + {@code next} 形成的新不可变 chain
   * @throws NullPointerException {@code next} 为 null
   */
  public ProviderRequestInterceptorChain andThen(BeforeProviderRequestInterceptor next) {
    Objects.requireNonNull(next, "next");
    List<BeforeProviderRequestInterceptor> extended = new ArrayList<>(interceptors.size() + 1);
    extended.addAll(interceptors);
    extended.add(next);
    return new ProviderRequestInterceptorChain(List.copyOf(extended));
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
