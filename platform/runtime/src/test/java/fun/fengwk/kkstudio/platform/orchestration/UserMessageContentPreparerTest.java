package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.UUID;

class UserMessageContentPreparerTest {

  @Test
  void truncatesOnlyTheIncomingMessageToTheStricterImageLimitAndAddsModelVisibleNote() {
    UUID sessionId = UUID.randomUUID();
    UUID firstImage = UUID.randomUUID();
    UUID secondImage = UUID.randomUUID();
    UUID thirdImage = UUID.randomUUID();
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    for (UUID blobId : List.of(firstImage, secondImage, thirdImage)) {
      when(refManager.contains(sessionId, blobId)).thenReturn(true);
      StorageBlob blob = mock(StorageBlob.class);
      when(blob.getState()).thenReturn(StorageBlobState.ACTIVE);
      when(blob.getMediaType()).thenReturn("image/png");
      when(blobManager.getBlob(blobId)).thenReturn(blob);
    }
    UserMessageContentPreparer preparer =
        new UserMessageContentPreparer(
            refManager,
            blobManager,
            (ignoredSessionId, ignoredUploadId) -> null,
            () -> ImageInputTier.P720,
            () -> 2);
    NewThreadCommand command =
        new NewThreadCommand(
            new UserMessageCommandPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(
                        new TextMessageContent("Inspect these."),
                        ResourceMessageContent.media(
                            firstImage, "first.png", null, ImageInputTier.P720),
                        ResourceMessageContent.media(
                            secondImage, "second.png", null, ImageInputTier.P720),
                        ResourceMessageContent.media(
                            thirdImage, "third.png", null, ImageInputTier.P720)))),
            UUID.randomUUID());

    NewThreadCommand prepared = preparer.prepare(sessionId, List.of(command), 1).getFirst();
    List<AgentMessageContent> contents =
        ((UserMessageCommandPayload) prepared.payload()).message().contents();

    assertEquals(3, contents.size());
    assertInstanceOf(TextMessageContent.class, contents.get(0));
    assertEquals(firstImage, ((ResourceMessageContent) contents.get(1)).blobId());
    TextMessageContent note = assertInstanceOf(TextMessageContent.class, contents.get(2));
    assertTrue(note.text().contains("kept the first 1 images and omitted 2 additional image(s)"));
  }

  @Test
  void userSettingCapsImagesWhenProtocolLimitIsHigherOrUnknown() {
    UUID sessionId = UUID.randomUUID();
    UUID firstImage = UUID.randomUUID();
    UUID secondImage = UUID.randomUUID();
    UUID thirdImage = UUID.randomUUID();
    SessionBlobRefManager refManager = mock(SessionBlobRefManager.class);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    for (UUID blobId : List.of(firstImage, secondImage, thirdImage)) {
      when(refManager.contains(sessionId, blobId)).thenReturn(true);
      StorageBlob blob = mock(StorageBlob.class);
      when(blob.getState()).thenReturn(StorageBlobState.ACTIVE);
      when(blob.getMediaType()).thenReturn("image/png");
      when(blobManager.getBlob(blobId)).thenReturn(blob);
    }
    UserMessageContentPreparer preparer =
        new UserMessageContentPreparer(
            refManager,
            blobManager,
            (ignoredSessionId, ignoredUploadId) -> null,
            () -> ImageInputTier.P720,
            () -> 2);
    NewThreadCommand command =
        new NewThreadCommand(
            new UserMessageCommandPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(
                        ResourceMessageContent.media(
                            firstImage, "first.png", null, ImageInputTier.P720),
                        ResourceMessageContent.media(
                            secondImage, "second.png", null, ImageInputTier.P720),
                        ResourceMessageContent.media(
                            thirdImage, "third.png", null, ImageInputTier.P720)))),
            UUID.randomUUID());

    List<AgentMessageContent> contents =
        ((UserMessageCommandPayload)
                preparer.prepare(sessionId, List.of(command), null).getFirst().payload())
            .message()
            .contents();

    assertEquals(3, contents.size());
    assertTrue(((TextMessageContent) contents.getLast()).text().contains("omitted 1 additional"));
  }

  @Test
  void copiesAuthoritativeUploadDimensionsIntoDurableResource() {
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    StorageBlob blob = mock(StorageBlob.class);
    when(blob.getState()).thenReturn(StorageBlobState.ACTIVE);
    when(blob.getMediaType()).thenReturn("image/png");
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    UserMessageContentPreparer preparer =
        new UserMessageContentPreparer(
            mock(SessionBlobRefManager.class),
            blobManager,
            (requestedSessionId, requestedUploadId) -> {
              assertEquals(sessionId, requestedSessionId);
              assertEquals(uploadId, requestedUploadId);
              return new UserMessageContentPreparer.ReadyAttachment(
                  blobId, "scan.png", 4032, 3024, 1280, 960);
            },
            () -> ImageInputTier.P1080,
            () -> 20);
    NewThreadCommand command =
        new NewThreadCommand(
            new UserMessageCommandPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(new AttachmentMessageContent(uploadId, ImageInputTier.P720)))),
            UUID.randomUUID());

    NewThreadCommand prepared = preparer.prepare(sessionId, List.of(command)).get(0);

    ResourceMessageContent resource =
        assertInstanceOf(
            ResourceMessageContent.class,
            ((UserMessageCommandPayload) prepared.payload()).message().contents().get(0));
    assertEquals(blobId, resource.blobId());
    assertEquals(4032, resource.originalWidth());
    assertEquals(3024, resource.originalHeight());
    assertEquals(1280, resource.displayWidth());
    assertEquals(960, resource.displayHeight());
    assertEquals(ImageInputTier.P720, resource.imageTier());
  }
}
