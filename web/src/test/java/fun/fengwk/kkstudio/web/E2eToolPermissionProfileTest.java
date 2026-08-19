package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

class E2eToolPermissionProfileTest {

  /** 生产默认 permission 规则：write/edit/bash 各 {@code * -> ask}（read 保持不限制）。 */
  private static final List<PermissionRule> ASK_ALL =
      List.of(new PermissionRule("*", PermissionAction.ASK));

  private static final String DB_DEFAULT_RULES =
      "\"permission\":{\"bash\":[{\"action\":\"ask\",\"pattern\":\"*\"}]";
  private static final String E2E_READ_RULE = "\"read\":[{\"action\":\"ask\",\"pattern\":\"*\"}]";

  /**
   * 生产默认聚合保持 read 不受限：只有 write/edit/bash 进入 {@code * -> ask}。这由 {@code
   * PostgresqlSchemaSeedTest.baselineSystemSettingsRowDecodesToSafeDefaults} 守护与 {@code
   * V1__schema.sql} 默认行一致。
   */
  @Test
  void productionDefaultKeepsReadUnrestricted() {
    ToolSettings settings =
        new ToolSettings(
            SystemSettings.Tool.DEFAULT.permission(), SystemSettings.Tool.DEFAULT.defaultYolo());

    assertEquals(ASK_ALL, settings.rulesFor("write"));
    assertEquals(ASK_ALL, settings.rulesFor("edit"));
    assertEquals(ASK_ALL, settings.rulesFor("bash"));
    assertTrue(settings.rulesFor("read").isEmpty(), "production default must not restrict read");
    assertFalse(settings.defaultYolo());
  }

  /**
   * E2E profile 的 read 权限来自数据库 seed 的 system_setting 覆盖行（{@code db/seed/e2e/V2__e2e_seed.sql}），
   * 而不是任何 profile 配置源；e2e 数据库应用该 seed 后 read/write/edit/bash 均为 {@code * -> ask}。
   */
  @Test
  void e2eDbSeedGrantsReadApproval() throws IOException {
    String seed =
        new String(
            new ClassPathResource("db/seed/e2e/V2__e2e_seed.sql").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    assertTrue(
        seed.contains("system_setting") && seed.contains(E2E_READ_RULE),
        "e2e seed must override the system_setting row and grant read -> ask");
  }

  /** V1 baseline 默认行保持 write/edit/bash 的 {@code * -> ask}（read 不在默认 permission 中）。 */
  @Test
  void v1BaselineKeepsReadUnrestricted() throws IOException {
    String baseline =
        new String(
            new ClassPathResource("db/migration/V1__schema.sql").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    assertTrue(
        baseline.contains(DB_DEFAULT_RULES),
        "V1 default row must keep write/edit/bash ask and no read override");
  }
}
