package fun.fengwk.kkstudio.ide.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

import java.util.List;
import java.util.UUID;

/**
 * 意图：IDE 控制命令/回执的 wire 边界必须严格 —— 固定字段、拒绝未知字段/重复键/尾随内容，各 action 只允许其语义对应的字段组合； UUID/epoch
 * 必须是规范形式，失败回执只携带安全 code/message。
 */
class DaemonIdeCodecTest {

  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String DOCUMENT = "66666666-7777-8888-9999-aaaaaaaaaaaa";
  private static final String EVENT_NODE = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
  private static final String CONNECTION = "12345678-1234-1234-1234-123456789abc";
  private static final String CLAIM = "87654321-4321-4321-4321-cba987654321";

  private final DaemonIdeCodec codec = new DaemonIdeCodec();

  @Test
  void roundTripsOpenViewCreateAndResume() {
    IdeCommand create =
        IdeCommand.openView(
            VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, IdeOpenMode.CREATE, "/home/user");
    assertEquals(create, codec.decodeCommand(codec.encodeCommand(create)));
    IdeCommand resume =
        IdeCommand.openView(
            VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, IdeOpenMode.RESUME, null);
    assertEquals(resume, codec.decodeCommand(codec.encodeCommand(resume)));
  }

  @Test
  void roundTripsBoundIdentityCommands() {
    for (IdeCommand command :
        List.of(
            IdeCommand.changeDirectory(VIEW, DOCUMENT, 3L, "/tmp"),
            IdeCommand.focusTerminal(VIEW, DOCUMENT, 3L),
            IdeCommand.detachView(VIEW, DOCUMENT, 3L),
            IdeCommand.closeView(VIEW, DOCUMENT, 3L, true),
            IdeCommand.closeView(VIEW, DOCUMENT, 3L, false),
            IdeCommand.listViews())) {
      assertEquals(command, codec.decodeCommand(codec.encodeCommand(command)));
    }
  }

  @Test
  void encodesFrozenOpenViewShape() {
    IdeCommand command =
        IdeCommand.openView(
            VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, IdeOpenMode.CREATE, null);
    assertEquals(
        "{\"action\":\"OPEN_VIEW\",\"viewId\":\""
            + VIEW
            + "\",\"documentId\":\""
            + DOCUMENT
            + "\",\"eventNodeId\":\""
            + EVENT_NODE
            + "\",\"connectionId\":\""
            + CONNECTION
            + "\",\"claimId\":\""
            + CLAIM
            + "\",\"mode\":\"create\"}",
        codec.encodeCommand(command));
  }

  @Test
  void roundTripsOpenAndListReplies() {
    UUID viewId = UUID.fromString(VIEW);
    UUID documentId = UUID.fromString(DOCUMENT);
    UUID eventNodeId = UUID.fromString(EVENT_NODE);
    UUID connectionId = UUID.fromString(CONNECTION);
    UUID claimId = UUID.fromString(CLAIM);
    IdeViewSummary summary =
        new IdeViewSummary(
            viewId,
            documentId,
            eventNodeId,
            connectionId,
            claimId,
            9L,
            "/home/user",
            "/daemon/views/" + VIEW,
            IdeViewState.OPEN);
    IdeReply open =
        new IdeReply(
            true,
            viewId,
            documentId,
            eventNodeId,
            connectionId,
            claimId,
            9L,
            "/home/user",
            "/daemon/views/" + VIEW,
            null,
            null,
            null);
    assertEquals(open, codec.decodeReply(codec.encodeReply(open)));
    IdeReply list =
        new IdeReply(
            true, null, null, null, null, null, null, null, null, List.of(summary), null, null);
    assertEquals(list, codec.decodeReply(codec.encodeReply(list)));
    IdeReply failure =
        new IdeReply(
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "VIEW_GONE",
            "view is gone");
    assertEquals(failure, codec.decodeReply(codec.encodeReply(failure)));
  }

