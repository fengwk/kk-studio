package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfiguration;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallConfigDTO;

import java.util.List;
import java.util.Map;

/** 安装输入边界严格类型与深层配置校验，且保存序列化可往返。 */
class EnvironmentInstallConfigsTest {
  private final ObjectMapper json = JsonMapper.builder().build();

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
      assertEquals(DaemonOperatingSystem.fromWireValue(os), value.getOperatingSystem());
      assertEquals(value, EnvironmentInstallConfigs.read(EnvironmentInstallConfigs.write(value)));
    }
    assertNull(EnvironmentInstallConfigs.read(null));
    assertNull(EnvironmentInstallConfigs.write(null));
  }

  /** 未知字段、不可安装的系统 family 与非法标量一样是硬错误，严格拒绝。 */
  @Test
  void rejectsUnknownFieldsInvalidOperatingSystemAndNull() {
    assertThrows(
        DomainException.class,
        () ->
            EnvironmentInstallConfigs.parse(
                json.valueToTree(
                    Map.of(
                        "operatingSystem",
                        "linux",
                        "extraField",
                        "/opt/jdk21",
                        "daemon",
                        Map.of("studioUrl", "https://studio.example.com")))));
    // WSL 与未知取值都不是安装目标。
    for (Object os : List.of("wsl", "", 123, true)) {
      assertThrows(
          DomainException.class,
          () ->
              EnvironmentInstallConfigs.parse(
                  json.valueToTree(
                      Map.of(
                          "operatingSystem",
                          os,
                          "daemon",
                          Map.of("studioUrl", "https://studio.example.com")))));
    }
    assertThrows(DomainException.class, () -> EnvironmentInstallConfigs.validate(null));
  }

  /** validate 也覆盖程序化构造的模型；read 遇到损坏持久化 JSON 必须失败而非静默返回空配置。 */
  @Test
  void validateRejectsProgrammaticModelAndReadRejectsCorruptPersistedJson() {
    assertThrows(
        DomainException.class,
        () -> EnvironmentInstallConfigs.validate(new EnvironmentInstallConfigDTO()));
    DaemonConfiguration invalidDaemon = new DaemonConfiguration();
    invalidDaemon.setStudioUrl("not-an-origin");
    EnvironmentInstallConfigDTO bad = new EnvironmentInstallConfigDTO();
    bad.setOperatingSystem(DaemonOperatingSystem.LINUX);
    bad.setDaemon(invalidDaemon);
    assertThrows(DomainException.class, () -> EnvironmentInstallConfigs.validate(bad));
    assertThrows(IllegalStateException.class, () -> EnvironmentInstallConfigs.read("not json"));
  }
}
