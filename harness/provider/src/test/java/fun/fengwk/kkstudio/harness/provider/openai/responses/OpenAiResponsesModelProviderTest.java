package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.HttpOpenMetadata;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** 验证 OpenAI Responses ModelProvider 端到端请求装配、鉴权注入与流生命周期交互。 */
class OpenAiResponsesModelProviderTest {

  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterEach
  void tearDown() {
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ModelDescriptor createModel() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE);
    return new ModelDescriptor(
        "openai_test", "gpt-5.4-mini", Set.of(ModelInputModality.TEXT), true, true, pricing);
  }

  private ProviderRequest createRequest() {
    return new ProviderRequest(
        createModel(),
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 验证带有 apiKey 时注入 Bearer 鉴权头，且请求端点、方法与 Content-Type 符合 Responses 规范。 */
  @Test
  void test_authenticatedRequestAssembly() {
    AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(httpClient, workerExecutor, scheduler) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            capturedRequest.set(request);
            return new ProviderStream() {
              @Override
              public void cancel() {}

              @Override
              public boolean isCancelled() {
                return false;
              }
            };
          }
        };

    OpenAiResponsesProviderAdapter adapter =
        new OpenAiResponsesProviderAdapter(transport, "sk-valid-key");
    assertEquals(ProviderType.OPENAI_RESPONSES, adapter.providerType());
    assertEquals(PromptCacheMode.AUTOMATIC, adapter.promptCacheCapability().mode());

    ModelProvider provider = adapter.create(createDescriptor());

    ProviderStream stream =
        provider.stream(
            createRequest(),
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {}
            });

    assertNotNull(stream);
    HttpRequest req = capturedRequest.get();
    assertNotNull(req);
    assertEquals("POST", req.method());
    assertEquals("https://api.openai.com/v1/responses", req.uri().toString());
    assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
    assertEquals("text/event-stream", req.headers().firstValue("Accept").orElse(""));
    assertEquals("Bearer sk-valid-key", req.headers().firstValue("Authorization").orElse(""));
  }

  /** 验证无 apiKey 或为空格时以匿名方式发起请求，绝不注入 Authorization 头。 */
  @Test
  void test_anonymousRequestAssembly() {
    AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(httpClient, workerExecutor, scheduler) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            capturedRequest.set(request);
            return new ProviderStream() {
              @Override
              public void cancel() {}

              @Override
              public boolean isCancelled() {
                return false;
              }
            };
          }
        };

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "   ");
    ModelProvider provider = adapter.create(createDescriptor());

    provider.stream(
        createRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        });

    HttpRequest req = capturedRequest.get();
    assertNotNull(req);
    assertTrue(req.headers().firstValue("Authorization").isEmpty());
  }

  /** 验证端到端成功生命周期：通过回调注入 SSE 事件并触发 onComplete。 */
  @Test
  void test_endToEndStreamLifecycle() {
    List<ProviderStreamEvent> receivedEvents = new ArrayList<>();
    AtomicReference<ProviderCompletion> receivedCompletion = new AtomicReference<>();

    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(httpClient, workerExecutor, scheduler) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            callback.onOpen(new HttpOpenMetadata(200, Map.of()));
            callback.onEvent(
                new ServerSentEvent(
                    null, "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_life\"}}"));
            callback.onEvent(
                new ServerSentEvent(
                    null, "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"));
            callback.onEvent(
                new ServerSentEvent(
                    null,
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_life\",\"status\":\"completed\"}}"));
            callback.onComplete();
            return new ProviderStream() {
              @Override
              public void cancel() {}

              @Override
              public boolean isCancelled() {
                return false;
              }
            };
          }
        };

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());

    provider.stream(
        createRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            receivedEvents.add(event);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            receivedCompletion.set(completion);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        });

    assertEquals(1, receivedEvents.size());
    assertNotNull(receivedCompletion.get());
    assertEquals("hello", receivedCompletion.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, receivedCompletion.get().response().stopReason());
  }

  /** 验证底座传输层异常被安全映射并投递给 handler 的 onError。 */
  @Test
  void test_transportFailureMappedToOnError() {
    AtomicReference<ProviderException> receivedError = new AtomicReference<>();

    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(httpClient, workerExecutor, scheduler) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            callback.onFailure(
                new TransportException(TransportErrorKind.TIMEOUT, "connect timed out"));
            return new ProviderStream() {
              @Override
              public void cancel() {}

              @Override
              public boolean isCancelled() {
                return false;
              }
            };
          }
        };

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    ModelProvider provider = adapter.create(createDescriptor());

    provider.stream(
        createRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            receivedError.set(error);
          }
        });

    assertNotNull(receivedError.get());
    assertEquals(ProviderErrorKind.TRANSIENT, receivedError.get().kind());
  }

  /** 验证 adapter 元数据及流关闭等生命周期路径。 */
  @Test
  void test_adapterMetadataAndLifecycleBranches() {
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(httpClient, workerExecutor, scheduler) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            callback.onOpen(new HttpOpenMetadata(200, Map.of()));
            callback.onComplete();
            return new ProviderStream() {
              @Override
              public void cancel() {}

              @Override
              public boolean isCancelled() {
                return false;
              }
            };
          }
        };

    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    assertEquals(ProviderType.OPENAI_RESPONSES, adapter.providerType());

    ProviderDescriptor desc = createDescriptor();
    ModelProvider provider = adapter.create(desc);
    assertNotNull(provider);

    provider.stream(
        createRequest(),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        });
  }
}
