package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;

import javax.crypto.SecretKey;
import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 真实遗留源库的最小跨表数据：一个已排空的旧 dev 部署真实会留下的行集合。
 *
 * <p>它覆盖五条真实转换路径：{@code agent_provider} 的旧 wire 值、{@code system_setting} 的七节升级、旧 format 1 凭据、
 * 显式静态密钥材料，以及普通 binary COPY 表（environment / storage_blob / chat / mcp）；全部值都是显式常量，读回断言才有意义。
 *
 * <p>包内可见（package-private）：只是升级测试的源端 fixture，不是生产代码。
 */
final class LegacyUpgradeSourceFixture {

  private static final ObjectMapper SETTINGS_MAPPER = JsonMapper.builder().build();

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);

  static final String PROVIDER = "legacy-openai";

  static final String MODEL = "legacy-model";

  static final String AGENT = "legacy-agent";

  static final String LEGACY_PLUGIN_ID = "minimax-mavis";

  static final String LEGACY_REGION = "CN";

  static final String LEGACY_CREDENTIAL_PLAINTEXT = "legacy-mavis-refresh-token";

  static final String COMFYUI_API_KEY = "explicit-comfyui-api-key";

  private final DataSource dataSource;

  private LegacyUpgradeSourceFixture(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 往一个已迁移的遗留源库写入全部 fixture 行（源库每个用例独有，因此可以整体重放）。 */
  static LegacyUpgradeSourceFixture load(DataSource dataSource, SecretKey sourceKey)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "insert into agent_provider (name, description, provider_type, base_url, credential,"
              + " config, connection_generation_id, created_at, updated_at, version) values ("
              + "'"
              + PROVIDER
              + "', 'legacy provider', 'openai_response', 'https://legacy.example.invalid/v1',"
              + " 'legacy-inline-credential', '{\"temperature\":0.5}',"
              + " '8f14e45f-ceea-467a-9c1d-2b0f5b5d5b01',"
              + " timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2025-02-03 04:05:06.789+00', 7)");
      statement.execute(
          "insert into agent_model (provider_name, name, model_id, description, config, created_at,"
              + " updated_at, version) values ('"
              + PROVIDER
              + "', '"
              + MODEL
              + "', 'gpt-legacy', 'legacy model', '{}',"
              + " timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2025-01-02 03:04:05.678+00', 2)");
      statement.execute(
          "insert into agent_definition (name, type, description, system_prompt,"
              + " model_provider_name, model_name, variant, config, created_at, updated_at,"
              + " version) values ('"
              + AGENT
              + "', 'USER', 'legacy agent', 'You are the legacy agent.', '"
              + PROVIDER
              + "', '"
              + MODEL
              + "', null, '{}', timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2025-01-02 03:04:05.678+00', 1)");
      statement.execute(
          "insert into environment (id, name, registration_token, install_config, created_at,"
              + " updated_at, version) values"
              + " ('0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f', 'legacy-environment',"
              + " 'legacy-registration-token', '{\"operatingSystem\":\"linux\","
              + "\"daemon\":{\"studioUrl\":\"https://legacy-studio.example.invalid\"}}',"
              + " timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2025-01-02 03:04:05.678+00', 3)");
      statement.execute(
          "insert into storage_blob (id, sha256, size_bytes, media_type, width, height,"
              + " duration_ms, ref_count, state, created_at, updated_at) values"
              + " ('6c1f5a1e-58b2-4b1c-9d7e-2f3a4b5c6d7e',"
              + " 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 12,"
              + " 'image/png', 4, 4, null, 1, 'ACTIVE',"
              + " timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2025-01-02 03:04:05.678+00')");
      statement.execute(
          "insert into chat (id, title, agent_name, environment_name, yolo_enabled, created_at,"
              + " updated_at, version, archived_at) values"
              + " ('5b1c2d3e-4f50-4a1b-8c2d-3e4f5a6b7c8d', 'legacy chat', '"
              + AGENT
              + "', null, false, timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2025-01-02 03:04:05.678+00', 0, null)");
      statement.execute(
          "insert into mcp_server (name, url, headers, enabled, timeout_millis, discovery_status,"
              + " created_at, updated_at, version) values ('legacy_mcp',"
              + " 'https://legacy-mcp.example.invalid/mcp', '{}', true, 5000, 'UNVERIFIED',"
              + " timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2025-01-02 03:04:05.678+00', 0)");
      statement.execute(
          "insert into mcp_tool (name, server_name, source_name, description, input_schema) values"
              + " ('legacyMcpEcho', 'legacy_mcp', 'echo', 'legacy echo tool',"
              + " '{\"type\":\"object\"}')");
      statement.execute(
          "insert into plugin_credential (plugin_id, encrypted_payload, region, expires_at,"
              + " next_refresh_at, status, last_refreshed_at, last_refresh_error, version,"
              + " create_time, update_time) values ('"
              + LEGACY_PLUGIN_ID
              + "', "
              + "decode('"
              + hex(legacyEnvelope(sourceKey))
              + "', 'hex'), '"
              + LEGACY_REGION
              + "', timestamptz '2027-03-04 05:06:07.891+00',"
              + " timestamptz '2026-03-04 05:06:07.891+00', 'CONNECTED',"
              + " timestamptz '2026-01-02 03:04:05.678+00', null, 4,"
              + " timestamptz '2025-01-02 03:04:05.678+00',"
              + " timestamptz '2026-01-02 03:04:05.678+00')");
      // 源 settings 已经过一次真实写：version 与两个应用侧时间不是默认值，转换必须原样保留。
      statement.execute(
          "update system_setting set version = 12,"
              + " created_at = timestamptz '2025-06-07 08:09:10.111+00',"
              + " updated_at = timestamptz '2025-07-08 09:10:11.222+00' where id = 1");
    }
    return new LegacyUpgradeSourceFixture(dataSource);
  }

  /** 真实遗留 settings 变体：旧配置启用 openCliHub（旧解析只要求布尔开关）。 */
  static void enableLegacyOpenCliHub(DataSource dataSource) throws SQLException {
    mutateLegacyIntegration(dataSource, "openCliHub", block -> block.put("enabled", true));
  }

  /** 真实遗留 settings 变体：启用旧 minimaxH3 同时必须给出 prompt agent 与 comfy 端点，否则旧解析拒绝。 */
  static void enableLegacyMiniMaxH3(DataSource dataSource) throws SQLException {
    mutateLegacyIntegration(
        dataSource,
        "minimaxH3",
        block -> {
          block.put("enabled", true);
          block.put("promptAgentName", AGENT);
          block.put("comfyBaseUrl", "http://legacy-comfy:8188");
        });
  }

  private static void mutateLegacyIntegration(
      DataSource dataSource, String block, Consumer<ObjectNode> mutation) throws SQLException {
    String config =
        UpgradePostgresTestSupport.text(dataSource, "select config::text from system_setting");
    ObjectNode root = (ObjectNode) SETTINGS_MAPPER.readTree(config);
    ObjectNode blockNode = (ObjectNode) root.get("integrations").get(block);
    if (blockNode == null) {
      throw new IllegalStateException("legacy settings block is missing: " + block);
    }
    mutation.accept(blockNode);
    String mutated = SETTINGS_MAPPER.writeValueAsString(root);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "update system_setting set config = cast('" + mutated + "' as jsonb) where id = 1");
    }
  }

  /** MAIN 专属的退役瞬时路由表：只有旧 lease 全部到期时才允许退休，这里写入两行已到期路由。 */
  static void retireClusterNodes(DataSource dataSource, int rows) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (int index = 0; index < rows; index++) {
        statement.execute(
            "insert into cluster_node (instance_id, internal_url, lease_until, last_seen) values"
                + " ('1f0c0f1e-0000-4000-8000-0000000000"
                + String.format("%02d", index)
                + "', 'http://legacy-node.invalid:8080',"
                + " timestamptz '2025-01-01 00:00:00+00',"
                + " timestamptz '2024-12-31 23:59:00+00')");
      }
    }
  }

  /**
   * 旧 format 1 envelope：{@code [0x01][nonce][ciphertext+tag]}，AAD 为 pluginId / region / i32be(1)。
   */
  static byte[] legacyEnvelope(SecretKey sourceKey) {
    byte[] aad =
        LengthPrefixedAad.create().field(LEGACY_PLUGIN_ID).field(LEGACY_REGION).intValue(1).build();
    return AesGcmEnvelope.encrypt(
        sourceKey, 1, aad, LEGACY_CREDENTIAL_PLAINTEXT.getBytes(StandardCharsets.UTF_8));
  }

  private static String hex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      builder.append(Character.forDigit((value >> 4) & 0xf, 16));
      builder.append(Character.forDigit(value & 0xf, 16));
    }
    return builder.toString();
  }

  DataSource dataSource() {
    return dataSource;
  }

  /** 操作人显式提供的静态密钥材料：只有真实 StaticKey 插件 comfyui 的声明内字段。 */
  static Map<String, Map<String, String>> staticSecrets() {
    Map<String, String> comfyui = new LinkedHashMap<>();
    comfyui.put("apiKey", COMFYUI_API_KEY);
    Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
    secrets.put("comfyui", Collections.unmodifiableMap(comfyui));
    return Collections.unmodifiableMap(secrets);
  }
}
