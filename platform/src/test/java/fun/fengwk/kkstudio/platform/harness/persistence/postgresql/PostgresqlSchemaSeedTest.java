package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.applyCanvasTestDatabase;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 验证 dev 与 e2e seed 都具备幂等性，并且 e2e seed 永不存储真实凭据。重新执行不能改变确定性列的行内容。 */
class PostgresqlSchemaSeedTest extends PostgresSchemaSupport {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @BeforeEach
  void setup() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void devSeedIsIdempotentAndStable() throws Exception {
    applyAndAssertDevSeed();
    // 快照确定性列。
    String devFingerprintBefore = devSeedFingerprint();
    long maxVersionBefore = maxVersionAcrossAgentTables();
    // 重新执行：行内容必须保持一致（无版本变化、无时间戳变化）。
    applyAndAssertDevSeed();
    assertEquals(devFingerprintBefore, devSeedFingerprint(), "dev seed must be idempotent");
    assertEquals(maxVersionBefore, maxVersionAcrossAgentTables(), "version must stay 0");
  }

  @Test
  void baselineSystemSettingsRowDecodesToSafeDefaults() throws Exception {
    // baseline 只有默认 system_setting 行（id=1、version=0），且可严格解码为安全默认聚合。
    try (Connection conn = newConnection();
        Statement st = conn.createStatement()) {
      assertSingleLong(conn, "select count(*) from system_setting", 1L);
      assertSingleLong(conn, "select id from system_setting", 1L);
      assertSingleLong(conn, "select version from system_setting", 0L);
      try (ResultSet rs = st.executeQuery("select config from system_setting")) {
        assertTrue(rs.next(), "default row must exist");
        String configJson = rs.getString(1);
        assertEquals(
            SystemSettings.DEFAULT,
            new SystemSettingsCodec().decode(configJson),
            "default row must decode to the safe default aggregate");
      }
    }
  }

  @Test
  void e2eSeedStoresNoCredentialsAndIsIdempotent() throws Exception {
    try (Connection conn = newConnection()) {
      applyE2eDatabase(conn);
      assertE2eSeedContent(conn);
      assertRepeatableMigrationRecorded(conn, "e2e seed");
      // 重新运行 profile migration，并断言确定性列不发生变化。
      String before = e2eFingerprint();
      applyE2eDatabase(conn);
      assertEquals(before, e2eFingerprint(), "e2e seed must be idempotent");
      assertE2eSeedContent(conn);
      assertRepeatableMigrationRecorded(conn, "e2e seed");
    }
    // Catalog 标识就是名称；任何 seed 行都不会消耗业务序列。
    // 后续插入必须不会与确定性 seed id 冲突。
    assertDoesNotThrow(
        () -> {
          try (Connection conn = newConnection();
              PreparedStatement ps =
                  conn.prepareStatement(
                      "insert into agent_provider (name, provider_type, config, connection_generation_id) values (?,"
                          + " 'openai', '{}'::jsonb, '00000000-0000-0000-0000-000000000088'::uuid)")) {
            ps.setString(1, "post-seed-provider");
            ps.executeUpdate();
          }
        });
  }

  @Test
  void e2eSeedCatalogAndMiniMaxCredentialTargetAreDeterministic() throws Exception {
    try (Connection conn = newConnection()) {
      applyE2eDatabase(conn);
      try (Statement st = conn.createStatement()) {
        assertEquals(
            "anthropic:anthropic,deepseek:openai,google:google,minimax:openai_response,"
                + "openai:openai_response,xai:openai_response,zai:openai",
            singleString(
                st,
                "select string_agg(name || ':' || provider_type, ',' order by name)"
                    + " from agent_provider"),
            "the complete E2E seed catalog must retain its deterministic provider names");
        assertEquals(
            "minimax:openai_response",
            singleString(
                st,
                "select name || ':' || provider_type from agent_provider where name = 'minimax'"),
            "the MiniMax credential synchronizer targets the minimax provider name");
      }
    }
  }

