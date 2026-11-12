package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

/** Session authorization precedes resource ownership checks, then streams only ACTIVE content. */
class HarnessResourceControllerTest {

  private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID RESOURCE_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

  private HarnessOwnerQueryService ownerQueryService;
  private SessionBlobRefManager refManager;
  private StorageBlobRepository blobRepository;
  private StorageBlobContentService contentService;
  private HarnessResourceController controller;

  @BeforeEach
  void setUp() {
    ownerQueryService = Mockito.mock(HarnessOwnerQueryService.class);
    refManager = Mockito.mock(SessionBlobRefManager.class);
    blobRepository = Mockito.mock(StorageBlobRepository.class);
    contentService = Mockito.mock(StorageBlobContentService.class);
    controller =
        new HarnessResourceController(
            ownerQueryService, refManager, blobRepository, contentService);
  }

  @Test
  void rejectsUnauthorizedSessionBeforeCheckingResourceOwnership() {
    doThrow(DomainException.notFound("session"))
        .when(ownerQueryService)
        .requireOwnedSession(SESSION_ID);

    assertThrows(
        DomainException.class, () -> controller.getResource(SESSION_ID, RESOURCE_ID, false));

    verifyNoInteractions(refManager, blobRepository, contentService);
  }

  @Test
  void rejectsResourceNotHeldBySessionBeforeLoadingBlob() {
    when(refManager.contains(SESSION_ID, RESOURCE_ID)).thenReturn(false);

    assertThrows(
        DomainException.class, () -> controller.getResource(SESSION_ID, RESOURCE_ID, false));

    InOrder order = inOrder(ownerQueryService, refManager);
    order.verify(ownerQueryService).requireOwnedSession(SESSION_ID);
    order.verify(refManager).contains(SESSION_ID, RESOURCE_ID);
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
        controller.getResource(SESSION_ID, RESOURCE_ID, false);
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

    InOrder order = inOrder(ownerQueryService, refManager, blobRepository);
    order.verify(ownerQueryService).requireOwnedSession(SESSION_ID);
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
          controller.getResource(SESSION_ID, RESOURCE_ID, false);
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
        DomainException.class, () -> controller.getResource(SESSION_ID, RESOURCE_ID, true));
    verify(contentService, never()).withBlobStream(eq(RESOURCE_ID), any(ReadDeadline.class), any());

    deletingBlob.setState(StorageBlobState.ACTIVE);
    ResponseEntity<StreamingResponseBody> response =
        controller.getResource(SESSION_ID, RESOURCE_ID, true);

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
