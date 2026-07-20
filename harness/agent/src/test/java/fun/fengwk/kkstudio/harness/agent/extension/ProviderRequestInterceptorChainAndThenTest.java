package fun.fengwk.kkstudio.harness.agent.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** 关注：andThen 返回新不可变 chain 且原 chain 不变；null 必失败；顺序严格保持（所有原 hooks 先执行，最后追加项执行）。 */
class ProviderRequestInterceptorChainAndThenTest {

  /** andThen 必须返回新 chain，原 chain 不变，且原有 hooks 仍按原顺序执行。 */
  @Test
  void returnsImmutableNewChainAndPreservesOriginal() {
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    AtomicInteger appendedCalls = new AtomicInteger();
    ProviderRequestInterceptorChain original =
        new ProviderRequestInterceptorChain(
            List.of(
                request -> {
                  firstCalls.incrementAndGet();
                  return request;
                },
                request -> {
                  secondCalls.incrementAndGet();
                  return request;
                }));

    ProviderRequestInterceptorChain extended =
        original.andThen(
            request -> {
              appendedCalls.incrementAndGet();
              return request;
            });

    assertNotSame(original, extended);
    // 原始 chain 仍按原顺序独立工作。
    original.intercept(request());
    assertEquals(1, firstCalls.get());
    assertEquals(1, secondCalls.get());
    assertEquals(0, appendedCalls.get());

    // 新 chain 在原 hooks 之后追加新项。
    int firstBefore = firstCalls.get();
    int secondBefore = secondCalls.get();
    extended.intercept(request());
    assertEquals(firstBefore + 1, firstCalls.get());
    assertEquals(secondBefore + 1, secondCalls.get());
    assertEquals(1, appendedCalls.get());
  }

  /** null 追加必须抛 NPE，绝不构造出损坏的 chain。 */
  @Test
  void rejectsNullNextInterceptor() {
    ProviderRequestInterceptorChain chain =
        new ProviderRequestInterceptorChain(List.of(request -> request));
    assertThrows(NullPointerException.class, () -> chain.andThen(null));
  }

  /** 顺序严格保持：原 hooks 先串行执行，新追加的最后执行。中间拦截器修改 request 字段供后续验证， 最终命令写入的标记值即能反映严格顺序。 */
  @Test
  void enforcesStrictOriginalThenAppendedOrder() {
    List<String> order = new ArrayList<>();
    ProviderRequestInterceptorChain extended =
        new ProviderRequestInterceptorChain(
                List.of(
                    request -> {
                      order.add("first");
                      return request;
                    },
                    request -> {
                      order.add("second");
                      return request;
                    }))
            .andThen(
                request -> {
                  order.add("appended");
                  return request;
                });

    extended.intercept(request());
    assertEquals(List.of("first", "second", "appended"), order);
  }

  /** andThen 链上重复追加会逐次叠加。 */
  @Test
  void canChainMultipleAndThenInvocations() {
    List<String> order = new ArrayList<>();
    ProviderRequestInterceptorChain chain =
        new ProviderRequestInterceptorChain(
                List.of(
                    request -> {
                      order.add("first");
                      return request;
                    }))
            .andThen(
                request -> {
                  order.add("second");
                  return request;
                })
            .andThen(
                request -> {
                  order.add("third");
                  return request;
                });
    chain.intercept(request());
    assertEquals(List.of("first", "second", "third"), order);
    // 返回的对象必须是新实例，互不相同。
    ProviderRequestInterceptorChain first = new ProviderRequestInterceptorChain(List.of());
    ProviderRequestInterceptorChain a = first.andThen(request -> request);
    ProviderRequestInterceptorChain b = first.andThen(request -> request);
    assertNotSame(a, b);
    assertNotSame(first, a);
    assertSame(first, first); // sanity
  }

  private static ProviderRequest request() {
    ModelVariant variant = new ModelVariant("default", null, null, null, null, List.of());
    ModelDescriptor model =
        new ModelDescriptor(
            1L,
            2L,
            ProviderType.OPENAI,
            "model",
            "Model",
            1024,
            256,
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            List.of(variant),
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new ProviderRequest(model, variant, List.of(), List.of(), ProviderCacheControl.none());
  }
}
