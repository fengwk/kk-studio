package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Environment name 是不可变身份：更新语句只允许写备注、完整配置、token 与 CAS 字段。
 *
 * <p>测试直接读取 {@link EnvironmentRepository#UPDATE_BY_ID_SQL} 文本，保证配置保存不会意外改名，并按变更来源撤销安装码。
 */
class EnvironmentRepositoryContractTest {

  /** 意图：备注与配置同一条 CAS SQL 更新，绝不包含 name；配置或 token 变化时同一语句清空安装码两列。 */
  @Test
  void updateByIdNeverWritesNameAndClearsInstallCodeOnConfigOrTokenChange() {
    String sql = EnvironmentRepository.UPDATE_BY_ID_SQL;

    assertTrue(sql.contains("registration_token_hash = :registrationTokenHash"), sql);
    assertTrue(sql.contains("note = :note"), sql);
    assertTrue(sql.contains("daemon_config = cast(:daemonConfigJson as jsonb)"), sql);
    assertTrue(sql.contains("install_code_hash = case"), sql);
    assertTrue(sql.contains("install_code_expires_at = case"), sql);
    assertTrue(
        sql.contains("when registration_token_hash is distinct from :registrationTokenHash"), sql);
    assertTrue(
        sql.contains(
            "or daemon_config is distinct from cast(:daemonConfigJson as jsonb) then null"),
        sql);
    assertTrue(sql.contains("version = version + 1"), sql);
    assertFalse(sql.contains("set name"), "environment name must never be updated: " + sql);
  }
}
