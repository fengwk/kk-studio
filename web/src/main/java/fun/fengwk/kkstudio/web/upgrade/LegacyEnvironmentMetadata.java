package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentStateCodec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 旧 Environment 连接的离线 metadata 转换；只给 DEV V4 补明确未知的架构，不改变其它宿主事实。 */
final class LegacyEnvironmentMetadata {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final DaemonCapabilitiesCodec CAPABILITIES = new DaemonCapabilitiesCodec();

  private static final EnvironmentStateCodec STATE = new EnvironmentStateCodec();

  private static final Set<String> MAIN_ARCHITECTURES = Set.of("amd64", "arm64");

  private LegacyEnvironmentMetadata() {}

  static List<String> row(ResultSet row, String profile) throws SQLException {
    String skills = row.getString("skill_state");
    String events = row.getString("recent_events");
    try {
      STATE.decodeSkillState(skills);
      STATE.decodeEvents(events);
    } catch (RuntimeException error) {
      throw reject();
    }
    return Arrays.asList(
        row.getString("environment_id"),
        row.getString("owner_node_id"),
        row.getString("lease_token"),
        row.getString("status"),
        runtimeInfo(row.getString("runtime_info"), profile),
        skills,
        events,
        row.getString("last_seen_at"),
        row.getString("lease_until"));
  }

  /** MAIN V5 原样保留；DEV V4 经当前严格 codec 验证后返回唯一 V5 形状。SQL NULL 仍为 NULL。 */
  static String runtimeInfo(String json, String profile) {
    if (json == null) {
      return null;
    }
    try {
      int sourceVersion =
          switch (profile) {
            case "dev" -> 4;
            case "main" -> 5;
            default -> throw reject();
          };
      JsonNode value = MAPPER.readTree(json);
      if (!(value instanceof ObjectNode root)
          || !(root.get("environment") instanceof ObjectNode environment)) {
        throw reject();
      }
      JsonNode version = root.get("version");
      if (version == null
          || !version.isIntegralNumber()
          || !version.canConvertToInt()
          || version.intValue() != sourceVersion) {
        throw reject();
      }
      if (sourceVersion == 4) {
        if (environment.has("architecture")) {
          throw reject();
        }
        root.put("version", DaemonCapabilities.VERSION);
        environment.putNull("architecture");
        String converted = MAPPER.writeValueAsString(root);
        CAPABILITIES.decode(converted);
        return converted;
      }
      JsonNode architecture = environment.get("architecture");
      if (architecture == null
          || !architecture.isString()
          || !MAIN_ARCHITECTURES.contains(architecture.stringValue())) {
        throw reject();
      }
      CAPABILITIES.decode(json);
      return json;
    } catch (LegacyDatabaseUpgradeException error) {
      throw error;
    } catch (RuntimeException error) {
      throw reject();
    }
  }

  private static LegacyDatabaseUpgradeException reject() {
    return new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.CONVERT,
        "legacy environment metadata is not the exact supported source shape");
  }
}
