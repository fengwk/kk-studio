package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;

/** 验证 OpenAI Responses 提供方的安全防护规则与无 Spring 架构边界隔离。 */
class OpenAiResponsesSecurityAndArchitectureTest {

  /** 验证异常映射绝不泄漏 API 凭证、原始敏感 URL、敏感参数或未脱敏响应体。 */
  @Test
  void test_sensitiveDataLeakGuards() {
    String leakToken = "sk-proj-super-secret-token-123456";
    String internalUrl = "https://internal.openai.corp/v1/responses";
    byte[] errorBody =
        ("{\"error\": {\"message\": \"Unauthorized for "
                + leakToken
                + " at "
                + internalUrl
                + "\", \"code\": \"invalid_api_key\"}}")
            .getBytes(StandardCharsets.UTF_8);

    TransportException transportEx =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "auth failure", 401, errorBody, null);

    ProviderException pe = OpenAiResponsesErrorMapper.mapTransportException(transportEx);
    assertNotNull(pe);
    assertFalse(pe.getMessage().contains(leakToken));
    assertFalse(pe.getMessage().contains("super-secret"));
    assertFalse(pe.getMessage().contains("internal.openai.corp"));
  }

  /** 验证提供方包完全无 Spring 框架依赖与注解，保持纯净轻量。 */
  @Test
  void test_springFreeArchitecture() {
    Class<?>[] classes = {
      OpenAiPromptCacheMode.class,
      OpenAiResponsesConfig.class,
      OpenAiResponsesEndpoints.class,
      OpenAiResponsesEncodedRequest.class,
      OpenAiResponsesErrorMapper.class,
      OpenAiResponsesModelProvider.class,
      OpenAiResponsesPrefixHasher.class,
      OpenAiResponsesProviderAdapter.class,
      OpenAiResponsesRequestEncoder.class,
      OpenAiResponsesStreamAccumulator.class,
      OpenAiResponsesStreamBridge.class
    };

    for (Class<?> clazz : classes) {
      for (Annotation anno : clazz.getAnnotations()) {
        String annoName = anno.annotationType().getName();
        assertFalse(
            annoName.startsWith("org.springframework"),
            clazz.getSimpleName() + " must not contain Spring annotation: " + annoName);
      }
    }
  }
}
