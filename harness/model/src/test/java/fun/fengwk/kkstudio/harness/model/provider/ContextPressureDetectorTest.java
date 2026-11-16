package fun.fengwk.kkstudio.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelUsage;

import java.util.Map;

/** 成功生成响应的 context-pressure 契约：只依赖 authoritative usage、stop reason 与 context window。 */
class ContextPressureDetectorTest {

  /** 意图：authoritative usage 的 prompt tokens 达到/超过 window 即 silent context wall；低于 window 不命中。 */
  @Test
  void silentPromptUsageReachingWindowIsContextPressure() {
    long window = 128_000L;
    assertTrue(
        ContextPressureDetector.detectResponse(
            null, new ModelUsage(100_000, 10, 28_000, 0, Map.of(), 0L, 128_010L), window));
    assertTrue(
        ContextPressureDetector.detectResponse(
            null, new ModelUsage(128_000, 10, 0, 0, Map.of(), 0L, 128_010L), window));
    assertFalse(
        ContextPressureDetector.detectResponse(
            null, new ModelUsage(100_000, 10, 27_999, 0, Map.of(), 0L, 127_999L), window));
  }

  /** 意图：严格 LENGTH 恢复条件——LENGTH + output==0 + prompt ≥ ceil(window*0.99) 才成立；低于边界或含 output 不成立。 */
  @Test
  void strictLengthIsContextPressureOnlyAtTheWindowEdge() {
    long window = 128_000L;
    long threshold = 126_720L; // ceil(128000 * 0.99)
    assertTrue(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.LENGTH,
            new ModelUsage(threshold, 0, 0, 0, Map.of(), 0L, threshold),
            window));
    assertTrue(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.LENGTH,
            new ModelUsage(90_000, 0, threshold - 90_000L, 0, Map.of(), 0L, threshold),
            window));
    assertFalse(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.LENGTH,
            new ModelUsage(threshold - 1, 0, 0, 0, Map.of(), 0L, threshold - 1),
            window));
    // 有 output 的普通 LENGTH 不是 context wall（模型只是到达 max tokens）。
    assertFalse(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.LENGTH,
            new ModelUsage(threshold, 16, 0, 0, Map.of(), 0L, threshold + 16),
            window));
    // 明显低于 window 的 LENGTH 同样不命中。
    assertFalse(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.LENGTH,
            new ModelUsage(10_000, 0, 0, 0, Map.of(), 0L, 10_000L),
            window));
    // 非 LENGTH stop reason 即使 prompt 达到 0.99 阈值（仍低于 window）也不触发严格恢复。
    assertFalse(
        ContextPressureDetector.detectResponse(
            GenerationStopReason.COMPLETE,
            new ModelUsage(126_720, 0, 0, 0, Map.of(), 0L, 126_720L),
            window));
  }

  /** 意图：缺少 usage 或非正 window 必须由公共边界拒绝，不能静默当作无压力。 */
  @Test
  void rejectsMissingUsageOrNonPositiveWindow() {
    assertThrows(
        NullPointerException.class,
        () ->
            ContextPressureDetector.detectResponse(GenerationStopReason.COMPLETE, null, 128_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContextPressureDetector.detectResponse(
                GenerationStopReason.COMPLETE, new ModelUsage(1, 0, 0, 0, Map.of(), 0L, 1L), 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContextPressureDetector.detectResponse(
                GenerationStopReason.LENGTH, new ModelUsage(1, 0, 0, 0, Map.of(), 0L, 1L), -1));
  }
}
