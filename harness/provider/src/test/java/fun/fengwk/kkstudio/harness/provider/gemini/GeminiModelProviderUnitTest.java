package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** GeminiModelProvider 生命周期调度与传输层交互单元测试。 */
class GeminiModelProviderUnitTest {

  private ProviderDescriptor descriptor;
  private ProviderRequest request;
  private HttpClient client;
  private ExecutorService exec;
  private ScheduledExecutorService sched;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "google-unit",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    ModelPricing pricing =
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
            BigDecimal.ZERO);

    ModelDescriptor model =
        new ModelDescriptor(
            "google-unit",
            "gemini-2.5-flash",
            "gemini-2.5-flash",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            pricing);

    ModelVariant variant = new ModelVariant("default");
    request =
        new ProviderRequest(
            model,
            variant,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")))),
            List.of(),
            ProviderCacheControl.none());

    exec = Executors.newSingleThreadExecutor();
    sched = Executors.newSingleThreadScheduledExecutor();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.shutdownNow();
    }
    if (exec != null) {
      exec.shutdownNow();
    }
    if (sched != null) {
      sched.shutdownNow();
    }
  }

  /** 验证带有凭据时设置 x-goog-api-key header，并且空凭据时以匿名方式请求（不添加 header）。 */
  @Test
  void passesApiKeyHeaderWhenCredentialProvided() {
    AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
    JdkHttpSseTransport mockTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest req,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            capturedReq.set(req);
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

    URI targetUri =
        URI.create(
            "https://generativelanguage.googleapis.com/models/gemini-2.5-flash:streamGenerateContent?alt=sse");
    GeminiModelProvider providerWithKey =
        new GeminiModelProvider(mockTransport, descriptor, "test-api-key-123", targetUri);

    providerWithKey.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        });

    assertNotNull(capturedReq.get());
    assertEquals(
        "test-api-key-123", capturedReq.get().headers().firstValue("x-goog-api-key").orElse(null));
    assertEquals(targetUri, capturedReq.get().uri());

    // 匿名调用
    capturedReq.set(null);
    GeminiModelProvider anonymousProvider =
        new GeminiModelProvider(mockTransport, descriptor, null, targetUri);
    anonymousProvider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        });

    assertNotNull(capturedReq.get());
    assertTrue(capturedReq.get().headers().firstValue("x-goog-api-key").isEmpty());
  }

  /** 验证传输层异步回调抛出异常时的错误分类交付。 */
  @Test
  void handlesAsyncTransportFailure() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport mockTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest req,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            callbackRef.set(callback);
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

    URI targetUri = URI.create("https://example.com/stream");
    GeminiModelProvider provider =
        new GeminiModelProvider(mockTransport, descriptor, "k", targetUri);

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            caught.set(error);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        });

    assertNotNull(callbackRef.get());
    callbackRef
        .get()
        .onFailure(new TransportException(TransportErrorKind.IO, "simulated transport error"));

    assertNotNull(caught.get());
    assertEquals(ProviderErrorKind.TRANSIENT, caught.get().kind());
  }

  /** 验证端到端接收 chunk、完成响应与 Replay 交付。 */
  @Test
  void handlesSuccessfulStreamEventsAndCompletion() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport mockTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest req,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            callbackRef.set(callback);
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

    URI targetUri = URI.create("https://example.com/stream");
    GeminiModelProvider provider =
        new GeminiModelProvider(mockTransport, descriptor, "k", targetUri);

    AtomicReference<ProviderCompletion> completed = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completed.set(completion);
          }
        });

    HttpSseCallback cb = callbackRef.get();
    cb.onOpen(new HttpOpenMetadata(200, Map.of()));
    cb.onEvent(
        new ServerSentEvent(
            "message",
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi back\"}]},\"finishReason\":\"STOP\"}]}"));
    cb.onComplete();

    assertNotNull(completed.get());
    assertEquals("hi back", completed.get().response().text());
    assertEquals(GenerationStopReason.COMPLETE, completed.get().response().stopReason());
  }

  /** 验证 descriptor() 与 toString() 行为。 */
  @Test
  void verifiesDescriptorAndToString() {
    JdkHttpSseTransport transport = new JdkHttpSseTransport(client, exec, sched);
    URI targetUri = URI.create("https://example.com/stream");
    GeminiModelProvider provider = new GeminiModelProvider(transport, descriptor, "k", targetUri);

    assertEquals(descriptor, provider.descriptor());
    assertEquals("GeminiModelProvider[]", provider.toString());
  }

  /** 验证非法 Header（如含换行符的 API Key）时抛出 INVALID_REQUEST。 */
  @Test
  void handlesInvalidHeaderValue() {
    JdkHttpSseTransport transport = new JdkHttpSseTransport(client, exec, sched);
    URI targetUri = URI.create("https://example.com/stream");
    GeminiModelProvider provider =
        new GeminiModelProvider(transport, descriptor, "bad\nkey", targetUri);

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorRef.set(error);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        });

    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, errorRef.get().kind());
  }

  /** 验证 transport.stream 抛出未检查异常时被如实包装抛出。 */
  @Test
  void handlesTransportStreamException() {
    JdkHttpSseTransport mockTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest req,
              ModelCallTimeoutPolicy timeout,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            throw new IllegalStateException("stream creation failed");
          }
        };

    URI targetUri = URI.create("https://example.com/stream");
    GeminiModelProvider provider =
        new GeminiModelProvider(mockTransport, descriptor, "k", targetUri);

    assertThrows(
        RuntimeException.class,
        () ->
            provider.stream(
                request,
                new ProviderStreamHandler() {
                  @Override
                  public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

                  @Override
                  public void onError(ProviderException error, ProviderStream stream) {}

                  @Override
                  public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
                }));
  }

  /** 验证 onEvent 与 onComplete 中捕获 ProviderException 时通过 bridge.emitError 分发。 */
  @Test
  void handlesCallbackExceptionsInEventAndComplete() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport mockTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest req,
              ModelCallTimeoutPolicy timeout,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            callbackRef.set(callback);
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

    URI targetUri = URI.create("https://example.com/stream");
    GeminiModelProvider provider =
        new GeminiModelProvider(mockTransport, descriptor, "k", targetUri);

    // 1. onEvent 抛出异常（非法的 JSON）
    AtomicReference<ProviderException> eventErrorRef = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            eventErrorRef.set(error);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        });

    callbackRef.get().onEvent(new ServerSentEvent("message", "invalid-json"));
    assertNotNull(eventErrorRef.get());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, eventErrorRef.get().kind());

    // 2. onComplete 抛出异常（流未正常结束 finishReason 缺失）
    AtomicReference<ProviderException> completeErrorRef = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            completeErrorRef.set(error);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        });

    callbackRef.get().onComplete();
    assertNotNull(completeErrorRef.get());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, completeErrorRef.get().kind());
  }

  /** 验证非法 endpoint 导致 resolveStreamUri 失败时抛出 INVALID_REQUEST。 */
  @Test
  void handlesInvalidEndpointStreamUriFailure() {
    JdkHttpSseTransport transport = new JdkHttpSseTransport(client, exec, sched);
    ProviderDescriptor badEndpointDesc =
        new ProviderDescriptor(
            "bad-desc",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com?query=not_allowed",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    URI targetUri = URI.create("https://example.com/stream");
    GeminiModelProvider provider =
        new GeminiModelProvider(transport, badEndpointDesc, "k", targetUri);

    AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorRef.set(error);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        });

    assertNotNull(errorRef.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, errorRef.get().kind());
  }
}
