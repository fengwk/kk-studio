package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** 专门针对 AnthropicModelProvider 及其内部回调各分支状态流转的轻量单元测试。 */
class AnthropicModelProviderUnitTest {

  private ProviderDescriptor descriptor;
  private ProviderRequest request;
  private HttpClient client;
  private ExecutorService exec;
  private ScheduledExecutorService sched;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "anthropic-unit",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(3)),
            UUID.randomUUID());

    ModelDescriptor model =
        new ModelDescriptor(
            "anthropic-unit",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
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
                BigDecimal.ZERO));

    request =
        new ProviderRequest(
            model,
            new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
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
    if (exec != null) {
      exec.shutdownNow();
    }
    if (sched != null) {
      sched.shutdownNow();
    }
  }

  @Test
  void throwsUnclassifiedRuntimeExceptionOnSynchronousTransportExceptionWithoutCallingHandler() {
    // 1. 同步抛出 TransportException (IO)
    JdkHttpSseTransport ioTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            throw new TransportException(TransportErrorKind.IO, "simulated io error");
          }
        };

    AnthropicModelProvider ioProvider =
        new AnthropicModelProvider(
            ioTransport, descriptor, "key", URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caughtIo = new AtomicReference<>();
    RuntimeException exIo =
        assertThrows(
            RuntimeException.class,
            () ->
                ioProvider.stream(
                    request,
                    new ProviderStreamHandler() {
                      @Override
                      public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

                      @Override
                      public void onComplete(
                          ProviderCompletion completion, ProviderStream stream) {}

                      @Override
                      public void onError(ProviderException error, ProviderStream stream) {
                        caughtIo.set(error);
                      }
                    }));
    assertEquals("transport execution failed", exIo.getMessage());
    assertNull(exIo.getCause());
    assertNull(
        caughtIo.get(), "handler.onError must not be called on synchronous transport exception");

    // 2. 同步抛出 TransportException (EXECUTOR_REJECTED)
    JdkHttpSseTransport rejectedTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            throw new TransportException(TransportErrorKind.EXECUTOR_REJECTED, "rejected");
          }
        };

    AnthropicModelProvider rejProvider =
        new AnthropicModelProvider(
            rejectedTransport,
            descriptor,
            "key",
            URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caughtRej = new AtomicReference<>();
    RuntimeException exRej =
        assertThrows(
            RuntimeException.class,
            () ->
                rejProvider.stream(
                    request,
                    new ProviderStreamHandler() {
                      @Override
                      public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

                      @Override
                      public void onComplete(
                          ProviderCompletion completion, ProviderStream stream) {}

                      @Override
                      public void onError(ProviderException error, ProviderStream stream) {
                        caughtRej.set(error);
                      }
                    }));
    assertEquals("transport execution failed", exRej.getMessage());
    assertNull(exRej.getCause());
    assertNull(
        caughtRej.get(), "handler.onError must not be called on synchronous executor rejection");
  }

  @Test
  void handlesAsyncTransportFailureInCallback() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport asyncTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
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

    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            asyncTransport, descriptor, "key", URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    ProviderStream stream =
        provider.stream(
            request,
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {
                caught.set(error);
              }
            });

    assertNotNull(stream);
    assertNotNull(callbackRef.get());

    // 异步触发 onFailure
    callbackRef
        .get()
        .onFailure(new TransportException(TransportErrorKind.IO, "async socket reset"));
    assertNotNull(caught.get());
    assertEquals(ProviderErrorKind.TRANSIENT, caught.get().kind());
  }

  @Test
  void throwsUnclassifiedRuntimeExceptionOnSynchronousTransportFailure() {
    JdkHttpSseTransport failingTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
              ModelCallTimeoutPolicy timeoutPolicy,
              HttpSseLimits limits,
              HttpSseCallback callback) {
            throw new RuntimeException("unexpected crash with sensitive detail");
          }
        };

    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            failingTransport,
            descriptor,
            "key",
            URI.create("https://api.anthropic.com/v1/messages"));

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () ->
                provider.stream(
                    request,
                    new ProviderStreamHandler() {
                      @Override
                      public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

                      @Override
                      public void onComplete(
                          ProviderCompletion completion, ProviderStream stream) {}

                      @Override
                      public void onError(ProviderException error, ProviderStream stream) {}
                    }));
    assertEquals("transport execution failed", ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  void handlesInvalidHeaderValueSafelyWithoutLeakingSecrets() {
    JdkHttpSseTransport mockTransport = new JdkHttpSseTransport(client, exec, sched);
    String sensitiveKeyWithCRLF = "secret-key\r\nInjected-Header: evil";
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            mockTransport,
            descriptor,
            sensitiveKeyWithCRLF,
            URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            caught.set(error);
          }
        });

    assertNotNull(caught.get());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, caught.get().kind());
    assertFalse(caught.get().getMessage().contains("secret-key"));
    assertFalse(caught.get().getMessage().contains("evil"));
  }

  @Test
  void toStringDoesNotLeakDescriptorOrEndpoint() {
    JdkHttpSseTransport mockTransport = new JdkHttpSseTransport(client, exec, sched);
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            mockTransport,
            descriptor,
            "secret-api-key",
            URI.create("https://api.anthropic.com/v1/messages"));

    String str = provider.toString();
    assertEquals("AnthropicModelProvider[]", str);
    assertFalse(str.contains("secret-api-key"));
    assertFalse(str.contains("anthropic.com"));
  }

  @Test
  void handlesCallbackOnEventFailure() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport hookTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
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

    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            hookTransport, descriptor, "key", URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            caught.set(error);
          }
        });

    HttpSseCallback cb = callbackRef.get();
    assertNotNull(cb);

    cb.onOpen(new HttpOpenMetadata(200, Map.of()));
    cb.onEvent(
        new ServerSentEvent(
            "error", "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}"));
    assertNotNull(caught.get());
    assertEquals(ProviderErrorKind.TRANSIENT, caught.get().kind());
  }

  @Test
  void handlesCallbackOnCompleteFailure() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport hookTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
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

    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            hookTransport, descriptor, "key", URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            caught.set(error);
          }
        });

    HttpSseCallback cb = callbackRef.get();
    assertNotNull(cb);

    cb.onOpen(new HttpOpenMetadata(200, Map.of()));
    cb.onComplete();
    assertNotNull(caught.get());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, caught.get().kind());
  }

  @Test
  void handlesCallbackOnFailureSilentCancellation() {
    AtomicReference<HttpSseCallback> callbackRef = new AtomicReference<>();
    JdkHttpSseTransport hookTransport =
        new JdkHttpSseTransport(client, exec, sched) {
          @Override
          public ProviderStream stream(
              HttpRequest request,
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

    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            hookTransport, descriptor, "key", URI.create("https://api.anthropic.com/v1/messages"));

    AtomicReference<ProviderException> caught = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            caught.set(error);
          }
        });

    HttpSseCallback cb = callbackRef.get();
    assertNotNull(cb);

    cb.onFailure(new TransportException(TransportErrorKind.CANCELLED, "cancelled"));
    assertNull(caught.get());
  }
}
