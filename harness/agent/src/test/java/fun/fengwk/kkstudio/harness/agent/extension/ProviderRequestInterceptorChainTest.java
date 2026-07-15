package fun.fengwk.kkstudio.harness.agent.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderRequestInterceptorChainTest {

  /** 构造时复制输入列表，调用方后续修改不会改变已冻结的执行链。 */
  @Test
  void copiesInterceptorListAtConstruction() {
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger replacementCalls = new AtomicInteger();
    List<BeforeProviderRequestInterceptor> interceptors = new ArrayList<>();
    interceptors.add(
        request -> {
          firstCalls.incrementAndGet();
          return request;
        });
    ProviderRequestInterceptorChain chain = new ProviderRequestInterceptorChain(interceptors);
    interceptors.clear();
    interceptors.add(
        request -> {
          replacementCalls.incrementAndGet();
          return request;
        });

    chain.intercept(request());

    assertEquals(1, firstCalls.get());
    assertEquals(0, replacementCalls.get());
  }

  /** null 返回值必须在链边界明确失败，不能继续传给后一项。 */
  @Test
  void rejectsNullInterceptorResult() {
    AtomicInteger laterCalls = new AtomicInteger();
    ProviderRequestInterceptorChain chain =
        new ProviderRequestInterceptorChain(
            List.of(
                request -> null,
                request -> {
                  laterCalls.incrementAndGet();
                  return request;
                }));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> chain.intercept(request()));

    assertEquals(
        "before provider request interceptor at index 0 returned null", error.getMessage());
    assertEquals(0, laterCalls.get());
  }

  private static ProviderRequest request() {
    ModelVariant variant = new ModelVariant("default", null, null, null, null, List.of());
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            "model",
            "Model",
            1024,
            256,
            Set.of(ModelInputModality.TEXT),
            Set.of(ModelCapability.TEXT),
            List.of(variant),
            new ModelPricing(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    return new ProviderRequest(model, variant, List.of(), List.of());
  }
}
