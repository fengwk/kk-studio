package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** READY capabilities codec 的严格版本、宿主 metadata 形状与未知字段拒绝契约。 */
class DaemonCapabilitiesCodecTest {

  private static final String DAEMON_VERSION = "1.0.9";
  private static final String TEMP_DIRECTORY = "/tmp/kk-studio";
  private static final DaemonEnvironmentInfo ENVIRONMENT =
      new DaemonEnvironmentInfo(
          DaemonOperatingSystem.LINUX,
          "Asia/Shanghai",
          "dev",
          "/home/dev",
          "Linux environment.",
          TEMP_DIRECTORY);
  private static final String ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
          + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
          + "\"tempDirectory\":\"/tmp/kk-studio\"}";
  private static final String DAEMON_VERSION_JSON = "\"daemonVersion\":\"" + DAEMON_VERSION + "\",";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final DaemonCapabilitiesCodec codec = new DaemonCapabilitiesCodec();

  /** READY 的唯一 wire 形状必须精确往返：只有 version、daemonVersion 与 environment 三个顶层字段。 */
  @Test
  void roundTripsHostMetadataAndDaemonVersion() {
    DaemonCapabilities original =
        new DaemonCapabilities(DaemonCapabilities.VERSION, DAEMON_VERSION, ENVIRONMENT);

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals("{\"version\":4," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "}", encoded);
  }

  /** Windows HOME 必须按目标 Daemon 语法校验，不能被 Linux Platform 的 Path 语义误拒绝。 */
  @Test
  void acceptsWindowsHomeDirectoryOnAnyPlatformHost() {
    DaemonEnvironmentInfo windows =
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.WINDOWS,
            "UTC",
            "dev",
            "C:\\Users\\dev",
            "Windows environment.",
            "C:\\Users\\dev\\AppData\\Local\\Temp");