  /** e2e 数据库的 system_setting 覆盖行必须把 read 提升到 {@code * -> ask}（V1 默认行不限制 read）。 */
  @Test
  void e2eSeedSystemSettingGrantsReadApproval() throws Exception {
    try (Connection conn = newConnection()) {
      applyE2eDatabase(conn);
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("select config from system_setting where id = 1")) {
        assertTrue(rs.next(), "e2e database must carry the system_setting row");
        SystemSettings settings = new SystemSettingsCodec().decode(rs.getString(1));
        assertEquals(
            List.of(new PermissionRule("*", PermissionAction.ASK)),
            settings.tool().permission().get(BuiltinToolIds.READ.value()),
            "e2e seed must grant read -> ask in the effective DB settings");
        assertEquals(
            false, settings.tool().defaultYolo(), "e2e seed must keep defaultYolo disabled");
      }
    }
  }

  @Test
  void canvasTestSeedEnablesOnlyTheRequiredNonSecretRuntimeSettings() throws Exception {
    try (Connection conn = newConnection()) {
      applyCanvasTestDatabase(conn);
      SystemSettings settings;
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("select config from system_setting where id = 1")) {
        assertTrue(rs.next());
        settings = new SystemSettingsCodec().decode(rs.getString(1));
      }

      assertTrue(settings.storageMedia().s3Enabled());
      assertEquals(900L, settings.storageMedia().uploadExpiresSeconds());
      assertTrue(settings.integrations().openCliHub().enabled());
      assertEquals("http://opencli-hub:8080", settings.integrations().openCliHub().baseUrl());
      assertTrue(settings.integrations().gptImage2().paidEnabled());
      assertTrue(settings.integrations().seedance().enabled());
      assertEquals("mock-workspace", settings.integrations().seedance().workspaceId());
      assertEquals(10L, settings.integrations().seedance().statusPollIntervalMillis());
      assertEquals(30_000L, settings.integrations().seedance().maxWaitMillis());
      assertTrue(!settings.integrations().comfyui().enabled());
      assertTrue(!settings.integrations().minimaxH3().enabled());
      assertRepeatableMigrationRecorded(conn, "canvas test seed");
      assertRepeatableMigrationRecorded(conn, "dev seed");

      // 重新执行 canvas-test 迁移，断言 repeatable migration 二次运行幂等且设置保持正确。
      applyCanvasTestDatabase(conn);
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("select config from system_setting where id = 1")) {
        assertTrue(rs.next());
        SystemSettings reloaded = new SystemSettingsCodec().decode(rs.getString(1));
        assertEquals(
            settings, reloaded, "canvas test settings must remain identical after re-migration");
      }
    }
  }

  /**
   * 验证从空库执行 Flyway bootstrap 时，各 profile 仅应用 V1 baseline 与对应的 repeatable seeds， 且
   * flyway_schema_history 中不存在任何 V2+ 历史记录。
   */
  @Test
  void cleanSlateBootstrapAcrossAllProfiles() throws Exception {
    // 1. 空库 bootstrap dev profile
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyDevDatabase(conn);
      assertSingleLong(
          conn,
          "select count(*) from flyway_schema_history where version = '1' and success = true",
          1L);
      assertRepeatableMigrationRecorded(conn, "dev seed");
      assertSingleLong(
          conn,
          "select count(*) from flyway_schema_history where version is not null and version != '1'",
          0L);
      assertDevSeedPresent(conn);
    }

    // 2. 空库 bootstrap e2e profile
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyE2eDatabase(conn);
      assertSingleLong(
          conn,
          "select count(*) from flyway_schema_history where version = '1' and success = true",
          1L);
      assertRepeatableMigrationRecorded(conn, "e2e seed");
      assertSingleLong(
          conn,
          "select count(*) from flyway_schema_history where version is not null and version != '1'",
          0L);
      assertE2eSeedContent(conn);
    }

    // 3. 空库 bootstrap canvas-test profile (dev seed + canvas-test seed)
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyCanvasTestDatabase(conn);
      assertSingleLong(
          conn,
          "select count(*) from flyway_schema_history where version = '1' and success = true",
          1L);
      assertRepeatableMigrationRecorded(conn, "canvas test seed");
      assertRepeatableMigrationRecorded(conn, "dev seed");
      assertSingleLong(
          conn,
          "select count(*) from flyway_schema_history where version is not null and version != '1'",
          0L);
      assertDevSeedPresent(conn);
      assertSingleLong(conn, "select count(*) from system_setting where id = 1", 1L);
    }
  }

  /**
   * 验证当 repeatable migration 文件 checksum 发生变化时，Flyway 能够真实重跑该 repeatable migration 并记录第二次 成功执行历史，同时
   * seed 的确定性 replacement 能够将被人为篡改的数据（包括多余行和被修改的属性）彻底收敛恢复为 SQL 最新期望值。
   */
  @Test
  void repeatableMigrationReplaysAndConvergesOnChecksumChange() throws Exception {
    Path tempDir = Files.createTempDirectory("flyway-repeatable-replay-test");
    try {
      Path migrationDir = Files.createDirectories(tempDir.resolve("migration"));
      Path seedDir = Files.createDirectories(tempDir.resolve("seed"));

      String v1Content =
          new String(
              Objects.requireNonNull(
                      PostgresqlSchemaSeedTest.class
                          .getClassLoader()
                          .getResourceAsStream("db/migration/V1__schema.sql"))
                  .readAllBytes(),
              StandardCharsets.UTF_8);
      String rDevContent =
          new String(
              Objects.requireNonNull(
                      PostgresqlSchemaSeedTest.class
                          .getClassLoader()
                          .getResourceAsStream("db/seed/dev/R__dev_seed.sql"))
                  .readAllBytes(),
              StandardCharsets.UTF_8);

      Path v1File = migrationDir.resolve("V1__schema.sql");
      Path rDevFile = seedDir.resolve("R__dev_seed.sql");
      Files.writeString(v1File, v1Content, StandardCharsets.UTF_8);
      Files.writeString(rDevFile, rDevContent, StandardCharsets.UTF_8);

      String migrationLoc = "filesystem:" + migrationDir.toAbsolutePath();
      String seedLoc = "filesystem:" + seedDir.toAbsolutePath();

      try (Connection conn = newConnection()) {
        resetDatabase(conn);

        // 1. 首次 migrate：应用 V1 baseline 与初始 R__dev_seed
        migrate(conn, migrationLoc, seedLoc);
        assertSingleLong(
            conn,
            "select count(*) from flyway_schema_history where version = '1' and success = true",
            1L);
        assertSingleLong(
            conn,
            "select count(*) from flyway_schema_history where version is null and description ="
                + " 'dev seed' and success = true",
            1L);
        assertDevSeedPresent(conn);

        // 2. 人为篡改 seed-owned 数据：修改属性并插入陈旧/多余行
        try (Statement st = conn.createStatement()) {
          st.executeUpdate(
              "update agent_provider set credential = 'tampered-key' where name = 'stub'");
          st.executeUpdate(
              "update agent_definition set system_prompt = 'tampered-prompt' where name ="
                  + " 'default-assistant'");
          st.executeUpdate(
              "insert into agent_model (provider_name, name, description, config) "
                  + "values ('stub', 'extraneous-stub-model', 'extraneous', '{}'::jsonb)");
        }
        assertSingleLong(
            conn,
            "select count(*) from agent_provider where name = 'stub' and credential ="
                + " 'tampered-key'",
            1L);
        assertSingleLong(conn, "select count(*) from agent_model where provider_name = 'stub'", 2L);

        // 3. 修改 R__dev_seed.sql 内容，触发 checksum 变化
        String updatedRDevContent =
            rDevContent + "\n-- modified checksum for repeatable migration replay verification\n";
        Files.writeString(rDevFile, updatedRDevContent, StandardCharsets.UTF_8);

        // 4. 再次执行 migrate：Flyway 必须识别 checksum 变化并重跑 repeatable seed
        migrate(conn, migrationLoc, seedLoc);

        // 5. 断言产生了第二次 repeatable dev seed 执行记录
        assertSingleLong(
            conn,
            "select count(*) from flyway_schema_history where version is null and description ="
                + " 'dev seed' and success = true",
            2L);

        // 6. 断言 seed 数据被确定性收敛恢复：tampered 属性恢复，多余的 extraneous model 被清除
        assertDevSeedPresent(conn);
        assertSingleLong(conn, "select count(*) from agent_model where provider_name = 'stub'", 1L);
      }
    } finally {
      deleteRecursively(tempDir);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (Files.exists(root)) {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }
  }

  private static void applyAndAssertDevSeed() throws Exception {
    try (Connection conn = newConnection()) {
      applyDevDatabase(conn);
      assertDevSeedPresent(conn);
      assertRepeatableMigrationRecorded(conn, "dev seed");
    }
  }

  private static void assertDevSeedPresent(Connection conn) throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from agent_provider where name = 'stub'")) {
      assertTrue(rs.next());
      assertEquals(1L, rs.getLong(1));
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery("select credential from agent_provider where name = 'stub'")) {
      assertTrue(rs.next());
      assertEquals("stub-key", rs.getString(1));
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery("select count(*) from agent_model where name = 'acceptance-stub'")) {
      assertTrue(rs.next());
      assertEquals(1L, rs.getLong(1));
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select count(*) from agent_definition where name = 'default-assistant'")) {
      assertTrue(rs.next());
      assertEquals(1L, rs.getLong(1));
    }
  }

  private static void assertE2eSeedContent(Connection conn) throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery("select count(*) from agent_provider where credential is not null")) {
      assertTrue(rs.next());
      assertEquals(
          0L,
          rs.getLong(1),
          "no e2e provider may carry a credential; a real secret must never be checked in");
    }
    assertSingleCount(conn, "agent_provider", 7L);
    assertSingleCount(conn, "agent_model", 19L);
    assertSingleCount(conn, "agent_definition", 1L);
    try (Statement st = conn.createStatement()) {
      assertEquals(
          "minimax/MiniMax-M2.7:high",
          singleString(
              st,
              "select a.model_provider_name || '/' || a.model_name || ':' || a.variant"
                  + " from agent_definition a"
                  + " join agent_model m on m.provider_name = a.model_provider_name"
                  + " and m.name = a.model_name"
                  + " where a.name = 'default-assistant'"),
          "the default E2E agent must remain bound to minimax/MiniMax-M2.7");
    }
    assertPiModelCatalog(conn);
  }

  private static void assertPiModelCatalog(Connection conn) throws Exception {
    try (InputStream input =
            Objects.requireNonNull(
                PostgresqlSchemaSeedTest.class.getResourceAsStream("pi-model-catalog.json"));
        Statement st = conn.createStatement()) {
      JsonNode expected = OBJECT_MAPPER.readTree(input);
      JsonNode actual =
          OBJECT_MAPPER.readTree(
              singleString(
                  st,
                  "select jsonb_agg(jsonb_build_object("
                      + "'provider', p.name,"
                      + "'name', m.name,"
                      + "'description', m.description,"
                      + "'config', m.config"
                      + ") order by m.provider_name, m.name)::text"
                      + " from agent_model m"
                      + " join agent_provider p on p.name = m.provider_name"));
      assertEquals(
          canonicalModels(expected),
          canonicalModels(actual),
          "E2E models must match the effective Pi 0.82.1 catalog");
    }
  }

  private static JsonNode canonicalModels(JsonNode models) {
    ArrayList<JsonNode> sorted = new ArrayList<>();
    models.forEach(sorted::add);
    sorted.sort(
        Comparator.comparing(
            model -> model.path("provider").asText() + "/" + model.path("name").asText()));
    ArrayNode canonical = OBJECT_MAPPER.createArrayNode();
    sorted.forEach(canonical::add);
    return canonical;
  }

  private static void assertSingleCount(Connection conn, String table, long expected)
      throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from " + table)) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getLong(1), () -> table + " row count mismatch");
    }
  }

  private static String devSeedFingerprint() throws Exception {
    // 把 dev seed 拥有的所有行的确定性列值拼接在一起。
    StringBuilder sb = new StringBuilder();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement()) {
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || provider_type || '|' || coalesce(credential,''),"
                  + " ';' order by name) from agent_provider"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(provider_name || '/' || name || '|' || version,"
                  + " ';' order by provider_name, name) from agent_model"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || coalesce(system_prompt, ''), ';' order by name)"
                  + " from agent_definition"));
    }
    return sb.toString();
  }

  private static long maxVersionAcrossAgentTables() throws Exception {
    long max = 0L;
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select greatest("
                    + "(select coalesce(max(version), 0) from agent_provider),"
                    + "(select coalesce(max(version), 0) from agent_model),"
                    + "(select coalesce(max(version), 0) from agent_definition))")) {
      assertTrue(rs.next());
      max = rs.getLong(1);
    }
    return max;
  }

  private static String e2eFingerprint() throws Exception {
    StringBuilder sb = new StringBuilder();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement()) {
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || provider_type || '|' ||"
                  + " coalesce(base_url, '') || '|' || coalesce(credential, '') || '|' ||"
                  + " version, ';' order by name) from agent_provider"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(provider_name || '/' || name || '|' || coalesce(description, '')"
                  + " || '|' || config::text || '|' || version,"
                  + " ';' order by provider_name, name) from agent_model"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || coalesce(system_prompt, ''), ';' order by name)"
                  + " from agent_definition"));
    }
    return sb.toString();
  }

  private static String singleString(Statement st, String sql) throws Exception {
    try (ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next(), "no result for: " + sql);
      String value = rs.getString(1);
      assertNotNull(value, "string aggregate unexpectedly null for " + sql);
      return value;
    }
  }

  private static void assertSingleLong(Connection conn, String sql, long expected)
      throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getLong(1), sql);
    }
  }

  private static void assertRepeatableMigrationRecorded(Connection conn, String description)
      throws Exception {
    assertSingleLong(
        conn,
        "select count(*) from flyway_schema_history"
            + " where version is null and description = '"
            + description
            + "' and success = true",
        1L);
  }
}
