package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** READY capabilities v4 codec 的严格版本、environment metadata 与能力摘要契约。 */
class DaemonCapabilitiesCodecTest {

  private static final DaemonEnvironmentInfo ENVIRONMENT =
      new DaemonEnvironmentInfo(
          DaemonOperatingSystem.LINUX, "Asia/Shanghai", "Linux environment.", "/home/dev");
  private static final String ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"note\":\"Linux environment.\","
          + "\"rootPath\":\"/home/dev\"}";

  private final DaemonCapabilitiesCodec codec = new DaemonCapabilitiesCodec();

  @Test
  void roundTripsCapabilitiesWithExactWireOrder() {
    DaemonCapabilities original =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            ENVIRONMENT,
            List.of(new DaemonSkillDescriptor("dev", "Developer rules")),
            List.of(
                new DaemonMcpServerDescriptor(
                    "filesystem",
                    DaemonMcpServerStatus.READY,
                    null,
                    List.of(new DaemonMcpToolDescriptor("read_file", "Read a file"))),
                new DaemonMcpServerDescriptor(
                    "broken", DaemonMcpServerStatus.FAILED, "cannot start process", List.of())));

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals(
        "{\"version\":4,"
            + ENVIRONMENT_JSON
            + ",\"skills\":[{\"name\":\"dev\",\"description\":\"Developer rules\"}],"
            + "\"mcpServers\":[{\"name\":\"filesystem\",\"status\":\"READY\",\"error\":null,"
            + "\"tools\":[{\"name\":\"read_file\",\"description\":\"Read a file\"}]},"
            + "{\"name\":\"broken\",\"status\":\"FAILED\",\"error\":\"cannot start process\","
            + "\"tools\":[]}]}",
        encoded);
  }

  @Test
  void encodesEmptyCapabilityLists() {
    assertEquals(
        "{\"version\":4," + ENVIRONMENT_JSON + ",\"skills\":[],\"mcpServers\":[]}",
        codec.encode(
            new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, List.of(), List.of())));
  }

  @Test
  void rejectsLegacyVersionsAndMissingEnvironment() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1,\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2," + ENVIRONMENT_JSON + ",\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":4,\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":4,\"environment\":null,\"skills\":[],\"mcpServers\":[]}"));
  }

  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode(payload("\"secret\":\"x\",", "", "")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + ENVIRONMENT_JSON
                    + ",\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\"},"
                    + "\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "", "") + " x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\",\"extra\":\"x\"},"
                    + "\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\"},\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"one\",\"note\":\"two\"},"
                    + "\"skills\":[],\"mcpServers\":[]}"));
  }

  @Test
  void rejectsMissingEnvironmentFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\"},\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"note\":\"Linux environment.\"},\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\"},\"skills\":[],\"mcpServers\":[]}"));
  }

  @Test
  void rejectsInvalidNestedCapabilityShapes() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":\"4\"," + ENVIRONMENT_JSON + ",\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4," + ENVIRONMENT_JSON + ",\"skills\":{},\"mcpServers\":[]}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "null", "")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "{\"name\":\"same\",\"description\":\"one\"},"
                        + "{\"name\":\"same\",\"description\":\"two\"}",
                    "")));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "", "null")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "", "", "{\"name\":\"a\",\"status\":\"BROKEN\",\"error\":null,\"tools\":[]}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "",
                    "{\"name\":\"a\",\"status\":\"READY\",\"error\":null,\"tools\":[null]}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "", "", "{\"name\":\"a\",\"status\":\"FAILED\",\"error\":1,\"tools\":[]}")));
  }

  @Test
  void rejectsInvalidEnvironmentMetadata() {
    assertInvalidEnvironment("plan9", "UTC", "Local environment.");
    assertInvalidEnvironment("linux", "Not/AZone", "Linux environment.");
    assertInvalidEnvironment("linux", "UTC", "");
    assertInvalidEnvironment("linux", "UTC", " ");
    assertInvalidEnvironment("linux", "UTC", " leading");
    assertInvalidEnvironment("linux", "UTC", "trailing ");
    assertInvalidEnvironment("linux", "UTC", "first\nsecond");
    assertInvalidEnvironment("linux", "UTC", "first\u2028second");
    assertInvalidEnvironment("linux", "UTC", "control\u0000value");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1),
                "/home/dev"));
    assertInvalidRootPath("");
    assertInvalidRootPath(" ");
    assertInvalidRootPath(" /home/dev");
    assertInvalidRootPath("/home/dev ");
    assertInvalidRootPath("/home/dev\u0000x");
  }

  /** rootPath 是必填展示字段：缺失或非法 rootPath 都在 wire 边界拒绝。 */
  @Test
  void rejectsMissingOrInvalidRootPath() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\"},"
                    + "\"skills\":[],\"mcpServers\":[]}"));
  }

  @Test
  void rejectsDuplicateNamesAndInvalidMcpSummaries() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                ENVIRONMENT,
                List.of(
                    new DaemonSkillDescriptor("same", "one"),
                    new DaemonSkillDescriptor("same", "two")),
                List.of()));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "",
                    "{\"name\":\"a\",\"status\":\"READY\",\"error\":null,\"tools\":[]},"
                        + "{\"name\":\"a\",\"status\":\"READY\",\"error\":null,\"tools\":[]}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "",
                    "{\"name\":\"a\",\"status\":\"FAILED\",\"error\":\"boom\","
                        + "\"tools\":[{\"name\":\"t\",\"description\":\"d\"}]}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "",
                    "{\"name\":\"a\",\"status\":\"READY\",\"error\":\"boom\",\"tools\":[]}")));
  }

  @Test
  void requiresMcpErrorAndNormalizesBlankFailedError() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode(payload("", "", "{\"name\":\"a\",\"status\":\"FAILED\",\"tools\":[]}")));
    DaemonCapabilities decoded =
        codec.decode(
            payload(
                "", "", "{\"name\":\"a\",\"status\":\"FAILED\",\"error\":\"  \",\"tools\":[]}"));
    assertNull(decoded.mcpServers().getFirst().error());
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }

  private void assertInvalidEnvironment(String operatingSystem, String timeZone, String note) {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payloadWithEnvironment(operatingSystem, jsonEscape(timeZone), jsonEscape(note))));
  }

  private void assertInvalidRootPath(String rootPath) {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\",\"rootPath\":\""
                    + jsonEscape(rootPath)
                    + "\"},\"skills\":[],\"mcpServers\":[]}"));
  }

  private static String payloadWithEnvironment(
      String operatingSystem, String timeZoneJson, String noteJson) {
    return "{\"version\":4,\"environment\":{\"operatingSystem\":\""
        + operatingSystem
        + "\",\"timeZone\":\""
        + timeZoneJson
        + "\",\"note\":\""
        + noteJson
        + "\",\"rootPath\":\"/home/dev\"},\"skills\":[],\"mcpServers\":[]}";
  }

  private static String payload(String rootPrefix, String skills, String servers) {
    return "{\"version\":4,"
        + rootPrefix
        + ENVIRONMENT_JSON
        + ",\"skills\":["
        + skills
        + "],\"mcpServers\":["
        + servers
        + "]}";
  }

  private static String jsonEscape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\u0000", "\\u0000")
        .replace("\n", "\\n")
        .replace("\u2028", "\\u2028")
        .replace("\"", "\\\"");
  }
}
