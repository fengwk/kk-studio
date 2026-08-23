package fun.fengwk.kkstudio.platform.canvas.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Platform 生命周期实现必须在 pin/owner 行收敛后恰好释放对应全局 Blob 引用。 */
class PlatformCanvasResourceLifecycleTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000005");

  @Test
  void releaseRunPinsCollectsOnlyTheLastUnownedReference() {
    CanvasResourceRepository resources = mock(CanvasResourceRepository.class);
    CanvasFunctionResourcePinRepository pins = mock(CanvasFunctionResourcePinRepository.class);
    StorageBlobManager blobs = mock(StorageBlobManager.class);
    CanvasResource resource = resource(null, null);
    CanvasFunctionResourcePin pin =
        new CanvasFunctionResourcePin(
            CANVAS, NODE, REQUEST, RESOURCE, CanvasFunctionResourcePin.Role.INPUT);
    when(pins.findByRun(CANVAS, NODE, REQUEST)).thenReturn(List.of(pin));
    when(pins.countByResource(CANVAS, RESOURCE)).thenReturn(0);
    when(resources.findByIdForUpdate(CANVAS, RESOURCE)).thenReturn(Optional.of(resource));
    when(resources.delete(CANVAS, RESOURCE)).thenReturn(true);
    when(blobs.release(BLOB)).thenReturn(true);
    PlatformCanvasResourceLifecycle lifecycle =
        new PlatformCanvasResourceLifecycle(resources, pins, provider(blobs));

    lifecycle.releaseRunPins(CANVAS, NODE, REQUEST);

    verify(pins).deleteByRun(CANVAS, NODE, REQUEST);
    verify(resources).delete(CANVAS, RESOURCE);
    verify(blobs).release(BLOB);
  }

  @Test
  void pinnedOwnedResourceIsDetachedWithoutReleasingBlob() {
    CanvasResourceRepository resources = mock(CanvasResourceRepository.class);
    CanvasFunctionResourcePinRepository pins = mock(CanvasFunctionResourcePinRepository.class);
    StorageBlobManager blobs = mock(StorageBlobManager.class);
    CanvasResource resource = resource(NODE, 0);
    when(resources.findByOwnerNode(CANVAS, NODE)).thenReturn(List.of(resource));
    when(pins.countByResource(CANVAS, RESOURCE)).thenReturn(1);
    when(resources.detachOwner(CANVAS, RESOURCE, NODE)).thenReturn(true);
    PlatformCanvasResourceLifecycle lifecycle =
        new PlatformCanvasResourceLifecycle(resources, pins, provider(blobs));

    lifecycle.deleteOwnedResources(CANVAS, NODE);

    verify(resources).detachOwner(CANVAS, RESOURCE, NODE);
    verify(resources, never()).delete(CANVAS, RESOURCE);
    verify(blobs, never()).release(BLOB);
  }

  @Test
  void missingBlobManagerKeepsStableFailureAfterResourceDelete() {
    CanvasResourceRepository resources = mock(CanvasResourceRepository.class);
    CanvasFunctionResourcePinRepository pins = mock(CanvasFunctionResourcePinRepository.class);
    when(resources.findByCanvasId(CANVAS)).thenReturn(List.of(resource(null, null)));
    when(resources.delete(CANVAS, RESOURCE)).thenReturn(true);
    PlatformCanvasResourceLifecycle lifecycle =
        new PlatformCanvasResourceLifecycle(resources, pins, provider(null));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> lifecycle.deleteCanvasResources(CANVAS));

    assertEquals("global blob storage is unavailable", error.getMessage());
  }

  private static CanvasResource resource(UUID ownerNodeId, Integer resourceIndex) {
    return new CanvasResource(
        RESOURCE, CANVAS, ownerNodeId, resourceIndex, BLOB, "resource.png", null, Instant.EPOCH);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
