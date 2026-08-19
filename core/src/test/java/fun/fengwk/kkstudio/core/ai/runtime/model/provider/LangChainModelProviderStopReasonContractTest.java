package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;

/**
 * LangChain4j finish reason 必须无损归一化到 canonical {@link GenerationStopReason}，且与 tool call 存在性正交：
 * 公共映射绝不根据 hasToolCalls 覆盖结果。
 *
 * <p>协议事实（LangChain4j 1.18.0）：OpenAI Chat stop/length/tool_calls/content_filter；OpenAI Responses
 * completed(含 calls)/incomplete/failed；Anthropic end_turn/tool_use/max_tokens；Gemini STOP/function
 * (builder 在带 calls 时归一为 TOOL_EXECUTION)/MAX_TOKENS/SAFETY。null 与 OTHER 不可映射（INVALID_RESPONSE）；
 * OTHER+calls 特例只由 MiniMax adapter 的钩子传入。
 */
class LangChainModelProviderStopReasonContractTest {

  @Test
  void mapsEveryExplicitFinishReason() {
    assertEquals(
        GenerationStopReason.COMPLETE,
        LangChainModelProvider.toStopReason(FinishReason.STOP, false));
    assertEquals(
        GenerationStopReason.LENGTH,
        LangChainModelProvider.toStopReason(FinishReason.LENGTH, false));
    assertEquals(
        GenerationStopReason.COMPLETE,
        LangChainModelProvider.toStopReason(FinishReason.TOOL_EXECUTION, false));
    assertEquals(
        GenerationStopReason.FILTERED,
        LangChainModelProvider.toStopReason(FinishReason.CONTENT_FILTER, false));
  }

  /** 携带可执行 tool call 的回合不覆盖 generation stop reason：STOP+calls 与 LENGTH+calls 原样保留。 */
  @Test
  void preservesGenerationReasonIndependentlyOfToolCalls() {
    assertEquals(
        GenerationStopReason.COMPLETE,
        LangChainModelProvider.toStopReason(FinishReason.STOP, true));
    assertEquals(
        GenerationStopReason.LENGTH,
        LangChainModelProvider.toStopReason(FinishReason.LENGTH, true));
    assertEquals(
        GenerationStopReason.COMPLETE,
        LangChainModelProvider.toStopReason(FinishReason.TOOL_EXECUTION, true));
    assertEquals(
        GenerationStopReason.FILTERED,
        LangChainModelProvider.toStopReason(FinishReason.CONTENT_FILTER, true));
  }

  /** null / OTHER 是不可映射的 terminal shape：返回 null，由 adapter 转 INVALID_RESPONSE retry。 */
  @Test
  void nullAndOtherFinishReasonsAreNotMappable() {
    assertNull(LangChainModelProvider.toStopReason(null, false));
    assertNull(LangChainModelProvider.toStopReason(null, true));
    assertNull(LangChainModelProvider.toStopReason(FinishReason.OTHER, false));
  }

  /** MiniMax OTHER+calls 特例：只在对应 adapter 的钩子传入时才映射为 COMPLETE。 */
  @Test
  void otherWithCallsOnlyMapsToCompleteWhenTheAdapterHookClaimsIt() {
    assertEquals(
        GenerationStopReason.COMPLETE,
        LangChainModelProvider.toStopReason(FinishReason.OTHER, true));
  }
}
