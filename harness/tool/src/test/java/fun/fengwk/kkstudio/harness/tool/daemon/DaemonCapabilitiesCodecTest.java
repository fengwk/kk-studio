package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** READY capabilities v2 codec 的严格版本、environment metadata 与能力摘要契约。 */
class DaemonCapabilitiesCodecTest {

  private static final DaemonEnvironmentInfo ENVIRONMENT =
      new DaemonEnvironmentInfo(DaemonOperatingSystem.LINUX, "/workspace/project", "Asia/Shanghai");
  private static final String ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"workingDirectory\":\"/workspace/project\",\"timeZone\":\"Asia/Shanghai\"}";

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
        "{\"version\":2,"
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
        "{\"version\":2," + ENVIRONMENT_JSON + ",\"skills\":[],\"mcpServers\":[]}",
        codec.encode(
            new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, List.of(), List.of())));
  }

  @Test
  void rejectsLegacyV1AndMissingEnvironment() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1,\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1," + ENVIRONMENT_JSON + ",\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2,\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2,\"environment\":null,\"skills\":[],\"mcpServers\":[]}"));
  }

  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode(payload("\"secret\":\"x\",", "", "")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,"
                    + ENVIRONMENT_JSON
                    + ",\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/tmp\",\"timeZone\":\"UTC\"},"
                    + "\"skills\":[],\"mcpServers\":[]}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "", "") + " x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace/project\",\"timeZone\":\"UTC\","
                    + "\"extra\":\"x\"},\"skills\":[],\"mcpServers\":[]}"));
  }

  @Test
  void rejectsInvalidEnvironmentMetadata() {
    assertInvalidEnvironment("plan9", "/workspace", "UTC");
    assertInvalidEnvironment("linux", "relative/path", "UTC");
    assertInvalidEnvironment("linux", "/workspace/../secret", "UTC");
    assertInvalidEnvironment("linux", "/workspace\nsecret", "UTC");
    assertInvalidEnvironment("linux", "/workspace", "Not/AZone");
    assertInvalidEnvironment("windows", "/workspace", "UTC");
    assertInvalidEnvironment("windows", "C:\\workspace\\..\\secret", "UTC");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "/" + "x".repeat(DaemonEnvironmentInfo.MAX_WORKING_DIRECTORY_CHARS),
                "UTC"));
  }

  @Test
  void acceptsWindowsDriveAndUncAbsolutePaths() {
    assertEquals(
        "C:\\workspace",
        codec
            .decode(payloadWithEnvironment("windows", "C:\\\\workspace", "UTC"))
            .environment()
            .workingDirectory());
    assertEquals(
        "C:\\",
        codec
            .decode(payloadWithEnvironment("windows", "C:\\\\", "UTC"))
            .environment()
            .workingDirectory());
    assertEquals(
        "\\\\server\\share\\project",
        codec
            .decode(payloadWithEnvironment("windows", "\\\\\\\\server\\\\share\\\\project", "UTC"))
            .environment()
            .workingDirectory());
    assertEquals(
        "\\\\server\\share\\",
        codec
            .decode(payloadWithEnvironment("windows", "\\\\\\\\server\\\\share\\\\", "UTC"))
            .environment()
            .workingDirectory());
    assertInvalidEnvironment("windows", "C:\\workspace\\", "UTC");
    assertInvalidEnvironment("windows", "C:\\workspace/project", "UTC");
  }

  /** POSIX path 只按 wire lexical 语义校验，不能依赖 codec 所在主机的 Path provider。 */
  @Test
  void validatesCanonicalPosixPathsIndependentlyOfHostOperatingSystem() {
    assertEquals(
        "/",
        codec.decode(payloadWithEnvironment("linux", "/", "UTC")).environment().workingDirectory());
    assertEquals(
        "/mnt/c/workspace\\literal",
        codec
            .decode(payloadWithEnvironment("wsl", "/mnt/c/workspace\\\\literal", "UTC"))
            .environment()
            .workingDirectory());
    assertInvalidEnvironment("linux", "/workspace/", "UTC");
    assertInvalidEnvironment("linux", "/workspace//project", "UTC");
    assertInvalidEnvironment("macos", "/workspace/./project", "UTC");
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

  private void assertInvalidEnvironment(
      String operatingSystem, String workingDirectory, String timeZone) {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payloadWithEnvironment(
                    operatingSystem, jsonEscape(workingDirectory), jsonEscape(timeZone))));
  }

  private static String payloadWithEnvironment(
      String operatingSystem, String workingDirectoryJson, String timeZoneJson) {
    return "{\"version\":2,\"environment\":{\"operatingSystem\":\""
        + operatingSystem
        + "\",\"workingDirectory\":\""
        + workingDirectoryJson
        + "\",\"timeZone\":\""
        + timeZoneJson
        + "\"},\"skills\":[],\"mcpServers\":[]}";
  }

  private static String payload(String rootPrefix, String skills, String servers) {
    return "{\"version\":2,"
        + rootPrefix
        + ENVIRONMENT_JSON
        + ",\"skills\":["
        + skills
        + "],\"mcpServers\":["
        + servers
        + "]}";
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
  }
}
