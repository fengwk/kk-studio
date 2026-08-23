package fun.fengwk.kkstudio.platform.ai.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.ResponseUsage;
import org.junit.jupiter.api.Test;

class OpenAiResponsesUsageJsonMapperTest {

  @Test
  void fillsMissingUsageBreakdownsWithoutChangingTotals() throws Exception {
    ResponseUsage usage =
        OpenAiResponsesUsageJsonMapper.instance()
            .readValue(
                """
                {
                  "input_tokens": 10,
                  "output_tokens": 20,
                  "total_tokens": 30,
                  "vendor_extra": "kept"
                }
                """,
                ResponseUsage.class);

    assertEquals(10, usage.inputTokens());
    assertEquals(20, usage.outputTokens());
    assertEquals(30, usage.totalTokens());
    assertEquals(0, usage.inputTokensDetails().cachedTokens());
    assertEquals(0, usage.outputTokensDetails().reasoningTokens());
    assertEquals(
        "kept", usage._additionalProperties().get("vendor_extra").asString().orElseThrow());
  }

  @Test
  void fillsMissingNestedCountsAndPreservesProvidedCounts() throws Exception {
    ResponseUsage usage =
        OpenAiResponsesUsageJsonMapper.instance()
            .readValue(
                """
                {
                  "input_tokens": 10,
                  "input_tokens_details": {},
                  "output_tokens": 20,
                  "output_tokens_details": {"reasoning_tokens": 7},
                  "total_tokens": 30
                }
                """,
                ResponseUsage.class);

    assertEquals(0, usage.inputTokensDetails().cachedTokens());
    assertEquals(7, usage.outputTokensDetails().reasoningTokens());
  }

  @Test
  void leavesMalformedBreakdownsInvalid() throws Exception {
    ResponseUsage usage =
        OpenAiResponsesUsageJsonMapper.instance()
            .readValue(
                """
                {
                  "input_tokens": 10,
                  "input_tokens_details": {"cached_tokens": 0},
                  "output_tokens": 20,
                  "output_tokens_details": "invalid",
                  "total_tokens": 30
                }
                """,
                ResponseUsage.class);

    assertThrows(OpenAIInvalidDataException.class, usage::outputTokensDetails);
  }
}
