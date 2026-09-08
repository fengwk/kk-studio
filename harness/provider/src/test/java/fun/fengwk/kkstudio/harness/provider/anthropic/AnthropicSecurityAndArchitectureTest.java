package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 验证 Anthropic 组件的 toString 与异常脱敏安全约束。 */
class AnthropicSecurityAndArchitectureTest {

  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;

  @AfterEach
  void tearDown() {
    if (httpClient != null) {
      httpClient.shutdownNow();
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  @Test
  void toStringNeverLeaksApiKeyOrRequestBody() {
    String sensitiveKey = "sk-ant-api03-TOP-SECRET-KEY-123456789";
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    JdkHttpSseTransport transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, sensitiveKey);
    assertFalse(adapter.toString().contains(sensitiveKey));
    assertFalse(adapter.toString().contains("sk-ant"));

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "p1",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
            UUID.randomUUID());

    AnthropicModelProvider modelProvider = (AnthropicModelProvider) adapter.create(descriptor);
    assertFalse(modelProvider.toString().contains(sensitiveKey));
    assertFalse(modelProvider.toString().contains("sk-ant"));

    AnthropicEncodedRequest encoded =
        new AnthropicEncodedRequest(
            "{\"secret\":\"payload_content\"}".getBytes(StandardCharsets.UTF_8),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    assertFalse(encoded.toString().contains("secret"));
    assertFalse(encoded.toString().contains("payload_content"));
  }

  @Test
  void rejectsNonAnthropicDescriptor() {
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    JdkHttpSseTransport transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "key");
    ProviderDescriptor openAiDescriptor =
        new ProviderDescriptor(
            "p2",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
            UUID.randomUUID());

    assertThrows(IllegalArgumentException.class, () -> adapter.create(openAiDescriptor));
  }

  @Test
  void errorMappingNeverLeaksSensitiveUrlOrCause() {
    String secret = "sk-ant-SECRET-BEARER";
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "http://api.anthropic.com/v1/messages failed with key " + secret,
            401,
            ("{\"error\":{\"message\":\"invalid key " + secret + "\"}}")
                .getBytes(StandardCharsets.UTF_8),
            null,
            new RuntimeException("cause containing " + secret));

    ProviderException mapped = AnthropicErrorMapper.mapTransportException(ex);
    assertNull(mapped.getCause());
    assertFalse(mapped.getMessage().contains(secret));
    assertFalse(mapped.getMessage().contains("messages"));
    assertEquals(AnthropicErrorMapper.MSG_AUTH, mapped.getMessage());
  }
}
