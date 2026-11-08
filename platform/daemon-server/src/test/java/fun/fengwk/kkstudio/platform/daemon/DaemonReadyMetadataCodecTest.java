package fun.fengwk.kkstudio.platform.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** READY metadata codec 的严格版本、宿主 metadata 形状与未知字段拒绝契约。 */
class DaemonReadyMetadataCodecTest {

  private static final String DAEMON_VERSION = "1.0.9";
  private static final String TEMP_DIRECTORY = "/tmp/kk-studio";
  private static final String ARCHITECTURE = "amd64";
  private static final DaemonHostInfo HOST_INFO =
      new DaemonHostInfo(
          DaemonOperatingSystem.LINUX,
          "Asia/Shanghai",
          "dev",
          "/home/dev",
          TEMP_DIRECTORY,
          ARCHITECTURE);
  private static final String HOST_INFO_JSON =
      "\"hostInfo\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
          + "\"homeDirectory\":\"/home/dev\","
          + "\"tempDirectory\":\"/tmp/kk-studio\",\"architecture\":\"amd64\"}";
  private static final String DAEMON_VERSION_JSON = "\"daemonVersion\":\"" + DAEMON_VERSION + "\",";
  private static final String UNKNOWN_ARCHITECTURE_HOST_INFO_JSON =
      "\"hostInfo\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
          + "\"homeDirectory\":\"/home/dev\","
          + "\"tempDirectory\":\"/tmp/kk-studio\",\"architecture\":null}";

  private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

  private final DaemonReadyMetadataCodec codec = new DaemonReadyMetadataCodec();

  /** READY 的唯一 wire 形状必须精确往返：只有 daemonVersion 与 hostInfo 两个顶层字段。 */
  @Test
  void roundTripsHostMetadataAndDaemonVersion() {
    DaemonReadyMetadata original = new DaemonReadyMetadata(DAEMON_VERSION, HOST_INFO);

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals("{" + DAEMON_VERSION_JSON + HOST_INFO_JSON + "}", encoded);
  }

  /** 明确未知的宿主架构必须显式编码为 JSON null 并原样往返，不能被省略或被填造成某个取值。 */
  @Test
  void roundTripsExplicitlyUnknownArchitecture() {
    DaemonHostInfo unknown =
        new DaemonHostInfo(
            DaemonOperatingSystem.LINUX, "Asia/Shanghai", "dev", "/home/dev", TEMP_DIRECTORY, null);
    DaemonReadyMetadata original = new DaemonReadyMetadata(DAEMON_VERSION, unknown);

    String encoded = codec.encode(original);

    assertEquals("{" + DAEMON_VERSION_JSON + UNKNOWN_ARCHITECTURE_HOST_INFO_JSON + "}", encoded);
    assertNull(codec.decode(encoded).hostInfo().architecture());
    assertEquals(original, codec.decode(encoded));
  }

  /** 宿主架构支持 amd64 与 arm64 等标准架构取值并精确往返。 */
  @Test
  void roundTripsDistinctArchitectures() {
    for (String architecture : new String[] {"amd64", "arm64"}) {
      DaemonHostInfo info =
          new DaemonHostInfo(
              DaemonOperatingSystem.LINUX,
              "Asia/Shanghai",
              "dev",
              "/home/dev",
              TEMP_DIRECTORY,
              architecture);
      DaemonReadyMetadata metadata = new DaemonReadyMetadata(DAEMON_VERSION, info);
      DaemonReadyMetadata roundTripped = codec.decode(codec.encode(metadata));
      assertEquals(architecture, roundTripped.hostInfo().architecture());
      assertEquals(metadata, roundTripped);
    }
  }

