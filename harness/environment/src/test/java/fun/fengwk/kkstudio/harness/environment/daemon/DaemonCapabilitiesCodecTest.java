package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** READY capabilities codec 的严格版本、宿主 metadata 形状与未知字段拒绝契约。 */
class DaemonCapabilitiesCodecTest {

  private static final String DAEMON_VERSION = "1.0.9";
  private static final String TEMP_DIRECTORY = "/tmp/kk-studio";
  private static final String ARCHITECTURE = "amd64";
  private static final DaemonEnvironmentInfo ENVIRONMENT =
      new DaemonEnvironmentInfo(
          DaemonOperatingSystem.LINUX,
          "Asia/Shanghai",
          "dev",
          "/home/dev",
          "Linux environment.",
          TEMP_DIRECTORY,
          ARCHITECTURE);
  private static final String ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
          + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
          + "\"tempDirectory\":\"/tmp/kk-studio\",\"architecture\":\"amd64\"}";
  private static final String DAEMON_VERSION_JSON = "\"daemonVersion\":\"" + DAEMON_VERSION + "\",";
  private static final String UNKNOWN_ARCHITECTURE_ENVIRONMENT_JSON =
      "\"environment\":{\"operatingSystem\":\"linux\","
          + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
          + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
          + "\"tempDirectory\":\"/tmp/kk-studio\",\"architecture\":null}";

  private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

  private final DaemonCapabilitiesCodec codec = new DaemonCapabilitiesCodec();

  /** READY 的唯一 wire 形状必须精确往返：只有 version、daemonVersion 与 environment 三个顶层字段。 */
  @Test
  void roundTripsHostMetadataAndDaemonVersion() {
    DaemonCapabilities original =
        new DaemonCapabilities(DaemonCapabilities.VERSION, DAEMON_VERSION, ENVIRONMENT);

    String encoded = codec.encode(original);

    assertEquals(original, codec.decode(encoded));
    assertEquals("{\"version\":6," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "}", encoded);
  }

  /** 明确未知的宿主架构必须显式编码为 JSON null 并原样往返，不能被省略或被填造成某个取值。 */
  @Test
  void roundTripsExplicitlyUnknownArchitecture() {
    DaemonEnvironmentInfo unknown =
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.LINUX,
            "Asia/Shanghai",
            "dev",
            "/home/dev",
            "Linux environment.",
            TEMP_DIRECTORY,
            null);
    DaemonCapabilities original =
        new DaemonCapabilities(DaemonCapabilities.VERSION, DAEMON_VERSION, unknown);

    String encoded = codec.encode(original);

