package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;

import java.util.Map;

/**
 * Tests official OpenAI Responses cache-write TTL facts without guessing missing breakdown values.
 */
class OpenAiResponsesStreamUsageTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Test
  void mapsCompleteTtlBreakdownWhenItMatchesCacheWriteTotal() throws Exception {
    OpenAiResponsesStreamUsage usage = new OpenAiResponsesStreamUsage();
    usage.apply(
        MAPPER.readTree(
            """
            {"usage":{"input_tokens":100,"output_tokens":10,
              "input_tokens_details":{"cache_write_tokens":10,
                "cache_write_tokens_details":{"ttl_30m_tokens":6,"ttl_12h_tokens":4}}}}
            """),
        "gpt-5.6");

    assertEquals(Map.of(1800L, 6L, 43200L, 4L), usage.toModelUsage(null).cacheWriteByTtl());
  }

  @Test
  void leavesMissingTtlBreakdownUnattributed() throws Exception {
    OpenAiResponsesStreamUsage usage = new OpenAiResponsesStreamUsage();
    usage.apply(
        MAPPER.readTree(
            """
            {"usage":{"input_tokens":100,"output_tokens":10,
              "input_tokens_details":{"cache_write_tokens":10,
                "cache_write_tokens_details":{"ttl_30m_tokens":6}}}}
            """),
        "gpt-5.6");

    assertTrue(usage.toModelUsage(null).cacheWriteByTtl().isEmpty());
  }

  @Test
  void fallsBackToEffectiveTtlOnlyWhenNativeBreakdownIsAbsent() throws Exception {
    OpenAiResponsesStreamUsage usage = new OpenAiResponsesStreamUsage();
    usage.apply(
        MAPPER.readTree(
            """
            {"usage":{"input_tokens":100,"output_tokens":10,
              "input_tokens_details":{"cache_write_tokens":10}}}
            """),
        "gpt-5.6");

    assertEquals(Map.of(43200L, 10L), usage.toModelUsage(43200L).cacheWriteByTtl());
  }

  @Test
  void nativeTtlBreakdownTakesPrecedenceOverEffectiveTtl() throws Exception {
    OpenAiResponsesStreamUsage usage = new OpenAiResponsesStreamUsage();
    usage.apply(
        MAPPER.readTree(
            """
            {"usage":{"input_tokens":100,"output_tokens":10,
              "input_tokens_details":{"cache_write_tokens":10,
                "cache_write_tokens_details":{"ttl_30m_tokens":10,"ttl_12h_tokens":0}}}}
            """),
        "gpt-5.6");

    assertEquals(Map.of(1800L, 10L, 43200L, 0L), usage.toModelUsage(43200L).cacheWriteByTtl());
  }

  @Test
  void rejectsTtlBreakdownThatDoesNotEqualCacheWriteTotal() throws Exception {
    OpenAiResponsesStreamUsage usage = new OpenAiResponsesStreamUsage();
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                usage.apply(
                    MAPPER.readTree(
                        """
                        {"usage":{"input_tokens":100,"output_tokens":10,
                          "input_tokens_details":{"cache_write_tokens":10,
                            "cache_write_tokens_details":{"ttl_30m_tokens":6,"ttl_12h_tokens":3}}}}
                        """),
                    "gpt-5.6"));

    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals("cache write TTL breakdown does not match total", error.getMessage());
  }
}
