package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 验证四种流式 provider 对不可执行选项均在传输层调用前失败。 */
class ProviderExecutionGuardTransportTest {

  /** 测试意图：拒绝工具和候选数时不仅报 INVALID_REQUEST，传输 spy 还证明未发 HTTP。 */
  @Test
  void unsupportedOptionsNeverReachTransport() {
    AtomicInteger calls = new AtomicInteger();
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(client, worker, scheduler) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy policy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            calls.incrementAndGet();
            throw new AssertionError("rejected options must not reach HTTP");
          }
        };
    try {
      check(
          ProviderType.OPENAI,
          new OpenAiChatProviderAdapter(transport, "test").create(descriptor(ProviderType.OPENAI)),
          "{\"n\":2}",
          calls);
      check(
          ProviderType.OPENAI,
          new OpenAiChatProviderAdapter(transport, "test").create(descriptor(ProviderType.OPENAI)),
          "{\"tools\":[{\"type\":\"function\"}]}",
          calls);
      check(
          ProviderType.OPENAI_RESPONSES,
          new OpenAiResponsesProviderAdapter(transport, "test")
              .create(descriptor(ProviderType.OPENAI_RESPONSES)),
          "{\"tools\":[{\"type\":\"local_shell\"}]}",
          calls);
      check(
          ProviderType.OPENAI_RESPONSES,
          new OpenAiResponsesProviderAdapter(transport, "test")
              .create(descriptor(ProviderType.OPENAI_RESPONSES)),
          "{\"background\":true}",
          calls);
      check(
          ProviderType.ANTHROPIC,
          new AnthropicProviderAdapter(transport, "test")
              .create(descriptor(ProviderType.ANTHROPIC)),
          "{\"tools\":[{\"type\":\"bash_20250124\",\"name\":\"run\"}]}",
          calls);
      check(
          ProviderType.GOOGLE,
          new GeminiProviderAdapter(transport, "test").create(descriptor(ProviderType.GOOGLE)),
          "{\"tools\":[{\"functionDeclarations\":[]}]}",
          calls);
      check(
          ProviderType.GOOGLE,
          new GeminiProviderAdapter(transport, "test").create(descriptor(ProviderType.GOOGLE)),
          "{\"generationConfig\":{\"candidateCount\":2}}",
          calls);
    } finally {
      client.shutdownNow();
      worker.shutdownNow();
      scheduler.shutdownNow();
    }
  }

  private static ProviderDescriptor descriptor(ProviderType type) {
    return new ProviderDescriptor(
        "test",
        type,
        "https://example.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  private static void check(
      ProviderType type, ModelProvider provider, String options, AtomicInteger calls) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE);
    ModelDescriptor model =
        new ModelDescriptor(
            "test", "model", "model", Set.of(ModelInputModality.TEXT), true, false, pricing);
    ProviderRequest request =
        new ProviderRequest(
            model,
            new ModelVariant("v", null, new ProviderProtocolOptions(options)),
            1024,
            "system",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
            List.of(),
            ProviderCacheControl.none());
    AtomicReference<ProviderException> error = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException failure, ProviderStream stream) {
            error.set(failure);
          }
        });
    assertNotNull(error.get(), type.toString());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.get().kind());
    assertEquals(0, calls.get(), type.toString());
  }
}
