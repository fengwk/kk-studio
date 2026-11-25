package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.util.UUID;

class CanvasResourceUrlServiceTest {

  private static final UUID CANVAS = new UUID(0, 1);
  private static final UUID USER = new UUID(0, 2);
  private static final UUID BLOB = new UUID(0, 3);
  private static final StudioPrincipal PRINCIPAL =
      new StudioPrincipal(USER, "user", StudioRole.USER);
  private final CanvasQueryService query = mock(CanvasQueryService.class);
  private final StorageBlobManager blobs = mock(StorageBlobManager.class);
  private final ResourceAccess access = mock(ResourceAccess.class);
  private final CanvasResourceUrlService service =
      new CanvasResourceUrlService(query, blobs, access);

  /** 原件和预览分别使用正确签名方法，并原样返回签名结果。 */
  @Test
  void signsOriginalAndPreview() {
    StoragePresignedUrlDTO original = mock(StoragePresignedUrlDTO.class);
    StoragePresignedUrlDTO preview = mock(StoragePresignedUrlDTO.class);
    when(query.containsHeldBlob(CANVAS, BLOB)).thenReturn(true);
    when(blobs.presignOriginalUrl(BLOB)).thenReturn(original);
    when(blobs.presignPreviewUrl(BLOB)).thenReturn(preview);

    assertSame(original, service.presign(PRINCIPAL, CANVAS, BLOB, true));
    assertSame(preview, service.presign(PRINCIPAL, CANVAS, BLOB, false));
    verify(blobs).presignOriginalUrl(BLOB);
    verify(blobs).presignPreviewUrl(BLOB);
    verify(access, times(2)).require(PRINCIPAL, Kind.CANVAS, CANVAS);
  }

  /** Blob 未被画布持有时不可签发 URL。 */
  @Test
  void rejectsBlobNotHeldByCanvas() {
    when(query.containsHeldBlob(CANVAS, BLOB)).thenReturn(false);
    DomainException rejected =
        assertThrows(DomainException.class, () -> service.presign(PRINCIPAL, CANVAS, BLOB, true));
    assertEquals(DomainErrorCode.NOT_FOUND, rejected.code());
    verify(access).require(PRINCIPAL, Kind.CANVAS, CANVAS);
    verify(blobs, never()).presignOriginalUrl(any());
    verify(blobs, never()).presignPreviewUrl(any());
  }
}
