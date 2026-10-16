package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

/** 真实 PostgreSQL 17 上三份冻结 DDL 的应用 schema 指纹实测：DEV / MAIN 冻结源资源与当前 V1 目标库都必须复现各自记录的固定指纹。 */
class LegacySchemaProfilePostgresIntegrationTest {

  /** DEV 冻结基线（与 Git blob 逐字节一致）的指纹。 */
  @Test
  void devBaselineFingerprintMatchesTheRecordedProfile() throws SQLException {
    try (Connection connection = UpgradePostgresTestSupport.sourceDatabase("dev").getConnection()) {
      String fingerprint = LegacySchemaProfile.fingerprint(connection);
      assertEquals(LegacySchemaProfile.DEV, fingerprint, "DEV=" + fingerprint);
    }
  }

  /** MAIN 冻结基线（与 Git blob 逐字节一致）的指纹。 */
  @Test
  void mainBaselineFingerprintMatchesTheRecordedProfile() throws SQLException {
    try (Connection connection =
        UpgradePostgresTestSupport.sourceDatabase("main").getConnection()) {
      String fingerprint = LegacySchemaProfile.fingerprint(connection);
      assertEquals(LegacySchemaProfile.MAIN, fingerprint, "MAIN=" + fingerprint);
    }
  }

  /** 当前 V1 目标基线（当前 classpath 唯一迁移）的指纹。 */
  @Test
  void targetBaselineFingerprintMatchesTheRecordedProfile() throws SQLException {
    try (Connection connection =
        UpgradePostgresTestSupport.pristineTargetDatabase().getConnection()) {
      String fingerprint = LegacySchemaProfile.fingerprint(connection);
      assertEquals(LegacySchemaProfile.TARGET, fingerprint, "TARGET=" + fingerprint);
    }
  }

  /** 源 profile 识别与 Flyway V1 校验和必须同时生效：DEV 基线被识别为 dev，且拒绝被识别为 main。 */
  @Test
  void devBaselineIsIdentifiedAndItsHistoryIsAccepted() throws SQLException {
    try (Connection connection = UpgradePostgresTestSupport.sourceDatabase("dev").getConnection()) {
      String profile = LegacySchemaProfile.identify(LegacySchemaProfile.fingerprint(connection));
      assertEquals("dev", profile);
      LegacySchemaProfile.requireSourceHistory(connection, profile);
    }
  }

  /** 目标基线必须带着当前唯一成功的 V1 历史，且校验和常量与真实 Flyway 计算一致。 */
  @Test
  void targetBaselineHistoryIsAccepted() throws SQLException {
    try (Connection connection =
        UpgradePostgresTestSupport.pristineTargetDatabase().getConnection()) {
      LegacySchemaProfile.requireTarget(connection);
      assertEquals(
          LegacySchemaProfile.TARGET_V1_CHECKSUM,
          (int) UpgradePostgresTestSupport.v1Checksum(connection),
          "the recorded target checksum must equal the real Flyway checksum");
    }
  }

  /** MAIN 基线（含退役 cluster_node）同样被识别并通过历史校验。 */
  @Test
  void mainBaselineIsIdentifiedAndItsHistoryIsAccepted() throws SQLException {
    try (Connection connection =
        UpgradePostgresTestSupport.sourceDatabase("main").getConnection()) {
      String profile = LegacySchemaProfile.identify(LegacySchemaProfile.fingerprint(connection));
      assertEquals("main", profile);
      LegacySchemaProfile.requireSourceHistory(connection, profile);
    }
  }
}
