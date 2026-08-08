package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** READY capabilities codec 的严格性：版本、未知字段、重复名称与错误边界。 */
class DaemonCapabilitiesCodecTest {

  private final DaemonCapabilitiesCodec codec = new DaemonCapabilitiesCodec();

  @Test
  void roundTripsCapabilities() {
    DaemonCapabilities original =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            List.of(new DaemonSkillDescriptor("dev", "Developer rules")),
            List.of(
                new DaemonMcpServerDescriptor(
                    "filesystem",
                    DaemonMcpServerStatus.READY,
                    null,
                    List.of(
                        new DaemonMcpToolDescriptor("read_file", "Read a file"),
                        new DaemonMcpToolDescriptor("write_file", "Write a file"))),
                new DaemonMcpServerDescriptor(
                    "broken", DaemonMcpServerStatus.FAILED, "cannot start process", List.of())));

    assertEquals(original, codec.decode(codec.encode(original)));
  }

  @Test
  void encodesEmptyCapabilities() {
    assertEquals(
        "{\"version\":1,\"skills\":[],\"mcpServers\":[]}",
        codec.encode(DaemonCapabilities.empty()));
  }

  @Test
  void rejectsUnknownFields() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1,\"skills\":[],\"mcpServers\":[],\"tools\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"READY\","
                    + "\"error\":null,\"tools\":[],\"secret\":\"x\"}]}"));
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1,\"skills\":[],\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"READY\","
                    + "\"error\":null,\"tools\":[],\"error\":null}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1,\"skills\":[],\"mcpServers\":[]} trailing"));
  }

  @Test
  void rejectsMissingErrorOnMcpServer() {
    // error 是必填字段（文本或显式 null），缺失即拒绝。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"READY\","
                    + "\"tools\":[]}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"FAILED\","
                    + "\"tools\":[]}]}"));
  }

  @Test
  void rejectsServerNameBeyond64Chars() {
    String tooLong = "a".repeat(65);
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\""
                    + tooLong
                    + "\",\"status\":\"READY\",\"error\":null,\"tools\":[]}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonMcpServerDescriptor(tooLong, DaemonMcpServerStatus.READY, null, List.of()));
    // 64 字符边界仍合法。
    String maxLength = "a".repeat(64);
    assertEquals(
        maxLength,
        codec
            .decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\""
                    + maxLength
                    + "\",\"status\":\"READY\",\"error\":null,\"tools\":[]}]}")
            .mcpServers()
            .get(0)
            .name());
  }

  @Test
  void rejectsMissingOrWrongVersion() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2,\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":\"1\",\"skills\":[],\"mcpServers\":[]}"));
  }

  @Test
  void rejectsDuplicateNames() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                List.of(
                    new DaemonSkillDescriptor("same", "one"),
                    new DaemonSkillDescriptor("same", "two")),
                List.of()));

    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":["
                    + "{\"name\":\"a\",\"status\":\"READY\",\"error\":null,\"tools\":[]},"
                    + "{\"name\":\"a\",\"status\":\"READY\",\"error\":null,\"tools\":[]}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"READY\","
                    + "\"error\":null,\"tools\":[{\"name\":\"t\",\"description\":\"one\"},"
                    + "{\"name\":\"t\",\"description\":\"two\"}]}]}"));
  }

  @Test
  void rejectsInvalidStatusAndNonCanonicalServerName() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"UP\","
                    + "\"error\":null,\"tools\":[]}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"A_b\",\"status\":\"READY\","
                    + "\"error\":null,\"tools\":[]}]}"));
  }

  @Test
  void rejectsToolsOnFailedServerAndErrorOnReadyServer() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"FAILED\","
                    + "\"error\":\"boom\",\"tools\":[{\"name\":\"t\",\"description\":\"d\"}]}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"READY\","
                    + "\"error\":\"boom\",\"tools\":[]}]}"));
  }

  @Test
  void rejectsOversizedErrorOnDecode() {
    String oversized = "x".repeat(DaemonMcpServerDescriptor.MAX_ERROR_CHARS + 1);
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"FAILED\","
                    + "\"error\":\""
                    + oversized
                    + "\",\"tools\":[]}]}"));
  }

  @Test
  void decodesBlankErrorAsNull() {
    DaemonCapabilities decoded =
        codec.decode(
            "{\"version\":1,\"skills\":[],\"mcpServers\":[{\"name\":\"a\",\"status\":\"FAILED\","
                + "\"error\":\"  \",\"tools\":[]}]}");
    assertNull(decoded.mcpServers().get(0).error());
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }
}
