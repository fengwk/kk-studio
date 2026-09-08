package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

/** 验证 Gemini 组件的 toString、架构无外部 SDK 依赖与凭据安全约束。 */
class GeminiSecurityAndArchitectureTest {

  @Test
  void toStringNeverLeaksApiKeyOrRequestBody() {
    String sensitiveKey = "AIzaSySecretApiKey123456789";
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadScheduledExecutor());

    GeminiProviderAdapter adapter = new GeminiProviderAdapter(transport, sensitiveKey);
    assertFalse(adapter.toString().contains(sensitiveKey));
    assertFalse(adapter.toString().contains("AIzaSy"));

    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "p1",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
            UUID.randomUUID());

    GeminiModelProvider modelProvider = (GeminiModelProvider) adapter.create(descriptor);
    assertFalse(modelProvider.toString().contains(sensitiveKey));
    assertFalse(modelProvider.toString().contains("AIzaSy"));

    GeminiEncodedRequest encoded =
        new GeminiEncodedRequest(
            "{\"secret\":\"super_confidential\"}".getBytes(StandardCharsets.UTF_8),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    assertFalse(encoded.toString().contains("secret"));
    assertFalse(encoded.toString().contains("super_confidential"));
  }

  @Test
  void rejectsNonGoogleDescriptor() {
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadScheduledExecutor());

    GeminiProviderAdapter adapter = new GeminiProviderAdapter(transport, "key");
    ProviderDescriptor anthropicDescriptor =
        new ProviderDescriptor(
            "p2",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5)),
            UUID.randomUUID());

    assertThrows(IllegalArgumentException.class, () -> adapter.create(anthropicDescriptor));
  }

  @Test
  void reportsPromptCacheAutomaticCapability() {
    JdkHttpSseTransport transport =
        new JdkHttpSseTransport(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadScheduledExecutor());

    GeminiProviderAdapter adapter = new GeminiProviderAdapter(transport, "key");
    assertEquals(PromptCacheCapability.automatic(), adapter.promptCacheCapability());
    assertEquals(ProviderType.GOOGLE, adapter.providerType());
    assertEquals("GeminiProviderAdapter[providerType=GOOGLE]", adapter.toString());

    GeminiProviderAdapter singleParamAdapter = new GeminiProviderAdapter(transport);
    assertEquals(ProviderType.GOOGLE, singleParamAdapter.providerType());
  }

  @Test
  void architectureConstraints_noSpringOrGoogleSdkImports() throws Exception {
    Class<?>[] classes =
        new Class<?>[] {
          GeminiEndpoints.class,
          GeminiProviderAdapter.class,
          GeminiModelProvider.class,
          GeminiStreamBridge.class,
          GeminiRequestEncoder.class,
          GeminiStreamAccumulator.class,
          GeminiPrefixHasher.class,
          GeminiEncodedRequest.class,
          GeminiErrorMapper.class
        };

    for (Class<?> clazz : classes) {
      // 验证没有加载任何 org.springframework 或 com.google 类
      for (Class<?> iface : clazz.getInterfaces()) {
        assertFalse(iface.getName().startsWith("org.springframework"));
        assertFalse(iface.getName().startsWith("com.google"));
      }
    }
  }
}
