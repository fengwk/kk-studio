package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

/** Streams only ACTIVE content held by the requested session. */
class HarnessResourceControllerTest {

  private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID RESOURCE_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final StudioPrincipal PRINCIPAL =
      new StudioPrincipal(new UUID(0, 1), "user", StudioRole.USER);

  private SessionBlobRefManager refManager;
  private StorageBlobRepository blobRepository;
  private StorageBlobContentService contentService;
  private StorageBlobManager blobManager;
  private ResourceAccess resourceAccess;
  private HarnessResourceController controller;

  @BeforeEach
  void setUp() {
    refManager = Mockito.mock(SessionBlobRefManager.class);
    blobRepository = Mockito.mock(StorageBlobRepository.class);
    contentService = Mockito.mock(StorageBlobContentService.class);
    blobManager = Mockito.mock(StorageBlobManager.class);
    resourceAccess = Mockito.mock(ResourceAccess.class);
    controller =
        new HarnessResourceController(
            refManager, blobRepository, contentService, blobManager, resourceAccess);
  }

  @Test
  void signsOnlyBlobsHeldByAnAuthorizedSession() {
    StoragePresignedUrlDTO original = Mockito.mock(StoragePresignedUrlDTO.class);
    StoragePresignedUrlDTO preview = Mockito.mock(StoragePresignedUrlDTO.class);
    when(refManager.contains(SESSION_ID, RESOURCE_ID)).thenReturn(true);
    when(blobManager.presignOriginalUrl(RESOURCE_ID)).thenReturn(original);
    when(blobManager.presignPreviewUrl(RESOURCE_ID)).thenReturn(preview);

    assertEquals(original, controller.downloadUrl(SESSION_ID, RESOURCE_ID, PRINCIPAL));
    assertEquals(preview, controller.previewUrl(SESSION_ID, RESOURCE_ID, PRINCIPAL));
    verify(resourceAccess, Mockito.times(2)).require(PRINCIPAL, Kind.SESSION, SESSION_ID);
    verify(refManager, Mockito.times(2)).contains(SESSION_ID, RESOURCE_ID);
  }

  @Test
  void rejectsUnheldBlobBeforeSigning() {
    when(refManager.contains(SESSION_ID, RESOURCE_ID)).thenReturn(false);
    assertThrows(
        DomainException.class, () -> controller.downloadUrl(SESSION_ID, RESOURCE_ID, PRINCIPAL));
    verify(resourceAccess).require(PRINCIPAL, Kind.SESSION, SESSION_ID);
    verify(blobManager, never()).presignOriginalUrl(any());
  }

  @Test
  void rejectsResourceNotHeldBySessionBeforeLoadingBlob() {
    when(refManager.contains(SESSION_ID, RESOURCE_ID)).thenReturn(false);

    assertThrows(
        DomainException.class,
        () -> controller.getResource(SESSION_ID, RESOURCE_ID, false, PRINCIPAL));

    verify(refManager).contains(SESSION_ID, RESOURCE_ID);
    verifyNoInteractions(blobRepository, contentService);
  }

  @Test
  void streamsActiveSessionResourceWithInlineSecurityHeaders() throws Exception {
    when(refManager.contains(SESSION_ID, RESOURCE_ID)).thenReturn(true);
    when(blobRepository.getById(RESOURCE_ID)).thenReturn(activeBlob());
    doAnswer(
            invocation -> {
              Function<InputStream, Object> consumer = invocation.getArgument(2);
              return consumer.apply(
                  new ByteArrayInputStream("resource bytes".getBytes(StandardCharsets.UTF_8)));
            })
        .when(contentService)
        .withBlobStream(eq(RESOURCE_ID), any(ReadDeadline.class), any());

    ResponseEntity<StreamingResponseBody> response =
        controller.getResource(SESSION_ID, RESOURCE_ID, false, PRINCIPAL);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
    assertEquals(
        ContentDisposition.inline().filename("resource", StandardCharsets.UTF_8).build(),
        response.getHeaders().getContentDisposition());
    assertEquals("private, no-store", response.getHeaders().getCacheControl());
    assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    assertEquals(
        "default-src 'none'; sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
    assertEquals("resource bytes", output.toString(StandardCharsets.UTF_8));

    InOrder order = inOrder(refManager, blobRepository);
    order.verify(refManager).contains(SESSION_ID, RESOURCE_ID);
    order.verify(blobRepository).getById(RESOURCE_ID);
    verify(contentService).withBlobStream(eq(RESOURCE_ID), any(ReadDeadline.class), any());
  }

  @Test
  void fallsBackToBinaryMediaTypeWhenBlobMediaTypeIsMissingOrInvalid() {
    when(refManager.contains(SESSION_ID, RESOURCE_ID)).thenReturn(true);
    StorageBlob blob = activeBlob();
    when(blobRepository.getById(RESOURCE_ID)).thenReturn(blob);

    for (String mediaType : new String[] {null, "", "not a media type"}) {
      blob.setMediaType(mediaType);
      ResponseEntity<StreamingResponseBody> response =
          controller.getResource(SESSION_ID, RESOURCE_ID, false, PRINCIPAL);
      assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    }
  }

  @Test
  void downloadsAsAttachmentAndRejectsNonActiveBlobs() {
    when(refManager.contains(SESSION_ID, RESOURCE_ID)).thenReturn(true);
    StorageBlob deletingBlob = activeBlob();
    deletingBlob.setState(StorageBlobState.DELETING);
    when(blobRepository.getById(RESOURCE_ID)).thenReturn(deletingBlob);

    assertThrows(
        DomainException.class,
        () -> controller.getResource(SESSION_ID, RESOURCE_ID, true, PRINCIPAL));
    verify(contentService, never()).withBlobStream(eq(RESOURCE_ID), any(ReadDeadline.class), any());

    deletingBlob.setState(StorageBlobState.ACTIVE);
    ResponseEntity<StreamingResponseBody> response =
        controller.getResource(SESSION_ID, RESOURCE_ID, true, PRINCIPAL);

    assertEquals(
        ContentDisposition.attachment().filename("resource", StandardCharsets.UTF_8).build(),
        response.getHeaders().getContentDisposition());
  }

  private static StorageBlob activeBlob() {
    StorageBlob blob = new StorageBlob();
    blob.setId(RESOURCE_ID);
    blob.setMediaType("text/plain");
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }
}
