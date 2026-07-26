package fun.fengwk.kkstudio.core.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;

/** LangChain4j finish reason 必须归一化到 Provider 公共契约。 */
class LangChainModelProviderStopReasonContractTest {

  /** 无 tool call 时，SDK finish reason 无损映射。 */
  @Test
  void mapsEveryExplicitFinishReasonWithoutToolCalls() {
    assertEquals(
        ProviderStopReason.COMPLETED,
        LangChainModelProvider.toStopReason(FinishReason.STOP, false));
    assertEquals(
        ProviderStopReason.LENGTH, LangChainModelProvider.toStopReason(FinishReason.LENGTH, false));
    assertEquals(
        ProviderStopReason.TOOL_CALLS,
        LangChainModelProvider.toStopReason(FinishReason.TOOL_EXECUTION, false));
    assertEquals(
        ProviderStopReason.CONTENT_FILTER,
        LangChainModelProvider.toStopReason(FinishReason.CONTENT_FILTER, false));
    assertEquals(
        ProviderStopReason.OTHER, LangChainModelProvider.toStopReason(FinishReason.OTHER, false));
  }

  /** 完整 tool calls 优先于兼容端点可能给出的 STOP/OTHER finish reason。 */
  @Test
  void prefersToolCallsWheneverExecutableCallsPresent() {
    assertEquals(ProviderStopReason.TOOL_CALLS, LangChainModelProvider.toStopReason(null, true));
    assertEquals(
        ProviderStopReason.TOOL_CALLS,
        LangChainModelProvider.toStopReason(FinishReason.STOP, true));
    assertEquals(
        ProviderStopReason.TOOL_CALLS,
        LangChainModelProvider.toStopReason(FinishReason.OTHER, true));
    assertEquals(
        ProviderStopReason.TOOL_CALLS,
        LangChainModelProvider.toStopReason(FinishReason.TOOL_EXECUTION, true));
    assertEquals(ProviderStopReason.COMPLETED, LangChainModelProvider.toStopReason(null, false));
  }
}
