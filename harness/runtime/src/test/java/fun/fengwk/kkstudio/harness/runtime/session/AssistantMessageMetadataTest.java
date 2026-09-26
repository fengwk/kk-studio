package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;

import java.math.BigDecimal;

/** AssistantMessageMetadata 的流式生成计时字段约束：可选可空、非负，且兼容无可信计时的旧形态。 */
class AssistantMessageMetadataTest {

  private static final ModelUsage USAGE = new ModelUsage(1, 1, 0, 0, 0, 0, 2);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  /** 三参构造是旧形态兼容入口：decodeDurationMillis 归一化为 null（无可信流计时）。 */
  @Test
  void legacyConstructorLeavesDecodeDurationAbsent() {
    AssistantMessageMetadata metadata =
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST);

    assertNull(metadata.decodeDurationMillis());
    assertEquals(
        metadata, new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST, null));
  }

  /** 显式计时必须非负：0 与正值合法，负值被拒绝。 */
  @Test
  void rejectsNegativeDecodeDuration() {
    assertEquals(
        0L,
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST, 0L)
            .decodeDurationMillis());
    assertEquals(
        1234L,
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST, 1234L)
            .decodeDurationMillis());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST, -1L));
  }
}
