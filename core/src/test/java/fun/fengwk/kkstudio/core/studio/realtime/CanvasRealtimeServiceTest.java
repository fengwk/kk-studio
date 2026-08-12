package fun.fengwk.kkstudio.core.studio.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.studio.canvas.CanvasChanges;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Canvas realtime 只在事务提交后发布，并在 patch 缺口时回退权威快照。 */
class CanvasRealtimeServiceTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void publishAppendsOnlyAfterCommit() {
    CanvasPatchStore store = mock(CanvasPatchStore.class);
    CanvasRealtimeService service =
        new CanvasRealtimeService(store, mock(CanvasQueryService.class));
    CanvasPatch patch = patch(0L, 1L);
    TransactionSynchronizationManager.initSynchronization();

    service.publish(CANVAS, patch);

    verify(store, never()).append(CANVAS, patch);
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCommit();
    }
    verify(store).append(CANVAS, patch);
  }

  @Test
  void readChangesReturnsContinuousPatchesAndFallsBackOnGap() {
    CanvasPatchStore store = mock(CanvasPatchStore.class);
    CanvasQueryService query = mock(CanvasQueryService.class);
    CanvasSnapshot snapshot = snapshot(3L);
    when(query.findSnapshot(CANVAS)).thenReturn(Optional.of(snapshot));
    CanvasRealtimeService service = new CanvasRealtimeService(store, query);
    CanvasPatch second = patch(1L, 2L);
    CanvasPatch third = patch(2L, 3L);
    when(store.readAll(CANVAS)).thenReturn(List.of(second, third));

    CanvasChanges contiguous = service.readChanges(CANVAS, 1L);

    assertEquals(List.of(second, third), contiguous.patches());
    assertNull(contiguous.snapshot());

    when(store.readAll(CANVAS)).thenReturn(List.of(third));
    CanvasChanges gap = service.readChanges(CANVAS, 1L);
    assertEquals(List.of(), gap.patches());
    assertSame(snapshot, gap.snapshot());
  }

  @Test
  void readChangesReplaysVersionOneFromZeroAndFallsBackWhenItIsMissing() {
    CanvasPatchStore store = mock(CanvasPatchStore.class);
    CanvasQueryService query = mock(CanvasQueryService.class);
    CanvasSnapshot snapshot = snapshot(1L);
    CanvasPatch first = patch(0L, 1L);
    when(query.findSnapshot(CANVAS)).thenReturn(Optional.of(snapshot));
    when(store.readAll(CANVAS)).thenReturn(List.of(first));
    CanvasRealtimeService service = new CanvasRealtimeService(store, query);

    CanvasChanges contiguous = service.readChanges(CANVAS, 0L);

    assertEquals(List.of(first), contiguous.patches());
    assertNull(contiguous.snapshot());

    when(store.readAll(CANVAS)).thenReturn(List.of());
    CanvasChanges missing = service.readChanges(CANVAS, 0L);
    assertEquals(List.of(), missing.patches());
    assertSame(snapshot, missing.snapshot());
  }

  private static CanvasPatch patch(long baseVersion, long version) {
    return new CanvasPatch(baseVersion, version, List.of(), List.of(), List.of());
  }

  private static CanvasSnapshot snapshot(long version) {
    Instant now = Instant.parse("2026-08-12T00:00:00Z");
    return new CanvasSnapshot(
        new CanvasDocument(CANVAS, "canvas", version, null, now, now),
        List.of(),
        List.of(),
        List.of());
  }
}
