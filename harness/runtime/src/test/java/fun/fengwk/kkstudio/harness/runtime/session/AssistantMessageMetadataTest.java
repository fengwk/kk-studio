package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;

/** AssistantMessageMetadata 只承载 stopReason/usage 与可空非负的流式生成计时。 */
class AssistantMessageMetadataTest {

  private static final ModelUsage USAGE = new ModelUsage(1, 1, 0, 0, 0, 0, 2);

  /** 两参构造不携带计时：decodeDurationMillis 归一化为 null（无可信流计时）。 */
  @Test
  void constructorWithoutDurationLeavesDecodeDurationAbsent() {
    AssistantMessageMetadata metadata =
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE);

    assertNull(metadata.decodeDurationMillis());
    assertEquals(
        metadata, new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, (Long) null));
  }

  /** 显式计时必须非负：0 与正值合法，负值被拒绝。 */
  @Test
  void rejectsNegativeDecodeDuration() {
    assertEquals(
        0L,
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, 0L)
            .decodeDurationMillis());
    assertEquals(
        1234L,
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, 1234L)
            .decodeDurationMillis());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, -1L));
  }
}
