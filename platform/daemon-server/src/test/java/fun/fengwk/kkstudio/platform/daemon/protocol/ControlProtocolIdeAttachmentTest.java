package fun.fengwk.kkstudio.platform.daemon.protocol;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** OPEN_ATTACHMENT 的 IDE 命令只允许带规范 UUID 和有界 UTF-8 文本。 */
class ControlProtocolIdeAttachmentTest {

  private static final String ENVIRONMENT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String VIEW_ID = "22222222-2222-4222-8222-222222222222";
  private static final String SESSION_ID = "33333333-3333-4333-8333-333333333333";
  private static final String DAEMON_ID = "44444444-4444-4444-8444-444444444444";
  private static final String RESOURCE_ID = "abcdefab-cdef-4abc-8def-abcdefabcdef";

  @Test
  void acceptsTextAtUtf8ByteLimitAndRejectsInvalidIdsOrContents() {
    ControlProtocolCodec.encodeBinary(envelope("x".repeat(64 * 1024), RESOURCE_ID));

    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(envelope("x".repeat(64 * 1024 + 1), RESOURCE_ID)));
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(envelope("\uD800", RESOURCE_ID)));
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(envelope("text", RESOURCE_ID.toUpperCase())));
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(envelope("text\u0000", RESOURCE_ID)));
  }

  @Test
  void rejectsAttachmentFieldsOnOtherActionsAndExtraAttachmentActionFields() {
    IdeCommand focus =
        IdeCommand.newBuilder()
            .setAction(IdeAction.FOCUS_TERMINAL)
            .setViewId(VIEW_ID)
            .setBrowserSessionId(SESSION_ID)
            .setBindingEpoch(1)
            .setExpectedDaemonInstanceId(DAEMON_ID)
            .setAttachmentId(RESOURCE_ID)
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(envelope(focus)));

    IdeCommand attachment = command("text", RESOURCE_ID).toBuilder().setDirectory("/tmp").build();
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(envelope(attachment)));
  }

  private static Envelope envelope(String text, String resourceId) {
    return envelope(command(text, resourceId));
  }

  private static IdeCommand command(String text, String resourceId) {
    return IdeCommand.newBuilder()
        .setAction(IdeAction.OPEN_ATTACHMENT)
        .setViewId(VIEW_ID)
        .setBrowserSessionId(SESSION_ID)
        .setBindingEpoch(1)
        .setExpectedDaemonInstanceId(DAEMON_ID)
        .setAttachmentId(resourceId)
        .setAttachmentText(text)
        .build();
  }

  private static Envelope envelope(IdeCommand command) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.IDE_COMMAND)
        .setEnvironmentId(ENVIRONMENT_ID)
        .setInvocationId("66666666-6666-4666-8666-666666666666")
        .setIdeCommand(command)
        .build();
  }
}
