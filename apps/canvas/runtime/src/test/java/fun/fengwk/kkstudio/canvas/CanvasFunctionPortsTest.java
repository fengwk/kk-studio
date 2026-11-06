package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** Canvas Function 内部端口（Run 异常、人工决议与 Blob 事实）的契约。 */
class CanvasFunctionPortsTest {

  private static final UUID BLOB_ID = new UUID(0L, 50);

  /** BlobFacts 冻结 blob identity 与媒体事实，blobId 为 null 即拒绝。 */
  @Test
  void blobFactsFreezeFactsAndRejectNullBlobId() {
    BlobFacts facts = new BlobFacts(BLOB_ID, "image/png", 1024L, 800L, 600L, null);

    assertEquals(BLOB_ID, facts.blobId());
    assertEquals("image/png", facts.mediaType());
    assertEquals(1024L, facts.sizeBytes());
    assertEquals(800L, facts.width());
    assertEquals(600L, facts.height());
    assertNull(facts.durationMs());

    assertThrows(
        NullPointerException.class, () -> new BlobFacts(null, "image/png", 1L, null, null, null));
  }

  /** 人工决议枚举是 UNKNOWN Run 的唯一处置集合。 */
  @Test
  void unknownResolutionEnumeratesAllOutcomes() {
    assertEquals(
        List.of(
            CanvasFunctionUnknownResolution.RESUME,
            CanvasFunctionUnknownResolution.FAILED,
            CanvasFunctionUnknownResolution.CANCELLED),
        List.of(CanvasFunctionUnknownResolution.values()));
  }
}