    assertEquals("C:\\Users\\dev", windows.homeDirectory());
    assertEquals(
        windows,
        codec
            .decode(
                codec.encode(
                    new DaemonCapabilities(DaemonCapabilities.VERSION, DAEMON_VERSION, windows)))
            .environment());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "C:\\Users\\dev",
                "Linux environment.",
                TEMP_DIRECTORY));
  }

  /** 历史上的 sourceSetVersion / skillSources 都是未知字段，必须在 wire 边界拒绝而不是忽略。 */
  @Test
  void rejectsLegacySkillInventoryFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + ENVIRONMENT_JSON
                    + ",\"skillSources\":[{\"sourceId\":\"x\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skills\":[]}"));
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
                    "{\"version\":4,"
                        + DAEMON_VERSION_JSON
                        + "\"environment\":{\"operatingSystem\":\"linux\","
                        + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
                        + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
                        + "\"tempDirectory\":\"/tmp/kk-studio\","
                        + "\"rootPath\":\"/home/dev\"}}"));
    assertTrue(
        error.getMessage().contains("unexpected READY environment field: rootPath"),
        error.getMessage());
  }

  /** 非当前版本、非整数版本与非法 environment 形状都按协议错误拒绝。 */
  @Test
  void rejectsUnsupportedVersionsAndShapes() {
    for (String version :
        new String[] {"0", "1", "2", "3", "5", "\"4\"", "null", "true", "{}", "[]"}) {
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":" + version + "," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "}"),
          version);
    }
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{" + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":4," + ENVIRONMENT_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":4," + DAEMON_VERSION_JSON + "\"environment\":null}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"version\":4}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":4," + DAEMON_VERSION_JSON + "\"environment\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonCapabilities(2, DAEMON_VERSION, ENVIRONMENT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonCapabilities(DaemonCapabilities.VERSION + 1, DAEMON_VERSION, ENVIRONMENT));
    assertThrows(
        NullPointerException.class,
        () -> new DaemonCapabilities(DaemonCapabilities.VERSION, DAEMON_VERSION, null));
  }

  /** daemonVersion 是构建事实，必须是受版本字符约束的单行文本。 */
  @Test
  void rejectsInvalidDaemonVersion() {
    for (String invalid :
        new String[] {"", " ", "1.0.9 ", " 1.0.9", "1 0", "1/0", "a\nb", "a\u0000b"}) {
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":4,\"daemonVersion\":\"" + invalid + "\"," + ENVIRONMENT_JSON + "}"),
          invalid);
    }
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"daemonVersion\":\""
                    + "x".repeat(DaemonCapabilities.MAX_DAEMON_VERSION_CHARS + 1)
                    + "\","
                    + ENVIRONMENT_JSON
                    + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonCapabilities(DaemonCapabilities.VERSION, " ", ENVIRONMENT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonCapabilities(DaemonCapabilities.VERSION, null, ENVIRONMENT));
  }

  /** 顶层与 environment 都只接受固定字段：未知字段、重复键与尾随内容一律拒绝。 */
  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + ",\"secret\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":4," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "} x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"Linux environment.\",\"tempDirectory\":\"/tmp/kk-studio\","
                    + "\"extra\":\"x\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"Linux environment.\",\"tempDirectory\":\"/tmp/kk-studio\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"one\",\"note\":\"two\",\"tempDirectory\":\"/tmp/kk-studio\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,\"version\":4," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + DAEMON_VERSION_JSON
                    + ENVIRONMENT_JSON
                    + "}"));
  }

  /** environment 的六个字段缺一不可，且都必须是 non-blank 文本。 */
  @Test
  void rejectsMissingEnvironmentFields() {
    for (String omitted :
        new String[] {
          "operatingSystem", "timeZone", "userName", "homeDirectory", "note", "tempDirectory"
        }) {
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":4," + DAEMON_VERSION_JSON + environmentWithout(omitted) + "}"),
          omitted);
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":4," + DAEMON_VERSION_JSON + environmentBlank(omitted) + "}"),
          omitted);
    }
  }

  @Test
  void rejectsInvalidEnvironmentMetadata() {
    assertInvalidEnvironment(
        "plan9", "UTC", "dev", "/home/dev", "Local environment.", TEMP_DIRECTORY);
    assertInvalidEnvironment(
        "linux", "Not/AZone", "dev", "/home/dev", "Linux environment.", TEMP_DIRECTORY);
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "", TEMP_DIRECTORY);
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", " ", TEMP_DIRECTORY);
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", " leading", TEMP_DIRECTORY);
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "trailing ", TEMP_DIRECTORY);
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "first\nsecond", TEMP_DIRECTORY);
    assertInvalidEnvironment(
        "linux", "UTC", "dev", "/home/dev", "first\u2028second", TEMP_DIRECTORY);
    assertInvalidEnvironment(
        "linux", "UTC", "dev", "/home/dev", "control\u0000value", TEMP_DIRECTORY);
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "Linux environment.", "relative");
    assertInvalidEnvironment("linux", "UTC", "dev", "/home/dev", "Linux environment.", "");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "/home/dev",
                "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1),
                TEMP_DIRECTORY));

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
                DaemonOperatingSystem.LINUX, "UTC", null, "/home/dev", "Note", TEMP_DIRECTORY));

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
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", null, "Note", TEMP_DIRECTORY));

    // tempDirectory 校验：空白、周边空白、换行、控制字符、非绝对路径；跨 OS 绝对路径都接受
    assertInvalidTempDirectory("");
    assertInvalidTempDirectory(" ");
    assertInvalidTempDirectory(" /tmp/kk-studio");
    assertInvalidTempDirectory("/tmp/kk-studio ");
    assertInvalidTempDirectory("/tmp/kk-studio\n");
    assertInvalidTempDirectory("/tmp/kk-studio\u0000x");
    assertInvalidTempDirectory("tmp/kk-studio");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "Note", null));
    assertEquals(
        "C:\\Temp",
        new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "Note", "C:\\Temp")
            .tempDirectory());
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }

  private void assertInvalidEnvironment(
      String operatingSystem,
      String timeZone,
      String userName,
      String homeDirectory,
      String note,
      String tempDirectory) {
    ObjectNode root = readyRoot();
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", operatingSystem);
    environment.put("timeZone", timeZone);
    environment.put("userName", userName);
    environment.put("homeDirectory", homeDirectory);
    environment.put("note", note);
    environment.put("tempDirectory", tempDirectory);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidUserName(String userName) {
    ObjectNode root = readyRoot();
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", "linux");
    environment.put("timeZone", "UTC");
    environment.put("userName", userName);
    environment.put("homeDirectory", "/home/dev");
    environment.put("note", "Linux environment.");
    environment.put("tempDirectory", TEMP_DIRECTORY);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidHomeDirectory(String homeDirectory) {
    ObjectNode root = readyRoot();
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", "linux");
    environment.put("timeZone", "UTC");
    environment.put("userName", "dev");
    environment.put("homeDirectory", homeDirectory);
    environment.put("note", "Linux environment.");
    environment.put("tempDirectory", TEMP_DIRECTORY);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidTempDirectory(String tempDirectory) {
    ObjectNode root = readyRoot();
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", "linux");
    environment.put("timeZone", "UTC");
    environment.put("userName", "dev");
    environment.put("homeDirectory", "/home/dev");
    environment.put("note", "Linux environment.");
    environment.put("tempDirectory", tempDirectory);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private static ObjectNode readyRoot() {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", DaemonCapabilities.VERSION);
    root.put("daemonVersion", DAEMON_VERSION);
    return root;
  }

  private static String environmentWithout(String omitted) {
    StringBuilder sb = new StringBuilder("\"environment\":{");
    boolean first = true;
    for (String field : environmentFields()) {
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
    for (String field : environmentFields()) {
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

  private static String[] environmentFields() {
    return new String[] {
      "operatingSystem", "timeZone", "userName", "homeDirectory", "note", "tempDirectory"
    };
  }

  private static String valueOf(String field) {
    return switch (field) {
      case "operatingSystem" -> "linux";
      case "timeZone" -> "UTC";
      case "userName" -> "dev";
      case "homeDirectory" -> "/home/dev";
      case "note" -> "Linux environment.";
      case "tempDirectory" -> TEMP_DIRECTORY;
      default -> throw new IllegalArgumentException(field);
    };
  }
}
