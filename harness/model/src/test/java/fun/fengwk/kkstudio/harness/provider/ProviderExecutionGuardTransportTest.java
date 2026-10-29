package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;

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
import java.util.function.Predicate;

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
        new JdkHttpSseTransport(client, scheduler) {
          @Override
          public void execute(
              HttpRequest request,
              ModelCallTimeoutPolicy policy,
              HttpSseLimits limits,
              Predicate<ServerSentEvent> onEvent) {
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
    ModelDescriptor model =
        new ModelDescriptor("test", "model", "model", Set.of(ModelInputModality.TEXT), true, false);
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
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException failure) {
            error.set(failure);
          }
        });
    assertNotNull(error.get(), type.toString());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.get().kind());
    assertEquals(0, calls.get(), type.toString());
  }
}
