package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;

/** 验证 OpenAI Responses 提供方的安全防护规则与无 Spring 架构边界隔离。 */
class OpenAiResponsesSecurityAndArchitectureTest {

  /** 验证异常映射完整保留上游响应体，且绝不泄漏内部 transport message、请求凭证或底层 cause。 */
  @Test
  void test_sensitiveDataLeakGuards() {
    String fakeToken = "fake-token-123456";
    String internalUrl = "https://api.example.com/v1/responses";
    byte[] errorBody =
        ("{\"error\": {\"message\": \"Unauthorized for "
                + fakeToken
                + " at "
                + internalUrl
                + "\", \"code\": \"invalid_api_key\"}}")
            .getBytes(StandardCharsets.UTF_8);

    TransportException transportEx =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "internal auth failure details",
            401,
            errorBody,
            false,
            null,
            new RuntimeException("secret underlying transport cause"));

    ProviderException pe = OpenAiResponsesErrorMapper.mapTransportException(transportEx);
    assertNotNull(pe);
    assertEquals("HTTP 401\n" + new String(errorBody, StandardCharsets.UTF_8), pe.getMessage());
    assertTrue(pe.getMessage().contains(fakeToken));
    assertFalse(pe.getMessage().contains("internal auth failure details"));
    assertNull(pe.getCause());
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