  /** Windows HOME 必须按目标 Daemon 语法校验，不能被 Linux Platform 的 Path 语义误拒绝。 */
  @Test
  void acceptsWindowsHomeDirectoryOnAnyPlatformHost() {
    DaemonHostInfo windows =
        new DaemonHostInfo(
            DaemonOperatingSystem.WINDOWS,
            "UTC",
            "dev",
            "C:\\Users\\dev",
            "C:\\Users\\dev\\AppData\\Local\\Temp",
            "amd64");

    assertEquals("C:\\Users\\dev", windows.homeDirectory());
    assertEquals(
        windows,
        codec.decode(codec.encode(new DaemonReadyMetadata(DAEMON_VERSION, windows))).hostInfo());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonHostInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "C:\\Users\\dev",
                TEMP_DIRECTORY,
                ARCHITECTURE));
  }

  /** 未知字段、缺失字段与非对象 hostInfo 都按协议错误拒绝。 */
  @Test
  void rejectsUnsupportedShapes() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{" + HOST_INFO_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{" + DAEMON_VERSION_JSON + "\"hostInfo\":null}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{" + DAEMON_VERSION_JSON + "\"hostInfo\":[]}"));
    assertThrows(NullPointerException.class, () -> new DaemonReadyMetadata(DAEMON_VERSION, null));
  }

  /** daemonVersion 是构建事实，必须是受版本字符约束的单行文本。 */
  @Test
  void rejectsInvalidDaemonVersion() {
    for (String invalid :
        new String[] {"", " ", "1.0.9 ", " 1.0.9", "1 0", "1/0", "a\nb", "a\u0000b"}) {
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{\"daemonVersion\":\"" + invalid + "\"," + HOST_INFO_JSON + "}"),
          invalid);
    }
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"daemonVersion\":\""
                    + "x".repeat(DaemonReadyMetadata.MAX_DAEMON_VERSION_CHARS + 1)
                    + "\","
                    + HOST_INFO_JSON
                    + "}"));
    assertThrows(IllegalArgumentException.class, () -> new DaemonReadyMetadata(" ", HOST_INFO));
    assertThrows(IllegalArgumentException.class, () -> new DaemonReadyMetadata(null, HOST_INFO));
  }

  /** 顶层与 hostInfo 都只接受固定字段：未知字段、重复键与尾随内容一律拒绝。 */
  @Test
  void rejectsUnknownDuplicateAndTrailingFields() {
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{" + DAEMON_VERSION_JSON + HOST_INFO_JSON + ",\"secret\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{" + DAEMON_VERSION_JSON + HOST_INFO_JSON + "} x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{"
                    + DAEMON_VERSION_JSON
                    + "\"hostInfo\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"tempDirectory\":\"/tmp/kk-studio\",\"architecture\":\"amd64\","
                    + "\"extra\":\"x\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{"
                    + DAEMON_VERSION_JSON
                    + "\"hostInfo\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"tempDirectory\":\"/tmp/kk-studio\",\"architecture\":\"amd64\","
                    + "\"architecture\":\"arm64\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{" + DAEMON_VERSION_JSON + DAEMON_VERSION_JSON + HOST_INFO_JSON + "}"));
  }

  /** hostInfo 的六个字段缺一不可：除 architecture 允许明确 null 外都是 non-blank 文本。 */
  @Test
  void rejectsMissingHostInfoFields() {
    for (String omitted :
        new String[] {
          "operatingSystem",
          "timeZone",
          "userName",
          "homeDirectory",
          "tempDirectory",
          "architecture"
        }) {
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{" + DAEMON_VERSION_JSON + hostInfoWithout(omitted) + "}"),
          omitted);
      assertThrows(
          DaemonProtocolException.class,
          () -> codec.decode("{" + DAEMON_VERSION_JSON + hostInfoBlank(omitted) + "}"),
          omitted);
    }
  }

  /** architecture 只接受明确 null 或受约束的标识字符串；错误类型、非法字符串与超长值都按协议错误拒绝。 */
  @Test
  void rejectsInvalidArchitecture() {
    for (String invalid : new String[] {"1", "true", "{}", "[]"}) {
      assertInvalidArchitecture(invalid);
    }
    assertInvalidArchitecture("\"\"");
    assertInvalidArchitecture("\"   \"");
    assertInvalidArchitecture("\" amd64\"");
    assertInvalidArchitecture("\"amd64 \"");
    assertInvalidArchitecture("\"am\\u0064\\u0000b\"");
    assertInvalidArchitecture("\"amd64\\n\"");
    assertInvalidArchitecture("\"amd64\\u2028x\"");
    assertInvalidArchitecture("\"" + "x".repeat(DaemonHostInfo.MAX_ARCHITECTURE_CHARS + 1) + "\"");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new DaemonHostInfo(
                    DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", TEMP_DIRECTORY, "  "));
    assertTrue(error.getMessage().contains("architecture"), error.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonHostInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "/home/dev",
                TEMP_DIRECTORY,
                "x".repeat(DaemonHostInfo.MAX_ARCHITECTURE_CHARS + 1)));
    assertEquals(
        "x86_64",
        new DaemonHostInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", TEMP_DIRECTORY, "x86_64")
            .architecture());
  }

  @Test
  void rejectsInvalidHostMetadata() {
    assertInvalidHostInfo("plan9", "UTC", "dev", "/home/dev", TEMP_DIRECTORY);
    assertInvalidHostInfo("linux", "Not/AZone", "dev", "/home/dev", TEMP_DIRECTORY);
    assertInvalidHostInfo("linux", "UTC", "dev", "/home/dev", "relative");
    assertInvalidHostInfo("linux", "UTC", "dev", "/home/dev", "");

    assertInvalidUserName("");
    assertInvalidUserName(" ");
    assertInvalidUserName(" dev");
    assertInvalidUserName("dev ");
    assertInvalidUserName("dev\nuser");
    assertInvalidUserName("dev\u0000x");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonHostInfo(
                DaemonOperatingSystem.LINUX, "UTC", null, "/home/dev", TEMP_DIRECTORY, "amd64"));

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
            new DaemonHostInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", null, TEMP_DIRECTORY, "amd64"));

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
            new DaemonHostInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", null, "amd64"));
    assertEquals(
        "C:\\Temp",
        new DaemonHostInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "C:\\Temp", ARCHITECTURE)
            .tempDirectory());
  }

  @Test
  void rejectsMalformedPayload() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("not-json"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("null"));
  }

  private void assertInvalidHostInfo(
      String operatingSystem,
      String timeZone,
      String userName,
      String homeDirectory,
      String tempDirectory) {
    ObjectNode root = readyRoot();
    ObjectNode hostInfo = root.putObject("hostInfo");
    hostInfo.put("operatingSystem", operatingSystem);
    hostInfo.put("timeZone", timeZone);
    hostInfo.put("userName", userName);
    hostInfo.put("homeDirectory", homeDirectory);
    hostInfo.put("tempDirectory", tempDirectory);
    hostInfo.put("architecture", ARCHITECTURE);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidArchitecture(String rawArchitectureJson) {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{"
                    + DAEMON_VERSION_JSON
                    + "\"hostInfo\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"tempDirectory\":\"/tmp/kk-studio\",\"architecture\":"
                    + rawArchitectureJson
                    + "}}"),
        rawArchitectureJson);
  }

  private void assertInvalidUserName(String userName) {
    ObjectNode root = readyRoot();
    ObjectNode hostInfo = root.putObject("hostInfo");
    hostInfo.put("operatingSystem", "linux");
    hostInfo.put("timeZone", "UTC");
    hostInfo.put("userName", userName);
    hostInfo.put("homeDirectory", "/home/dev");
    hostInfo.put("tempDirectory", TEMP_DIRECTORY);
    hostInfo.put("architecture", ARCHITECTURE);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidHomeDirectory(String homeDirectory) {
    ObjectNode root = readyRoot();
    ObjectNode hostInfo = root.putObject("hostInfo");
    hostInfo.put("operatingSystem", "linux");
    hostInfo.put("timeZone", "UTC");
    hostInfo.put("userName", "dev");
    hostInfo.put("homeDirectory", homeDirectory);
    hostInfo.put("tempDirectory", TEMP_DIRECTORY);
    hostInfo.put("architecture", ARCHITECTURE);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidTempDirectory(String tempDirectory) {
    ObjectNode root = readyRoot();
    ObjectNode hostInfo = root.putObject("hostInfo");
    hostInfo.put("operatingSystem", "linux");
    hostInfo.put("timeZone", "UTC");
    hostInfo.put("userName", "dev");
    hostInfo.put("homeDirectory", "/home/dev");
    hostInfo.put("tempDirectory", tempDirectory);
    hostInfo.put("architecture", ARCHITECTURE);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private static ObjectNode readyRoot() {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("daemonVersion", DAEMON_VERSION);
    return root;
  }

  private static String hostInfoWithout(String omitted) {
    StringBuilder sb = new StringBuilder("\"hostInfo\":{");
    boolean first = true;
    for (String field : hostInfoFields()) {
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

  private static String hostInfoBlank(String blankField) {
    StringBuilder sb = new StringBuilder("\"hostInfo\":{");
    boolean first = true;
    for (String field : hostInfoFields()) {
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

  private static String[] hostInfoFields() {
    return new String[] {
      "operatingSystem", "timeZone", "userName", "homeDirectory", "tempDirectory", "architecture"
    };
  }

  private static String valueOf(String field) {
    return switch (field) {
      case "operatingSystem" -> "linux";
      case "timeZone" -> "UTC";
      case "userName" -> "dev";
      case "homeDirectory" -> "/home/dev";
      case "tempDirectory" -> TEMP_DIRECTORY;
      case "architecture" -> ARCHITECTURE;
      default -> throw new IllegalArgumentException(field);
    };
  }
}
