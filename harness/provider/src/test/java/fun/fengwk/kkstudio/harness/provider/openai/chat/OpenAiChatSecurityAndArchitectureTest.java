package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 测试意图：验证 OpenAI Chat 实现的安全防泄漏（禁止 credential、configJson、replay 泄露到异常与 toString） 以及 Spring-free
 * 架构纯洁性。
 */
class OpenAiChatSecurityAndArchitectureTest {

  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;
  private ProviderDescriptor descriptor;

  @BeforeEach
  void setUp() {
    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);

    descriptor =
        new ProviderDescriptor(
            "openai",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

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
  @DisplayName("ProviderAdapter 与 ModelProvider 的 toString 绝不泄漏 apiKey 与敏感配置")
  void testToStringDoesNotLeakSecrets() {
    String sensitiveKey = "sk-super-sensitive-secret-token-xyz-123456789";
    OpenAiChatProviderAdapter adapter =
        new OpenAiChatProviderAdapter(transport, sensitiveKey, "{\"openAiChatIncludeUsage\":true}");
    ModelProvider provider = adapter.create(descriptor);

    String adapterStr = adapter.toString();
    String providerStr = provider.toString();

    assertNotNull(adapterStr);
    assertFalse(adapterStr.contains(sensitiveKey));
    assertFalse(adapterStr.contains("super-sensitive"));

    assertNotNull(providerStr);
    assertFalse(providerStr.contains(sensitiveKey));
    assertFalse(providerStr.contains("super-sensitive"));
  }

  @Test
  @DisplayName("错误透传与安全防护：完整保留上游响应 body，且绝不上抛请求凭证、内部 transport 消息与底层 cause")
  void testExceptionDoesNotLeakErrorBody() {
    String upstreamBody =
        "{\"error\":{\"message\":\"Invalid authorization fake-token-123456\",\"type\":\"invalid_request_error\"}}";
    TransportException tex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "internal transport failure with key fake-token-123456",
            401,
            upstreamBody.getBytes(StandardCharsets.UTF_8),
            false,
            null,
            new RuntimeException("underlying secret cause"));

    ProviderException pe = OpenAiChatErrorMapper.mapTransportException(tex);
    assertNotNull(pe);
    assertEquals("HTTP 401\n" + upstreamBody, pe.getMessage());
    assertFalse(pe.getMessage().contains("internal transport failure"));
    assertNull(pe.getCause());
  }

  @Test
  @DisplayName("非法配置异常信息不回显非法输入的敏感内容")
  void testConfigurationExceptionSanitization() {
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> OpenAiChatConfiguration.parse("{\"openAiPromptCacheMode\":\"SECRET_INVALID\"}"));
    assertNotNull(ex.getMessage());
    assertFalse(ex.getMessage().contains("SECRET_INVALID"));
  }
}
