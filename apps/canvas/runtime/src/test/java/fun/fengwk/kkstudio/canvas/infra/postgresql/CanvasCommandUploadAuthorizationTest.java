package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandPlanner;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanvasCommandUploadAuthorizationTest {

  @Test
  void commandConsumesOnlyUploadOwnedByPrincipal() {
    UUID canvasId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    PostgresqlCanvasStore canvasStore = mock(PostgresqlCanvasStore.class);
    StorageUploadService uploadService = mock(StorageUploadService.class);
    when(canvasStore.lockDocument(canvasId))
        .thenReturn(
            Optional.of(
                new CanvasDocument(canvasId, "canvas", 0L, Instant.EPOCH, Instant.EPOCH)));
    when(uploadService.lockReady(uploadId, userId)).thenThrow(DomainException.notFound("upload"));
    CanvasCommandService service =
        new CanvasCommandService(
            canvasStore,
            mock(PostgresqlCanvasResourceRepository.class),
            mock(PostgresqlCanvasFunctionResourcePinRepository.class),
            mock(PostgresqlCanvasFunctionRunRepository.class),
            mock(CanvasResourceLifecycle.class),
            mock(CanvasCommandPlanner.class),
            uploadService,
            mock(StorageBlobManager.class));

    assertThrows(
        DomainException.class,
        () ->
            service.applyCommandsFromUploads(
                canvasId,
                UUID.randomUUID(),
                List.of(
                    new CanvasCommand.CreateNode(
                        nodeId,
                        "node",
                        new CanvasTransform(0, 0, 10, 10),
                        List.of(new CanvasResourceInput.Blob("image.png", uploadId)))),
                userId));

    verify(uploadService).lockReady(eq(uploadId), eq(userId));
  }
}
