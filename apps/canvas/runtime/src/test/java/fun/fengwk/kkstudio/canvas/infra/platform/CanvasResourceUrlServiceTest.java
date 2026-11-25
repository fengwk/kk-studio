package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ContentDisposition;

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

    assertSame(original, service.originalUrl(PRINCIPAL, CANVAS, BLOB, null));
    assertSame(preview, service.previewUrl(PRINCIPAL, CANVAS, BLOB));
    verify(blobs).presignOriginalUrl(BLOB);
    verify(blobs).presignPreviewUrl(BLOB);
    verify(access, times(2)).require(PRINCIPAL, Kind.CANVAS, CANVAS);
  }

  /** 文件名保留 Unicode 与引号，且不会把路径分隔符交给浏览器。 */
  @Test
  void encodesAttachmentNameWithoutPathSeparators() {
    when(query.containsHeldBlob(CANVAS, BLOB)).thenReturn(true);
    service.originalUrl(PRINCIPAL, CANVAS, BLOB, "../资料\\\"图\".png");

    var disposition = ArgumentCaptor.forClass(ContentDisposition.class);
    verify(blobs).presignOriginalUrl(eq(BLOB), disposition.capture());
    var parsed = ContentDisposition.parse(disposition.getValue().toString());
    assertEquals("attachment", parsed.getType());
    assertEquals(".._资料_\"图\".png", parsed.getFilename());
  }

  /** Blob 未被画布持有时，不能签发附件 URL。 */
  @Test
  void rejectsBlobNotHeldByCanvas() {
    when(query.containsHeldBlob(CANVAS, BLOB)).thenReturn(false);
    DomainException rejected =
        assertThrows(
            DomainException.class, () -> service.originalUrl(PRINCIPAL, CANVAS, BLOB, "test.png"));
    assertEquals(DomainErrorCode.NOT_FOUND, rejected.code());
    verify(access).require(PRINCIPAL, Kind.CANVAS, CANVAS);
    verifyNoInteractions(blobs);
  }
}