    assertEquals(
        "{\"version\":6," + DAEMON_VERSION_JSON + UNKNOWN_ARCHITECTURE_ENVIRONMENT_JSON + "}",
        encoded);
    assertNull(codec.decode(encoded).environment().architecture());
    assertEquals(original, codec.decode(encoded));
  }

  /** 旧 MAIN 下发的 V6 metadata 保留原生 architecture 字符串，必须原样可读与往返，不因字段顺序或取值不同而丢失。 */
  @Test
  void readsMainV6NativeArchitectureMetadata() {
    for (String architecture : new String[] {"amd64", "arm64"}) {
      String payload =
          "{\"version\":6,"
              + DAEMON_VERSION_JSON
              + "\"environment\":{\"operatingSystem\":\"linux\",\"architecture\":\""
              + architecture
              + "\",\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
              + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
              + "\"tempDirectory\":\"/tmp/kk-studio\"}}";

      DaemonCapabilities capabilities = codec.decode(payload);

      assertEquals(architecture, capabilities.environment().architecture());
      assertEquals(
          architecture, codec.decode(codec.encode(capabilities)).environment().architecture());
    }
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
            "C:\\Users\\dev\\AppData\\Local\\Temp",
            "amd64");

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
                TEMP_DIRECTORY,
                ARCHITECTURE));
  }

  /** 历史上的 sourceSetVersion / skillSources 都是未知字段，必须在 wire 边界拒绝而不是忽略。 */
  @Test
  void rejectsLegacySkillInventoryFields() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + DAEMON_VERSION_JSON
                    + ENVIRONMENT_JSON
                    + ",\"sourceSetVersion\":0,\"skillSources\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + DAEMON_VERSION_JSON
                    + ENVIRONMENT_JSON
                    + ",\"skillSources\":[{\"sourceId\":\"x\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
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
                    "{\"version\":6,"
                        + DAEMON_VERSION_JSON
                        + "\"environment\":{\"operatingSystem\":\"linux\","
                        + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
                        + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
                        + "\"tempDirectory\":\"/tmp/kk-studio\","
                        + "\"architecture\":\"amd64\","
                        + "\"rootPath\":\"/home/dev\"}}"));
    assertTrue(
        error.getMessage().contains("unexpected READY environment field: rootPath"),
        error.getMessage());
  }

  /** 旧 V4 payload 不再被当前 codec 接受：没有运行期双读、旧形状解码或兼容构造器。 */
  @Test
  void rejectsLegacyVersion4Payload() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":4,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
                    + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
                    + "\"tempDirectory\":\"/tmp/kk-studio\"}}"));
  }

  /** 非当前版本、非整数版本与非法 environment 形状都按协议错误拒绝。 */
  @Test
  void rejectsUnsupportedVersionsAndShapes() {
    for (String version :
        new String[] {"0", "1", "2", "3", "4", "5", "\"6\"", "null", "true", "{}", "[]"}) {
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
        () -> codec.decode("{\"version\":6," + ENVIRONMENT_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":6," + DAEMON_VERSION_JSON + "\"environment\":null}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"version\":6}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":6," + DAEMON_VERSION_JSON + "\"environment\":[]}"));
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
                  "{\"version\":6,\"daemonVersion\":\"" + invalid + "\"," + ENVIRONMENT_JSON + "}"),
          invalid);
    }
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"daemonVersion\":\""
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
                "{\"version\":6," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + ",\"secret\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decode("{\"version\":6," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "} x"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"Linux environment.\",\"tempDirectory\":\"/tmp/kk-studio\","
                    + "\"architecture\":\"amd64\",\"extra\":\"x\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"workingDirectory\":\"/workspace\",\"timeZone\":\"UTC\","
                    + "\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"Linux environment.\",\"tempDirectory\":\"/tmp/kk-studio\","
                    + "\"architecture\":\"amd64\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"one\",\"note\":\"two\",\"tempDirectory\":\"/tmp/kk-studio\","
                    + "\"architecture\":\"amd64\"}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,\"version\":6," + DAEMON_VERSION_JSON + ENVIRONMENT_JSON + "}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + DAEMON_VERSION_JSON
                    + DAEMON_VERSION_JSON
                    + ENVIRONMENT_JSON
                    + "}"));
  }

  /** environment 的七个字段缺一不可：除 architecture 允许明确 null 外都是 non-blank 文本。 */
  @Test
  void rejectsMissingEnvironmentFields() {
    for (String omitted :
        new String[] {
          "operatingSystem",
          "timeZone",
          "userName",
          "homeDirectory",
          "note",
          "tempDirectory",
          "architecture"
        }) {
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":6," + DAEMON_VERSION_JSON + environmentWithout(omitted) + "}"),
          omitted);
      assertThrows(
          DaemonProtocolException.class,
          () ->
              codec.decode(
                  "{\"version\":6," + DAEMON_VERSION_JSON + environmentBlank(omitted) + "}"),
          omitted);
    }
  }

  /** architecture 只接受明确 null 或受约束的标识字符串；错误类型、非法字符串与超长值都按协议错误拒绝且不回显值。 */
  @Test
  void rejectsInvalidArchitecture() {
    // 错误类型：数字、布尔、对象、数组都是协议错误
    for (String invalid : new String[] {"1", "true", "{}", "[]"}) {
      assertInvalidArchitecture(invalid);
    }
    // 空白与周边空白：blank 不能充当未知，必须显式 null
    assertInvalidArchitecture("\"\"");
    assertInvalidArchitecture("\"   \"");
    assertInvalidArchitecture("\" amd64\"");
    assertInvalidArchitecture("\"amd64 \"");
    // 控制字符与行分隔符：identity 必须是单行
    assertInvalidArchitecture("\"am\\u0064\\u0000b\"");
    assertInvalidArchitecture("\"amd64\\n\"");
    assertInvalidArchitecture("\"amd64\\u2028x\"");
    // 超长标识
    assertInvalidArchitecture(
        "\"" + "x".repeat(DaemonEnvironmentInfo.MAX_ARCHITECTURE_CHARS + 1) + "\"");

    // 录层校验与 wire 边界共享同一约束；拒绝信息不回显被拒绝的值
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.LINUX,
                    "UTC",
                    "dev",
                    "/home/dev",
                    "Note",
                    TEMP_DIRECTORY,
                    "  "));
    assertTrue(error.getMessage().contains("architecture"), error.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "/home/dev",
                "Note",
                TEMP_DIRECTORY,
                "x".repeat(DaemonEnvironmentInfo.MAX_ARCHITECTURE_CHARS + 1)));
    assertEquals(
        "x86_64",
        new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "/home/dev",
                "Note",
                TEMP_DIRECTORY,
                "x86_64")
            .architecture());
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
                TEMP_DIRECTORY,
                ARCHITECTURE));

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
                DaemonOperatingSystem.LINUX,
                "UTC",
                null,
                "/home/dev",
                "Note",
                TEMP_DIRECTORY,
                ARCHITECTURE));

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
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                null,
                "Note",
                TEMP_DIRECTORY,
                ARCHITECTURE));

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
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "/home/dev",
                "Note",
                null,
                ARCHITECTURE));
    assertEquals(
        "C:\\Temp",
        new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "UTC",
                "dev",
                "/home/dev",
                "Note",
                "C:\\Temp",
                ARCHITECTURE)
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
    environment.put("architecture", ARCHITECTURE);
    assertThrows(DaemonProtocolException.class, () -> codec.decode(root.toString()));
  }

  private void assertInvalidArchitecture(String rawArchitectureJson) {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decode(
                "{\"version\":6,"
                    + DAEMON_VERSION_JSON
                    + "\"environment\":{\"operatingSystem\":\"linux\","
                    + "\"timeZone\":\"UTC\",\"userName\":\"dev\",\"homeDirectory\":\"/home/dev\","
                    + "\"note\":\"Linux environment.\",\"tempDirectory\":\"/tmp/kk-studio\","
                    + "\"architecture\":"
                    + rawArchitectureJson
                    + "}}"),
        rawArchitectureJson);
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
    environment.put("architecture", ARCHITECTURE);
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
    environment.put("architecture", ARCHITECTURE);
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
    environment.put("architecture", ARCHITECTURE);
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
      "operatingSystem",
      "timeZone",
      "userName",
      "homeDirectory",
      "note",
      "tempDirectory",
      "architecture"
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
      case "architecture" -> ARCHITECTURE;
      default -> throw new IllegalArgumentException(field);
    };
  }
}
