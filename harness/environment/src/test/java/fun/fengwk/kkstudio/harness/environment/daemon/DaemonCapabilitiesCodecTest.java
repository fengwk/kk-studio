package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** READY capabilities codec 的严格版本、宿主 metadata 形状与未知字段拒绝契约。 */
class DaemonCapabilitiesCodecTest {

  private static final DaemonEnvironmentInfo ENVIRONMENT =
      new DaemonEnvironmentInfo(
          DaemonOperatingSystem.LINUX, "Asia/Shanghai", "Linux environment.", "/home/dev");
  private static final String ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"note\":\"Linux environment.\","
          + "\"rootPath\":\"/home/dev\"}";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final DaemonCapabilitiesCodec codec = new DaemonCapabilitiesCodec();

  /** READY 的唯一 wire 形状必须精确往返：只有 version 与 environment 两个顶层字段。 */
  @Test
  void roundTripsHostMetadataOnly() {
    DaemonCapabilities original = new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT);

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals("{\"version\":1," + ENVIRONMENT_JSON + "}", encoded);
  }

  /** 历史上的 sourceSetVersion / skillSources 都是未知字段，必须在 wire 边界拒绝而不是忽略。 */
  @Test
  void rejectsLegacySkillInventoryFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,"
                    + ENVIRONMENT_JSON
                    + ",\"skillSources\":[{\"sourceId\":\"x\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1," + ENVIRONMENT_JSON + ",\"sourceSetVersion\":0,\"skills\":[]}"));
  }

  /** 非当前版本、非整数版本与非法 environment 形状都按协议错误拒绝。 */
  @Test
  void rejectsUnsupportedVersionsAndShapes() {
    for (String version : new String[] {"0", "2", "3", "\"1\"", "null", "true", "{}", "[]"}) {
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{\"version\":" + version + "," + ENVIRONMENT_JSON + "}"),
          version);
    }
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"environment\":" + ENVIRONMENT_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"version\":1,\"environment\":null}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"version\":1}"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"version\":1,\"environment\":[]}"));
    assertThrows(IllegalArgumentException.class, () -> new DaemonCapabilities(2, ENVIRONMENT));
    assertThrows(NullPointerException.class, () -> new DaemonCapabilities(1, null));
  }

  /** 顶层与 environment 都只接受固定字段：未知字段、重复键与尾随内容一律拒绝。 */
  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1," + ENVIRONMENT_JSON + ",\"secret\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1," + ENVIRONMENT_JSON + "} x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"Linux environment.\","
                    + "\"rootPath\":\"/home/dev\",\"extra\":\"x\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"note\":\"one\",\"note\":\"two\","
                    + "\"rootPath\":\"/home/dev\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":1,\"version\":1," + ENVIRONMENT_JSON + "}"));
  }

  /** environment 的四个字段缺一不可，且都必须是 non-blank 文本。 */
  @Test
  void rejectsMissingEnvironmentFields() {
    for (String omitted : new String[] {"operatingSystem", "timeZone", "note", "rootPath"}) {
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{\"version\":1," + environmentWithout(omitted) + "}"),
          omitted);
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{\"version\":1," + environmentBlank(omitted) + "}"),
          omitted);
    }
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
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvironmentInfo(DaemonOperatingSystem.LINUX, "UTC", "Note", null));
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }

  private void assertInvalidEnvironment(String operatingSystem, String timeZone, String note) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", operatingSystem);
    environment.put("timeZone", timeZone);
    environment.put("note", note);
    environment.put("rootPath", "/home/dev");
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidRootPath(String rootPath) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", "linux");
    environment.put("timeZone", "UTC");
    environment.put("note", "Linux environment.");
    environment.put("rootPath", rootPath);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private static String environmentWithout(String omitted) {
    StringBuilder sb = new StringBuilder("\"environment\":{");
    boolean first = true;
    for (String field : new String[] {"operatingSystem", "timeZone", "note", "rootPath"}) {
      if (field.equals(omitted)) {
        continue;
      }
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(field).append("\":\"").append(valueOf(field)).append('"');
    }
    return sb.append('}').toString();
  }

  private static String environmentBlank(String blankField) {
    StringBuilder sb = new StringBuilder("\"environment\":{");
    boolean first = true;
    for (String field : new String[] {"operatingSystem", "timeZone", "note", "rootPath"}) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(field).append("\":\"");
      if (!field.equals(blankField)) {
        sb.append(valueOf(field));
      }
      sb.append('"');
    }
    return sb.append('}').toString();
  }

  private static String valueOf(String field) {
    return switch (field) {
      case "operatingSystem" -> "linux";
      case "timeZone" -> "UTC";
      case "note" -> "Linux environment.";
      case "rootPath" -> "/home/dev";
      default -> throw new IllegalArgumentException(field);
    };
  }
}
