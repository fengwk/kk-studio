package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class CanvasResourceUrlServiceTest {

  private static final UUID CANVAS = new UUID(0, 1);
  private static final UUID RESOURCE = new UUID(0, 2);
  private static final UUID BLOB = new UUID(0, 3);
  private final CanvasQueryService query = mock(CanvasQueryService.class);
  private final StorageBlobManager blobs = mock(StorageBlobManager.class);
  private final CanvasResourceUrlService service = new CanvasResourceUrlService(query, blobs);

  /** 原件和预览分别使用正确签名方法，并原样返回签名结果。 */
  @Test
  void signsOriginalAndPreview() {
    CanvasResource resource =
        new CanvasResource(RESOURCE, CANVAS, new UUID(0, 4), 0, BLOB, "a", null, Instant.EPOCH);
    StoragePresignedUrlDTO original = mock(StoragePresignedUrlDTO.class);
    StoragePresignedUrlDTO preview = mock(StoragePresignedUrlDTO.class);
    when(query.findResource(CANVAS, RESOURCE)).thenReturn(Optional.of(resource));
    when(blobs.presignOriginalUrl(BLOB)).thenReturn(original);
    when(blobs.presignPreviewUrl(BLOB)).thenReturn(preview);

    assertSame(original, service.presign(CANVAS, RESOURCE, true));
    assertSame(preview, service.presign(CANVAS, RESOURCE, false));
    verify(blobs).presignOriginalUrl(BLOB);
    verify(blobs).presignPreviewUrl(BLOB);
    verify(query, never()).findDocument(any());
  }

  /** 区分不存在的画布和资源，并且不触发签名。 */
  @Test
  void distinguishesMissingCanvasAndResource() {
    when(query.findResource(CANVAS, RESOURCE)).thenReturn(Optional.empty());
    when(query.findDocument(CANVAS))
        .thenReturn(Optional.empty(), Optional.of(mock(CanvasDocument.class)));

    DomainException missingCanvas =
        assertThrows(DomainException.class, () -> service.presign(CANVAS, RESOURCE, true));
    assertEquals(DomainErrorCode.NOT_FOUND, missingCanvas.code());
    assertEquals("canvas", missingCanvas.resource());
    assertEquals("unknown canvas: " + CANVAS, missingCanvas.getMessage());
    DomainException missingResource =
        assertThrows(DomainException.class, () -> service.presign(CANVAS, RESOURCE, false));
    assertEquals(DomainErrorCode.NOT_FOUND, missingResource.code());
    assertEquals("canvas_resource", missingResource.resource());
    assertEquals("unknown resource: " + RESOURCE, missingResource.getMessage());
    verify(blobs, never()).presignOriginalUrl(any());
    verify(blobs, never()).presignPreviewUrl(any());
  }

  /** 内联文本没有 Blob，两个 URL 均不可签发。 */
  @Test
  void rejectsText() {
    CanvasResource text =
        new CanvasResource(
            RESOURCE, CANVAS, new UUID(0, 4), 0, null, "note", "text", Instant.EPOCH);
    when(query.findResource(CANVAS, RESOURCE)).thenReturn(Optional.of(text));
    for (boolean original : new boolean[] {true, false}) {
      DomainException rejected =
          assertThrows(DomainException.class, () -> service.presign(CANVAS, RESOURCE, original));
      assertEquals(DomainErrorCode.VALIDATION, rejected.code());
      assertEquals("TEXT resource has no blob content", rejected.getMessage());
    }
    verify(blobs, never()).presignOriginalUrl(any());
    verify(blobs, never()).presignPreviewUrl(any());
  }
}
