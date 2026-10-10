package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 意图：IDE 控制命令/回执的 wire 边界必须严格 —— 固定字段、拒绝未知字段/重复键/尾随内容，且各 action 只允许其语义对应的 字段组合；viewId 必须是规范
 * UUID，streamUrl/path/ticket/header 必须是安全形态，避免命令穿越到任意地址或注入 header。
 */
class DaemonIdeCodecTest {

  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String TICKET = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

  private final DaemonIdeCodec codec = new DaemonIdeCodec();

  @Test
  void roundTripsOpenViewWithAndWithoutDirectory() {
    IdeCommand withDirectory =
        new IdeCommand(
            IdeAction.OPEN_VIEW,
            VIEW,
            "/home/user/project",
            null,
            null,
            null,
            null,
            null,
            List.of());
    assertEquals(withDirectory, codec.decodeCommand(codec.encodeCommand(withDirectory)));
    IdeCommand withoutDirectory = IdeCommand.view(IdeAction.OPEN_VIEW, VIEW, null);
    assertEquals(withoutDirectory, codec.decodeCommand(codec.encodeCommand(withoutDirectory)));
  }

  @Test
  void roundTripsChangeDirectoryFocusAndClose() {
    for (IdeCommand command :
        List.of(
            IdeCommand.view(IdeAction.CHANGE_DIRECTORY, VIEW, "/tmp"),
            IdeCommand.view(IdeAction.FOCUS_TERMINAL, VIEW, null),
            IdeCommand.view(IdeAction.CLOSE_VIEW, VIEW, null))) {
      assertEquals(command, codec.decodeCommand(codec.encodeCommand(command)));
    }
  }

  @Test
  void roundTripsHttpOpenStreamWithQueryAndHeaders() {
    IdeCommand command =
        new IdeCommand(
            IdeAction.OPEN_STREAM,
            VIEW,
            null,
            TICKET,
            "wss://abc.ide.example.com/api/harness/environment-stream/v1/" + TICKET,
            IdeStreamMode.HTTP,
            "GET",
            "/?folder=/home/user/project&t=1",
            List.of(new IdeHeader("Accept", "application/json")));
    assertEquals(command, codec.decodeCommand(codec.encodeCommand(command)));
  }

  @Test
  void roundTripsWsOpenStream() {
    IdeCommand command =
        new IdeCommand(
            IdeAction.OPEN_STREAM,
            VIEW,
            null,
            TICKET,
            "wss://abc.ide.example.com/api/harness/environment-stream/v1/" + TICKET,
            IdeStreamMode.WS,
            null,
            null,
            List.of());
    assertEquals(command, codec.decodeCommand(codec.encodeCommand(command)));
  }

  @Test
  void roundTripsReplyVariants() {
    IdeReply ok = IdeReply.ok(VIEW, "/home/user/project", "/daemon/workspaces/" + VIEW);
    assertEquals(ok, codec.decodeReply(codec.encodeReply(ok)));
    IdeReply failure = IdeReply.failure("directory is not accessible");
    assertEquals(failure, codec.decodeReply(codec.encodeReply(failure)));
  }

  @Test
  void rejectsUnknownCommandField() {
    String json = "{\"action\":\"FOCUS_TERMINAL\",\"viewId\":\"" + VIEW + "\",\"extra\":1}";
    assertThrows(DaemonProtocolException.class, () -> codec.decodeCommand(json));
  }

  @Test
  void rejectsDuplicateKeyAndTrailingContent() {
    String duplicate =
        "{\"action\":\"FOCUS_TERMINAL\",\"action\":\"CLOSE_VIEW\",\"viewId\":\"" + VIEW + "\"}";
    assertThrows(DaemonProtocolException.class, () -> codec.decodeCommand(duplicate));
    String json = "{\"action\":\"FOCUS_TERMINAL\",\"viewId\":\"" + VIEW + "\"}";
    assertThrows(DaemonProtocolException.class, () -> codec.decodeCommand(json + " {}"));
  }

  @Test
  void rejectsUnknownReplyFieldAndNonBooleanOk() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeReply("{\"ok\":true,\"extra\":1}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeReply("{\"ok\":\"yes\"}"));
  }

  @Test
  void rejectsNonCanonicalViewId() {
    for (String viewId :
        new String[] {"", "123", "../escape", "ABCDEF01-2345-6789-ABCD-EF0123456789"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> IdeCommand.view(IdeAction.CLOSE_VIEW, viewId, null),
          "viewId must be rejected: " + viewId);
    }
  }

  @Test
  void rejectsStreamFieldsOnNonStreamActions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_VIEW, VIEW, null, TICKET, null, null, null, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.CLOSE_VIEW,
                VIEW,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new IdeHeader("Accept", "*/*"))));
  }

  @Test
  void changeDirectoryRequiresDirectory() {
    assertThrows(
        IllegalArgumentException.class,
        () -> IdeCommand.view(IdeAction.CHANGE_DIRECTORY, VIEW, null));
  }

  @Test
  void openStreamRequiresTicketUrlAndMode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_STREAM,
                VIEW,
                null,
                TICKET,
                null,
                IdeStreamMode.WS,
                null,
                null,
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_STREAM,
                VIEW,
                null,
                null,
                "wss://x.example/s",
                IdeStreamMode.WS,
                null,
                null,
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_STREAM,
                VIEW,
                null,
                TICKET,
                "wss://x.example/s",
                null,
                null,
                null,
                List.of()));
  }

  @Test
  void httpOpenStreamRequiresMethodAndPath() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_STREAM,
                VIEW,
                null,
                TICKET,
                "wss://x.example/s",
                IdeStreamMode.HTTP,
                null,
                "/",
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_STREAM,
                VIEW,
                null,
                TICKET,
                "wss://x.example/s",
                IdeStreamMode.HTTP,
                "GET",
                null,
                List.of()));
  }

  @Test
  void rejectsUnsafePathAndStreamUrl() {
    for (String path : new String[] {"//evil.example/", "http://evil.example/", "relative"}) {
      assertThrows(IllegalArgumentException.class, () -> IdeCommand.validatePath(path), path);
    }
    for (String url :
        new String[] {
          "ftp://x.example/s", "wss://user:pw@x.example/s", "wss://x.example/s#frag", "/relative"
        }) {
      assertThrows(IllegalArgumentException.class, () -> IdeCommand.validateStreamUrl(url), url);
    }
  }

  @Test
  void rejectsNonBase64UrlTicketAndBadHeaders() {
    assertThrows(IllegalArgumentException.class, () -> IdeCommand.validateTicket("has space"));
    assertThrows(IllegalArgumentException.class, () -> IdeCommand.validateTicket("../path"));
    assertThrows(IllegalArgumentException.class, () -> new IdeHeader("Bad Name", "v"));
    assertThrows(IllegalArgumentException.class, () -> new IdeHeader("X-Test", "v\r\nInjected"));
  }

  @Test
  void wsOpenStreamRejectsMethodAndPath() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdeCommand(
                IdeAction.OPEN_STREAM,
                VIEW,
                null,
                TICKET,
                "wss://x.example/s",
                IdeStreamMode.WS,
                "GET",
                "/",
                List.of()));
  }
}
