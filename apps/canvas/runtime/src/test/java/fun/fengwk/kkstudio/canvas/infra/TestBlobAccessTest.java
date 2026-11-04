package fun.fengwk.kkstudio.canvas.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.BlobFacts;

import java.util.UUID;

class TestBlobAccessTest {

  /** 替身按 blob ID 查找最新事实，且拒绝不属于 foundation 测试的原件 I/O。 */
  @Test
  void exposesStoredFactsAndRejectsOriginalIo() {
    TestBlobAccess fake = new TestBlobAccess();
    UUID id = UUID.randomUUID();
    BlobFacts first = new BlobFacts(id, "image/png", 3L, 1L, 1L, null);
    BlobFacts updated = new BlobFacts(id, "image/png", 4L, 2L, 2L, null);

    assertTrue(fake.access().findFacts(id).isEmpty());
    fake.put(first);
    assertEquals(first, fake.access().findFacts(id).orElseThrow());
    fake.put(updated);
    assertEquals(updated, fake.access().findFacts(id).orElseThrow());
    assertThrows(UnsupportedOperationException.class, () -> fake.access().openOriginal(id, 4L));
    assertThrows(UnsupportedOperationException.class, () -> fake.access().originalUrl(id, 60L));
  }
}
