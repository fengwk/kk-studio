package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfiguration;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;

import java.util.List;
import java.util.Map;

/** 安装输入边界严格类型、宿主路径和深层配置校验，且保存序列化可往返。 */
class EnvironmentInstallConfigsTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void canonicalConfigRoundtripsForEachOperatingSystem() {
    for (String os : List.of("linux", "macos", "windows")) {
      var value =
          EnvironmentInstallConfigs.parse(
              json.valueToTree(
                  Map.of(
                      "operatingSystem",
                      os,
                      "daemon",
                      Map.of("studioUrl", "https://studio.example.com/"))));
      assertEquals("https://studio.example.com", value.getDaemon().getStudioUrl());
      assertEquals(value, EnvironmentInstallConfigs.read(EnvironmentInstallConfigs.write(value)));
    }
    assertNull(EnvironmentInstallConfigs.read(null));
    assertNull(EnvironmentInstallConfigs.write(null));
  }

  @Test
  void rejectsUnknownJavaHomeInvalidOperatingSystemAndNull() {
    assertThrows(
        AiValidationException.class,
        () ->
            EnvironmentInstallConfigs.parse(
                json.valueToTree(
                    Map.of(
                        "operatingSystem",
                        "linux",
                        "javaHome",
                        "/opt/jdk21",
                        "daemon",
                        Map.of("studioUrl", "https://studio.example.com")))));
    for (Object os : List.of("wsl", "", 123, true)) {
      assertThrows(
          AiValidationException.class,
          () ->
              EnvironmentInstallConfigs.parse(
                  json.valueToTree(
                      Map.of(
                          "operatingSystem",
                          os,
                          "daemon",
                          Map.of("studioUrl", "https://studio.example.com")))));
    }
    assertThrows(AiValidationException.class, () -> EnvironmentInstallConfigs.validate(null));
  }

  @Test
  void strictRequestAndDeepKnownInvalidFieldsAreHardErrors() throws Exception {
    for (String raw :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"expectedVersion\":0,\"installConfig\":{}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{},\"unknown\":1}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"unknown\":1,\"daemon\":{\"studioUrl\":\"https://studio.example.com\"}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com/path\"}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\",\"lsp\":{\"servers\":{}}}}}")) {
      assertThrows(
          AiValidationException.class,
          () -> EnvironmentInstallConfigs.parseUpdate(json.readTree(raw)));
    }
  }

  /** 边界必须原样保留未知字段与标量类型：成功请求解析，嵌套未知字段/错误类型一律拒绝，不静默丢弃或强转。 */
  @Test
  void parseUpdateAcceptsValidBodyAndRejectsUnknownNestedFieldsAndTypeCoercion() throws Exception {
    var parsed =
        EnvironmentInstallConfigs.parseUpdate(
            json.readTree(
                "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\"}}}"));
    assertEquals("0", parsed.getExpectedVersion());
    assertEquals("linux", parsed.getInstallConfig().getOperatingSystem());

    for (String raw :
        List.of(
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"javaHome\":\"/opt/jdk\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\"}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\",\"unknown\":true}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"unknown\":1,\"daemon\":{\"studioUrl\":\"https://studio.example.com\"}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\",\"lsp\":{\"servers\":{\"jdtls\":{\"command\":[\"jdtls\"],\"extensions\":[\".java\"],\"unknown\":true}}}}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":\"string\"}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\",\"lsp\":{\"servers\":[]}}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\",\"note\":42}}}")) {
      assertThrows(
          AiValidationException.class,
          () -> EnvironmentInstallConfigs.parseUpdate(json.readTree(raw)));
    }
  }

  /** validate 也覆盖程序化构造的模型；read 遇到损坏持久化 JSON 必须失败而非静默返回空配置。 */
  @Test
  void validateRejectsProgrammaticModelAndReadRejectsCorruptPersistedJson() {
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallConfigs.validate(new EnvironmentInstallConfigDTO()));
    DaemonConfiguration invalidDaemon = new DaemonConfiguration();
    invalidDaemon.setStudioUrl("not-an-origin");
    EnvironmentInstallConfigDTO bad = new EnvironmentInstallConfigDTO();
    bad.setOperatingSystem("linux");
    bad.setDaemon(invalidDaemon);
    assertThrows(AiValidationException.class, () -> EnvironmentInstallConfigs.validate(bad));
    assertThrows(IllegalStateException.class, () -> EnvironmentInstallConfigs.read("not json"));
  }
}
