package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandPlanner;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Accepted;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.CanvasUploadOwnership;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class CanvasCommandUploadAuthorizationTest {

  @Test
  void commandConsumesOnlyUploadOwnedByPrincipal() {
    UUID canvasId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    PostgresqlCanvasStore canvasStore = mock(PostgresqlCanvasStore.class);
    CanvasUploadOwnership uploadOwnership = mock(CanvasUploadOwnership.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<CanvasUploadOwnership> ownershipProvider = mock(ObjectProvider.class);
    when(ownershipProvider.getIfAvailable()).thenReturn(uploadOwnership);
    when(canvasStore.lockDocument(canvasId))
        .thenReturn(
            Optional.of(new CanvasDocument(canvasId, "canvas", 0L, Instant.EPOCH, Instant.EPOCH)));
    when(uploadOwnership.lockReady(uploadId, userId)).thenThrow(DomainException.notFound("upload"));
    CanvasCommandService service =
        new CanvasCommandService(
            canvasStore,
            mock(PostgresqlCanvasResourceRepository.class),
            mock(PostgresqlCanvasFunctionResourcePinRepository.class),
            mock(PostgresqlCanvasFunctionRunRepository.class),
            mock(CanvasResourceLifecycle.class),
            mock(CanvasCommandPlanner.class),
            ownershipProvider);

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

    verify(uploadOwnership).lockReady(eq(uploadId), eq(userId));
  }

  @Test
  void acceptedCommandTransfersOneUploadOwnerIntoEachCanvasResource() {
    UUID canvasId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    PostgresqlCanvasStore canvasStore = mock(PostgresqlCanvasStore.class);
    PostgresqlCanvasResourceRepository resourceRepository =
        mock(PostgresqlCanvasResourceRepository.class);
    PostgresqlCanvasFunctionResourcePinRepository pinRepository =
        mock(PostgresqlCanvasFunctionResourcePinRepository.class);
    PostgresqlCanvasFunctionRunRepository runRepository =
        mock(PostgresqlCanvasFunctionRunRepository.class);
    CanvasUploadOwnership uploadOwnership = mock(CanvasUploadOwnership.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<CanvasUploadOwnership> ownershipProvider = mock(ObjectProvider.class);
    when(ownershipProvider.getIfAvailable()).thenReturn(uploadOwnership);
    when(canvasStore.lockDocument(canvasId))
        .thenReturn(
            Optional.of(new CanvasDocument(canvasId, "canvas", 0L, Instant.EPOCH, Instant.EPOCH)));
    when(canvasStore.findCommandDedup(eq(canvasId), any())).thenReturn(Optional.empty());
    when(canvasStore.listNodes(canvasId)).thenReturn(List.of());
    when(canvasStore.listGroups(canvasId)).thenReturn(List.of());
    when(resourceRepository.findByCanvasId(canvasId)).thenReturn(List.of());
    when(runRepository.findByCanvasId(canvasId)).thenReturn(List.of());
    when(pinRepository.findByCanvas(canvasId)).thenReturn(List.of());
    when(canvasStore.advanceRevision(canvasId, 0L, 1L)).thenReturn(true);
    when(uploadOwnership.lockReady(uploadId, userId))
        .thenReturn(new CanvasUploadOwnership.ReadyUpload(blobId, "stored-name.png"));
    CanvasCommandService service =
        new CanvasCommandService(
            canvasStore,
            resourceRepository,
            pinRepository,
            runRepository,
            mock(CanvasResourceLifecycle.class),
            new CanvasCommandPlanner(Clock.systemUTC(), UUID::randomUUID),
            ownershipProvider);

    var result =
        service.applyCommandsFromUploads(
            canvasId,
            UUID.randomUUID(),
            List.of(
                new CanvasCommand.CreateNode(
                    nodeId,
                    "node",
                    new CanvasTransform(0, 0, 10, 10),
                    List.of(
                        new CanvasResourceInput.Blob("untrusted-name.png", uploadId),
                        new CanvasResourceInput.Blob("also-untrusted.png", uploadId)))),
            userId);

    assertEquals(1L, assertInstanceOf(Accepted.class, result).patch().revision());
    var resources = ArgumentCaptor.forClass(CanvasResource.class);
    verify(resourceRepository, times(2)).add(resources.capture());
    assertEquals(
        List.of("stored-name.png", "stored-name.png"),
        resources.getAllValues().stream().map(CanvasResource::name).toList());
    assertEquals(
        List.of(blobId, blobId),
        resources.getAllValues().stream().map(CanvasResource::blobId).toList());
    verify(uploadOwnership).lockReady(uploadId, userId);
    verify(uploadOwnership, times(2)).retain(blobId);
    verify(uploadOwnership).delete(uploadId, userId);
  }
}