  @Test
  void decodesDirectoryAcknowledgementAndRejectsMalformedDirectory() {
    // The value on the wire is the daemon-confirmed directory, not the browser request.
    String reply = "{\"ok\":true,\"viewId\":\"" + VIEW + "\",\"directory\":\"/canonical/project\"}";
    IdeReply decoded = codec.decodeReply(reply);
    assertEquals(UUID.fromString(VIEW), decoded.viewId());
    assertEquals("/canonical/project", decoded.directory());
    assertEquals(decoded, codec.decodeReply(codec.encodeReply(decoded)));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeReply("{\"ok\":true,\"viewId\":\"" + VIEW + "\",\"directory\":42}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeReply("{\"ok\":true,\"viewId\":\"" + VIEW + "\",\"directory\":\"\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeReply(
                "{\"ok\":true,\"viewId\":\"" + VIEW + "\",\"directory\":\"/bad\\npath\"}"));
  }

  @Test
  void rejectsUnknownFieldsDuplicateKeysAndTrailingContent() {
    String base =
        "{\"action\":\"OPEN_VIEW\",\"viewId\":\""
            + VIEW
            + "\",\"documentId\":\""
            + DOCUMENT
            + "\",\"eventNodeId\":\""
            + EVENT_NODE
            + "\",\"connectionId\":\""
            + CONNECTION
            + "\",\"claimId\":\""
            + CLAIM
            + "\",\"mode\":\"create\"";
    assertThrows(DaemonProtocolException.class, () -> codec.decodeCommand(base + ",\"extra\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeCommand(
                base.replace("\"mode\":\"create\"", "\"mode\":\"create\",\"mode\":\"resume\"")
                    + "}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeCommand(base + "} trailing"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeReply("{\"ok\":true,\"views\":[],\"extra\":1}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeReply("{\"ok\":\"yes\"}"));
  }

  @Test
  void enforcesActionSpecificFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_VIEW,
                VIEW,
                DOCUMENT,
                EVENT_NODE,
                CONNECTION,
                null,
                null,
                IdeOpenMode.CREATE,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.CHANGE_DIRECTORY,
                VIEW,
                DOCUMENT,
                null,
                null,
                null,
                null,
                null,
                "/tmp",
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.CLOSE_VIEW, VIEW, DOCUMENT, null, null, null, 1L, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.LIST_VIEWS, VIEW, null, null, null, null, null, null, null, null));
  }

  @Test
  void rejectsNonCanonicalIdentityAndEpoch() {
    for (String viewId : List.of("", "123", "../escape", "AAAAAAAA-2222-3333-4444-555555555555")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new IdeCommand(
                  IdeAction.FOCUS_TERMINAL,
                  viewId,
                  DOCUMENT,
                  null,
                  null,
                  null,
                  1L,
                  null,
                  null,
                  null));
    }
    for (String epoch : List.of("", "0", "01", "-1", "9223372036854775808", "1.0")) {
      assertThrows(IllegalArgumentException.class, () -> IdeCommand.parseBindingEpoch(epoch));
    }
  }

  @Test
  void rejectsInvalidModeAndUnknownActionOnWire() {
    String command =
        "{\"action\":\"OPEN_VIEW\",\"viewId\":\""
            + VIEW
            + "\",\"documentId\":\""
            + DOCUMENT
            + "\",\"eventNodeId\":\""
            + EVENT_NODE
            + "\",\"connectionId\":\""
            + CONNECTION
            + "\",\"claimId\":\""
            + CLAIM
            + "\",\"mode\":\"replace\"}";
    assertThrows(DaemonProtocolException.class, () -> codec.decodeCommand(command));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeCommand("{\"action\":\"OPEN_STREAM\"}"));
  }

  @Test
  void rejectsUnsafeFailureReplyAndDuplicateViewSummaries() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeReply(
                false, null, null, null, null, null, null, null, null, null, "view gone", "x"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeReply(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "VIEW_GONE",
                "x".repeat(501)));
    UUID viewId = UUID.fromString(VIEW);
    IdeViewSummary summary =
        new IdeViewSummary(
            viewId,
            UUID.fromString(DOCUMENT),
            UUID.fromString(EVENT_NODE),
            UUID.fromString(CONNECTION),
            UUID.fromString(CLAIM),
            1L,
            "/tmp",
            "/daemon/views/" + VIEW,
            IdeViewState.OPEN);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeReply(
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(summary, summary),
                null,
                null));
  }
}
