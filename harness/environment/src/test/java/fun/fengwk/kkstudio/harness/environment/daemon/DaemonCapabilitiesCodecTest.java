package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** READY capabilities v6 codec 的严格版本、environment metadata 与能力摘要契约。 */
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
            List.of(new DaemonSkillDescriptor("dev", "Developer rules")));

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals(
        "{\"version\":6,"
            + ENVIRONMENT_JSON
            + ",\"skills\":[{\"name\":\"dev\",\"description\":\"Developer rules\"}]}",
        encoded);
  }

  @Test
  void encodesEmptyCapabilityLists() {
    assertEquals(
        "{\"version\":6," + ENVIRONMENT_JSON + ",\"skills\":[]}",
        codec.encode(new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT, List.of())));
  }

  @Test
  void rejectsLegacyVersionsAndMissingEnvironment() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"version\":1,\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2," + ENVIRONMENT_JSON + ",\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"version\":4,\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"version\":5,\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":6,\"environment\":null,\"skills\":[]}"));
  }

  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode(payload("\"secret\":\"x\",", "")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + ENVIRONMENT_JSON
                    + ",\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\"},"
                    + "\"skills\":[]}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "") + " x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\",\"extra\":\"x\"},"
                    + "\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\"},\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"one\",\"note\":\"two\"},"
                    + "\"skills\":[]}"));
  }

  @Test
  void rejectsMissingEnvironmentFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"environment\":{\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\"},\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"note\":\"Linux environment.\"},\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\"},\"skills\":[]}"));
  }

  @Test
  void rejectsInvalidNestedCapabilityShapes() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":\"6\"," + ENVIRONMENT_JSON + ",\"skills\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":6," + ENVIRONMENT_JSON + ",\"skills\":{}}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode(payload("", "null")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                payload(
                    "",
                    "{\"name\":\"same\",\"description\":\"one\"},"
                        + "{\"name\":\"same\",\"description\":\"two\"}")));
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
                "{\"version\":6,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\"},"
                    + "\"skills\":[]}"));
  }

  @Test
  void rejectsDuplicateSkillNames() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                ENVIRONMENT,
                List.of(
                    new DaemonSkillDescriptor("same", "one"),
                    new DaemonSkillDescriptor("same", "two"))));
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
                "{\"version\":6,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\",\"rootPath\":\""
                    + jsonEscape(rootPath)
                    + "\"},\"skills\":[]}"));
  }

  private static String payloadWithEnvironment(
      String operatingSystem, String timeZoneJson, String noteJson) {
    return "{\"version\":6,\"environment\":{\"operatingSystem\":\""
        + operatingSystem
        + "\",\"timeZone\":\""
        + timeZoneJson
        + "\",\"note\":\""
        + noteJson
        + "\",\"rootPath\":\"/home/dev\"},\"skills\":[]}";
  }

  private static String payload(String rootPrefix, String skills) {
    return "{\"version\":6," + rootPrefix + ENVIRONMENT_JSON + ",\"skills\":[" + skills + "]}";
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
