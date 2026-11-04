package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.util.UUID;

class CanvasBlobReleaserTest {

  private final StorageBlobManager manager = mock(StorageBlobManager.class);
  private final CanvasBlobReleaser releaser = new CanvasBlobReleaser(manager);

  /** 成功时恰好向 Storage 释放指定引用。 */
  @Test
  void releasesReference() {
    UUID id = UUID.randomUUID();
    when(manager.release(id)).thenReturn(true);

    releaser.release(id);

    verify(manager).release(id);
  }

  /** Storage 拒绝释放时必须抛出异常以触发事务回滚。 */
  @Test
  void rejectsFailedRelease() {
    UUID id = UUID.randomUUID();
    when(manager.release(id)).thenReturn(false);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> releaser.release(id));

    assertEquals("release canvas resource blob failed: " + id, error.getMessage());
  }

  /** Storage 异常不得被吞掉。 */
  @Test
  void propagatesStorageFailure() {
    UUID id = UUID.randomUUID();
    IllegalStateException failure = new IllegalStateException("storage failure");
    when(manager.release(id)).thenThrow(failure);

    assertSame(failure, assertThrows(IllegalStateException.class, () -> releaser.release(id)));
  }
}
