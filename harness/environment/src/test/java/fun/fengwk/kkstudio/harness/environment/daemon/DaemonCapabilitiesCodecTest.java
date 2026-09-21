package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** READY capabilities codec 的严格版本、宿主 metadata 形状与未知字段拒绝契约。 */
class DaemonCapabilitiesCodecTest {

  private static final DaemonEnvironmentInfo ENVIRONMENT =
      new DaemonEnvironmentInfo(
          DaemonOperatingSystem.LINUX, "Asia/Shanghai", "dev", "/home/dev", "Linux environment.");
  private static final String ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
          + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\"}";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final DaemonCapabilitiesCodec codec = new DaemonCapabilitiesCodec();

  /** READY 的唯一 wire 形状必须精确往返：只有 version 与 environment 两个顶层字段。 */
  @Test
  void roundTripsHostMetadataOnly() {
    DaemonCapabilities original = new DaemonCapabilities(DaemonCapabilities.VERSION, ENVIRONMENT);

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals("{\"version\":2," + ENVIRONMENT_JSON + "}", encoded);
  }

  /** Windows HOME 必须按目标 Daemon 语法校验，不能被 Linux Platform 的 Path 语义误拒绝。 */
  @Test
  void acceptsWindowsHomeDirectoryOnAnyPlatformHost() {
    DaemonEnvironmentInfo windows =
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.WINDOWS, "UTC", "dev", "C:\\Users\\dev", "Windows environment.");

    assertEquals("C:\\Users\\dev", windows.homeDirectory());
    assertEquals(
        windows,
        codec
            .decode(codec.encode(new DaemonCapabilities(DaemonCapabilities.VERSION, windows)))
            .environment());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", "C:\\Users\\dev", "Linux environment."));
  }

  /** 历史上的 sourceSetVersion / skillSources 都是未知字段，必须在 wire 边界拒绝而不是忽略。 */
  @Test
  void rejectsLegacySkillInventoryFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,"
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,"
                    + ENVIRONMENT_JSON
                    + ",\"skillSources\":[{\"sourceId\":\"x\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2," + ENVIRONMENT_JSON + ",\"sourceSetVersion\":0,\"skills\":[]}"));
  }

  /** 严格拒绝已删除的 rootPath 元数据字段与历史 v1 payload。 */
  @Test
  void rejectsLegacyRootPathAndVersion1() {
    // 严格拒绝 v1 payload（携带已删除的 rootPath）
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":1,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"Asia/Shanghai\",\"note\":\"Linux environment.\","
                    + "\"rootPath\":\"/home/dev\"}}"));

    // 严格拒绝 environment 中残留的已删除字段
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decode(
                    "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                        + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
                        + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
                        + "\"rootPath\":\"/home/dev\"}}"));
    assertTrue(
        error.getMessage().contains("unexpected READY environment field: rootPath"),
        error.getMessage());
  }

  /** 非当前版本、非整数版本与非法 environment 形状都按协议错误拒绝。 */
  @Test
  void rejectsUnsupportedVersionsAndShapes() {
    for (String version : new String[] {"0", "1", "3", "\"2\"", "null", "true", "{}", "[]"}) {
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{\"version\":" + version + "," + ENVIRONMENT_JSON + "}"),
          version);
    }
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"environment\":" + ENVIRONMENT_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"version\":2,\"environment\":null}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"version\":2}"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decode("{\"version\":2,\"environment\":[]}"));
    assertThrows(IllegalArgumentException.class, () -> new DaemonCapabilities(1, ENVIRONMENT));
    assertThrows(IllegalArgumentException.class, () -> new DaemonCapabilities(3, ENVIRONMENT));
    assertThrows(NullPointerException.class, () -> new DaemonCapabilities(2, null));
  }

  /** 顶层与 environment 都只接受固定字段：未知字段、重复键与尾随内容一律拒绝。 */
  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2," + ENVIRONMENT_JSON + ",\"secret\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2," + ENVIRONMENT_JSON + "} x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"Linux environment.\",\"extra\":\"x\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"Linux environment.\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":2,\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"one\",\"note\":\"two\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":2,\"version\":2," + ENVIRONMENT_JSON + "}"));
  }

  /** environment 的五个字段缺一不可，且都必须是 non-blank 文本。 */
  @Test
  void rejectsMissingEnvironmentFields() {
    for (String omitted :
        new String[] {"operatingSystem", "timeZone", "userName", "homeDirectory", "note"}) {
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{\"version\":2," + environmentWithout(omitted) + "}"),
          omitted);
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{\"version\":2," + environmentBlank(omitted) + "}"),
          omitted);
    }
  }

  @Test
  void rejectsInvalidEnvironmentMetadata() {
    assertInvalidEnvironment("plan9", "UTC", "dev", "/home/dev", "Local environment.");
    assertInvalidEnvironment("linux", "Not/AZone", "dev", "/home/dev", "Linux environment.");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", " ");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", " leading");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "trailing ");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "first\nsecond");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "first\u2028second");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "control\u0000value");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "/home/dev",
                "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1)));

    // userName 校验：空白、周边空白、换行、控制字符
    assertInvalidUserName("");
    assertInvalidUserName(" ");
    assertInvalidUserName(" dev");
    assertInvalidUserName("dev ");
    assertInvalidUserName("dev\nuser");
    assertInvalidUserName("dev\u0000x");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", null, "/home/dev", "Note"));

    // homeDirectory 校验：空白、周边空白、换行、控制字符、非绝对路径
    assertInvalidHomeDirectory("");
    assertInvalidHomeDirectory(" ");
    assertInvalidHomeDirectory(" /home/dev");
    assertInvalidHomeDirectory("/home/dev ");
    assertInvalidHomeDirectory("/home/dev\n");
    assertInvalidHomeDirectory("/home/dev\u0000x");
    assertInvalidHomeDirectory("home/dev");
    assertInvalidHomeDirectory("./home/dev");
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvironmentInfo(DaemonOperatingSystem.LINUX, "UTC", "dev", null, "Note"));
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }

  private void assertInvalidEnvironment(
      String operatingSystem, String timeZone, String userName, String homeDirectory, String note) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", operatingSystem);
    environment.put("timeZone", timeZone);
    environment.put("userName", userName);
    environment.put("homeDirectory", homeDirectory);
    environment.put("note", note);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidUserName(String userName) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", "linux");
    environment.put("timeZone", "UTC");
    environment.put("userName", userName);
    environment.put("homeDirectory", "/home/dev");
    environment.put("note", "Linux environment.");
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidHomeDirectory(String homeDirectory) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", "linux");
    environment.put("timeZone", "UTC");
    environment.put("userName", "dev");
    environment.put("homeDirectory", homeDirectory);
    environment.put("note", "Linux environment.");
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private static String environmentWithout(String omitted) {
    StringBuilder sb = new StringBuilder("\"environment\":{");
    boolean first = true;
    for (String field :
        new String[] {"operatingSystem", "timeZone", "userName", "homeDirectory", "note"}) {
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
    for (String field :
        new String[] {"operatingSystem", "timeZone", "userName", "homeDirectory", "note"}) {
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
      case "userName" -> "dev";
      case "homeDirectory" -> "/home/dev";
      case "note" -> "Linux environment.";
      default -> throw new IllegalArgumentException(field);
    };
  }
}
