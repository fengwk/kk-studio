package fun.fengwk.kkstudio.platform.environment.repo.impl.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Environment name 是不可变身份：持久层唯一的更新语句只允许写 registration_token / timestamp / version。
 *
 * <p>测试直接读取 {@link EnvironmentMapper#updateById} 的 SQL 文本，保证 token 轮换绝不会意外改名。
 */
class EnvironmentMapperContractTest {

  /** 意图：token 轮换的 SQL 必须只更新 token 与 CAS 字段，绝不包含 name。 */
  @Test
  void updateByIdNeverWritesName() throws Exception {
    Method updateById =
        EnvironmentMapper.class.getMethod("updateById", EnvironmentDO.class, long.class);
    Update annotation = updateById.getAnnotation(Update.class);
    assertNotNull(annotation, "updateById must declare an explicit @Update statement");
    String sql = String.join(" ", annotation.value());

    assertTrue(sql.contains("registration_token = #{environment.registrationToken}"), sql);
    assertTrue(sql.contains("version = version + 1"), sql);
    assertFalse(sql.contains("name"), "environment name must never be updated: " + sql);
  }

  /** 意图：仓库不再暴露按 id 排除自身的名称查询，即“改名”入口已被彻底删除。 */
  @Test
  void mapperHasNoRenameEntryPoint() {
    assertFalse(
        Arrays.stream(EnvironmentMapper.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().contains("ExcludingId")));
  }
}
