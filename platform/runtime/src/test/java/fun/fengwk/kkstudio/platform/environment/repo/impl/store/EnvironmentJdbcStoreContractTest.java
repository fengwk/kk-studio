package fun.fengwk.kkstudio.platform.environment.repo.impl.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Environment name 是不可变身份：更新语句只允许写 token、安装设置与 CAS 字段。
 *
 * <p>测试直接读取 {@link EnvironmentJdbcStore#UPDATE_BY_ID_SQL} 文本，保证 token 轮换绝不会意外改名。
 */
class EnvironmentJdbcStoreContractTest {

  /** 意图：token 和配置同一条 CAS SQL 更新，绝不包含 name；token 变化时同一语句清空安装 code。 */
  @Test
  void updateByIdNeverWritesNameAndClearsInstallCodeOnTokenChange() {
    String sql = EnvironmentJdbcStore.UPDATE_BY_ID_SQL;

    assertTrue(sql.contains("registration_token_hash = :registrationTokenHash"), sql);
    assertTrue(sql.contains("install_config = cast(:installConfigJson as jsonb)"), sql);
    assertTrue(
        sql.contains(
            "when registration_token_hash is distinct from :registrationTokenHash then null"),
        sql);
    assertTrue(sql.contains("version = version + 1"), sql);
    assertFalse(sql.contains("name"), "environment name must never be updated: " + sql);
  }

  /** 意图：仓库不再暴露按 id 排除自身的名称查询，即“改名”入口已被彻底删除。 */
  @Test
  void storeHasNoRenameEntryPoint() {
    assertFalse(
        Arrays.stream(EnvironmentJdbcStore.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().contains("ExcludingId")));
  }
}
