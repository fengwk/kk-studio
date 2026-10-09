package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 意图：受管更新命令的 wire 边界必须严格 —— 固定四字段、拒绝未知字段/重复键/尾随内容，且 operationId 必须是规范 UUID、 artifactUrl
 * 必须是官方发布形态，避免伪造命令把更新写进数据目录之外或指向任意地址。
 */
class DaemonUpdateCommandCodecTest {

  private static final String OP = "11111111-1111-1111-1111-111111111111";
  private static final String VERSION = "1.0.9";
  private static final String URL = DaemonUpdateArtifact.artifactUrl(VERSION);
  private static final String SHA = "a".repeat(64);

  private final DaemonUpdateCommandCodec codec = new DaemonUpdateCommandCodec();

  @Test
  void roundTripsStrictPayload() {
    DaemonUpdateCommand command = new DaemonUpdateCommand(OP, VERSION, URL, SHA);
    assertEquals(command, codec.decode(codec.encode(command)));
  }

  @Test
  void rejectsUnknownField() {
    String payload =
        "{\"operationId\":\""
            + OP
            + "\",\"targetVersion\":\""
            + VERSION
            + "\",\"artifactUrl\":\""
            + URL
            + "\",\"artifactSha256\":\""
            + SHA
            + "\",\"extra\":true}";
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
  }

  @Test
  void rejectsDuplicateKey() {
    String payload =
        "{\"operationId\":\""
            + OP
            + "\",\"operationId\":\""
            + OP
            + "\",\"targetVersion\":\""
            + VERSION
            + "\",\"artifactUrl\":\""
            + URL
            + "\",\"artifactSha256\":\""
            + SHA
            + "\"}";
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
  }

  @Test
  void rejectsTrailingContent() {
    String payload = codec.encode(new DaemonUpdateCommand(OP, VERSION, URL, SHA));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload + " {}"));
  }

  @Test
  void rejectsOperationIdThatIsNotACanonicalUuid() {
    for (String operationId :
        new String[] {
          "", "  ", "123", "../escape", "/absolute", "a/b", "ABCDEF01-2345-6789-ABCD-EF0123456789"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DaemonUpdateCommand.validateOperationId(operationId),
          "operationId must be rejected: " + operationId);
    }
    assertEquals(OP, DaemonUpdateCommand.validateOperationId(OP));
  }

  @Test
  void rejectsNonOfficialArtifactUrl() {
    String payload =
        "{\"operationId\":\""
            + OP
            + "\",\"targetVersion\":\""
            + VERSION
            + "\",\"artifactUrl\":\"https://evil.example/kk-studio-daemon-v1.0.9.jar\","
            + "\"artifactSha256\":\""
            + SHA
            + "\"}";
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
  }

  @Test
  void rejectsMalformedSha256() {
    String payload =
        "{\"operationId\":\""
            + OP
            + "\",\"targetVersion\":\""
            + VERSION
            + "\",\"artifactUrl\":\""
            + URL
            + "\",\"artifactSha256\":\"not-a-digest\"}";
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload));
  }
}
