package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * 离线转换期间对 Environment {@code install_config} 的唯一改写：删除已退役的 {@code javaHome}，其余字段与值原样保留。
 *
 * <p>当前严格安装模型不再携带 {@code javaHome}；但旧源仍可能有该字段，因此转换必须显式删除而不是把旧安装字段带进目标。 非对象或重复键/尾随 token
 * 的持久化值不是受支持来源，直接 fail closed。
 */
final class LegacyEnvironmentInstallConfig {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private LegacyEnvironmentInstallConfig() {}

  /** 按 environment 目标列顺序投影一行，只对 install_config 移除 javaHome。 */
  static List<String> row(ResultSet row) throws SQLException {
    return Arrays.asList(
        row.getString("id"),
        row.getString("name"),
        row.getString("registration_token"),
        withoutJavaHome(row.getString("install_config")),
        row.getString("created_at"),
        row.getString("updated_at"),
        row.getString("version"));
  }

  /** 返回删除顶层 {@code javaHome} 后的 JSON 文本；null 保持 null。 */
  static String withoutJavaHome(String json) {
    if (json == null) {
      return null;
    }
    try {
      JsonNode value = MAPPER.readTree(json);
      if (!(value instanceof ObjectNode object)) {
        throw reject();
      }
      object.remove("javaHome");
      return MAPPER.writeValueAsString(object);
    } catch (LegacyDatabaseUpgradeException error) {
      throw error;
    } catch (RuntimeException error) {
      throw reject();
    }
  }

  private static LegacyDatabaseUpgradeException reject() {
    return new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.CONVERT,
        "legacy environment install config is not the exact supported source shape");
  }
}
