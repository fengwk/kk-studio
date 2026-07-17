package fun.fengwk.kkstudio.harness.model.provider.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;

/** LangChain4j finish reason 必须无损归一化到 Provider 公共契约。 */
class LangChainModelProviderStopReasonContractTest {

  /** SDK 明确给出的结束原因优先于响应中是否存在工具调用。 */
  @Test
  void mapsEveryExplicitFinishReasonExactly() {
    assertMapping(FinishReason.STOP, ProviderStopReason.COMPLETED);
    assertMapping(FinishReason.LENGTH, ProviderStopReason.LENGTH);
    assertMapping(FinishReason.TOOL_EXECUTION, ProviderStopReason.TOOL_CALLS);
    assertMapping(FinishReason.CONTENT_FILTER, ProviderStopReason.CONTENT_FILTER);
    assertMapping(FinishReason.OTHER, ProviderStopReason.OTHER);
  }

  /** 只有 SDK 未给 finish reason 时才按完整 tool calls 回退。 */
  @Test
  void fallsBackToToolCallsOnlyForNullFinishReason() {
    assertEquals(ProviderStopReason.COMPLETED, LangChainModelProvider.toStopReason(null, false));
    assertEquals(ProviderStopReason.TOOL_CALLS, LangChainModelProvider.toStopReason(null, true));
  }

  private static void assertMapping(
      FinishReason finishReason, ProviderStopReason expectedStopReason) {
    assertEquals(expectedStopReason, LangChainModelProvider.toStopReason(finishReason, false));
    assertEquals(expectedStopReason, LangChainModelProvider.toStopReason(finishReason, true));
  }
}
