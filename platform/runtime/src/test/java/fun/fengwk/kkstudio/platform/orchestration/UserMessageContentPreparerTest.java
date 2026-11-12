package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.UUID;

class UserMessageContentPreparerTest {

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
            () -> ImageInputTier.P1080);
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
