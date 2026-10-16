package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillTokenCipher;
import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

/**
 * 真实隔离 PostgreSQL 17 上的 CLI 端到端：空目标 prepare 只初始化一次、非空/未知对象/非 origin session 拒绝且保留、DEV 源
 * dry-run→apply→再 apply 拒绝，并验证 Plugin 凭据与 Skill 令牌的显式 key 与静态材料真实生效、源库不变、失败不泄漏测试 sentinel。
 *
 * <p>全部用例只在可丢弃的隔离容器库上运行，不连接任何实际部署。
 */
class LegacyDatabaseUpgradeCliPostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x55);

  private static final SecretKey TARGET_KEY = key(0x66);

  private static final PluginCredentialCodec CODEC = new PluginCredentialCodec();

  private static final String SENTINEL = "sentinel-plugin-secret-do-not-leak-8801";

  private static final String SKILL_PACKAGE = "legacy-cli-skill";

  private static final String SKILL_TOKEN = "legacy-cli-skill-token";

  private static final String COMMIT = "a".repeat(40);

  private static final String OBSERVED_HEAD = "b".repeat(40);

  @TempDir Path tempDir;

  /** 一次 CLI 调用的退出码与两条输出流。 */
  private record Run(int exit, String out, String err) {}

  @Test
  void prepareInitializesABlankTargetExactlyOnce() throws Exception {
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    Path config = prepareConfig(target);

    Run prepared = run("prepare", "--config", config.toString());
    assertEquals(0, prepared.exit(), prepared.err());
    assertTrue(prepared.out().contains("mode=prepare"));
    assertTrue(prepared.out().contains("applied=true"));
    assertEquals("", prepared.err());

    // 真实 Flyway 写出唯一 V1 历史，目标处于 pristine 基线形态。
    try (Connection connection = target.getConnection()) {
      assertEquals(
          (long) LegacySchemaProfile.TARGET_V1_CHECKSUM,
          UpgradePostgresTestSupport.v1Checksum(connection));
    }
    UpgradePostgresTestSupport.assertPristine(target);

    // 已经初始化过的库不是空目标：拒绝、标记无需核验，且保留原状态。
    Run again = run("prepare", "--config", config.toString());
    assertEquals(1, again.exit());
    assertTrue(again.err().contains("error.stage=PREPARE"));
    assertTrue(again.err().contains("blank"));
    assertTrue(again.err().contains("outcomeUncertain=false"));
    UpgradePostgresTestSupport.assertPristine(target);
  }

  @Test
  void prepareRejectsUnknownObjectsAndPreservesThem() throws Exception {
    DataSource withTable = UpgradePostgresTestSupport.blankDatabase();
    execute(withTable, "create table alien_object(id integer primary key)");
    execute(withTable, "insert into alien_object(id) values (1)");
    Run tableResult = run("prepare", "--config", prepareConfig(withTable).toString());
    assertEquals(1, tableResult.exit());
    assertTrue(tableResult.err().contains("error.stage=PREPARE"));
    assertEquals(1L, UpgradePostgresTestSupport.count(withTable, "alien_object"));

    DataSource withSchema = UpgradePostgresTestSupport.blankDatabase();
    execute(withSchema, "create schema alien_namespace");
    Run schemaResult = run("prepare", "--config", prepareConfig(withSchema).toString());
    assertEquals(1, schemaResult.exit());
    assertTrue(schemaResult.err().contains("error.stage=PREPARE"));
    assertEquals(
        1L,
        UpgradePostgresTestSupport.longValue(
            withSchema, "select count(*) from pg_namespace where nspname = 'alien_namespace'"));

    DataSource withRoutine = UpgradePostgresTestSupport.blankDatabase();
    execute(
        withRoutine,
        "create function public.alien_fn() returns integer language sql as 'select 1'");
    Run routineResult = run("prepare", "--config", prepareConfig(withRoutine).toString());
    assertEquals(1, routineResult.exit());
    assertTrue(routineResult.err().contains("error.stage=PREPARE"));

    // 下划线开头的用户类型是真实的未知对象，必须拒绝：它既不是数组伴随类型也无法通过 typtype 过滤掉。
    DataSource withUnderscoreType = UpgradePostgresTestSupport.blankDatabase();
    execute(withUnderscoreType, "create type \"_custom_enum\" as enum ('a', 'b')");
    Run typeResult = run("prepare", "--config", prepareConfig(withUnderscoreType).toString());
    assertEquals(1, typeResult.exit());
    assertTrue(typeResult.err().contains("error.stage=PREPARE"));
  }

  @Test
  void prepareRejectsANonOriginSession() throws Exception {
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    execute(
        target,
        "alter database " + databaseName(target) + " set session_replication_role = replica");
    Run result = run("prepare", "--config", prepareConfig(target).toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=CONNECTION"));
    assertTrue(result.err().contains("origin"));
  }

  @Test
  void prepareRejectsANonWritableSession() throws Exception {
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    execute(
        target,
        "alter database " + databaseName(target) + " set default_transaction_read_only = on");
    Run result = run("prepare", "--config", prepareConfig(target).toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=CONNECTION"));
    assertTrue(result.err().contains("writable"));
  }

  @Test
  void prepareRejectsAMissingPublicSchema() throws Exception {
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    execute(target, "drop schema public cascade");
    Run result = run("prepare", "--config", prepareConfig(target).toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=PREPARE"));
    assertTrue(result.err().contains("public schema"));
    assertTrue(result.err().contains("outcomeUncertain=false"));
  }

  /** baseline 应用失败是不确定结果：DDL 之前就失败的 Flyway 仍必须提示人工核验而不是零写入。 */
  @Test
  void baselineFailureIsReportedAsUncertain() throws Exception {
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    // 空 search_path 让 Flyway 自己的历史表也无法创建：migrate 在第一条 DDL 前失败。
    execute(target, "alter database " + databaseName(target) + " set search_path = ''");
    Run result = run("prepare", "--config", prepareConfig(target).toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=PREPARE"));
    assertTrue(result.err().contains("baseline migration"));
    assertTrue(result.err().contains("outcomeUncertain=true"));
    assertTrue(result.err().contains("do not retry blindly"));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = 'public' and c.relkind in ('r','p','v','m','S','f')"));
  }

  @Test
  void prepareDryRunApplyAndReapplyThroughTheCli() throws Exception {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    insertSkillPackage(
        source, SKILL_PACKAGE, legacySkillEnvelope(SOURCE_KEY, SKILL_PACKAGE, SKILL_TOKEN));
    DataSource target = UpgradePostgresTestSupport.blankDatabase();

    Path sourceKey = UpgradeCliInputFixture.key(inputDir(), "source.key", SOURCE_KEY.getEncoded());
    Path targetKey = UpgradeCliInputFixture.key(inputDir(), "target.key", TARGET_KEY.getEncoded());
    Path prepareConfig = writeConfig("prepare.json", prepareJson(target));
    Path convertConfig =
        writeConfig("convert.json", convertJson(source, target, sourceKey, targetKey));

    Run prepared = run("prepare", "--config", prepareConfig.toString());
    assertEquals(0, prepared.exit(), prepared.err());
    UpgradePostgresTestSupport.assertPristine(target);

    Run dryRun = run("dry-run", "--config", convertConfig.toString());
    assertEquals(0, dryRun.exit(), dryRun.err());
    assertTrue(dryRun.out().contains("mode=dry-run"));
    assertTrue(dryRun.out().contains("profile=dev"));
    assertTrue(dryRun.out().contains("applied=false"));
    assertTrue(dryRun.out().contains("table.agent_provider=1"));
    assertTrue(dryRun.out().contains("rewrittenCredentials=1"));
    assertTrue(dryRun.out().contains("installedStaticCredentials=1"));
    assertTrue(dryRun.out().contains("rewrittenSkillTokens=1"));
    UpgradePostgresTestSupport.assertPristine(target);

    Run apply = run("apply", "--config", convertConfig.toString());
    assertEquals(0, apply.exit(), apply.err());
    assertTrue(apply.out().contains("mode=apply"));
    assertTrue(apply.out().contains("applied=true"));
    assertEquals(1L, UpgradePostgresTestSupport.count(target, "agent_provider"));
    assertEquals(1L, UpgradePostgresTestSupport.count(target, "system_setting"));

    // 源库读完仍是同一个冻结 DEV profile，且行事实逐一未变：CLI 绝不改源。
    try (Connection connection = source.getConnection()) {
      assertEquals(
          "dev", LegacySchemaProfile.identify(LegacySchemaProfile.fingerprint(connection)));
    }
    assertEquals(1L, UpgradePostgresTestSupport.count(source, "agent_provider"));
    assertEquals(
        12L, UpgradePostgresTestSupport.longValue(source, "select version from system_setting"));
    assertEquals(1L, UpgradePostgresTestSupport.count(source, "plugin_credential"));

    // 显式提供的源/目标主密钥与静态材料真实生效。
    assertLegacyCredentialReadableWithTheTargetKey(target);
    assertStaticCredentialIsReadableWithTheTargetKey(target);
    assertEquals(SKILL_TOKEN, decryptTargetSkillToken(target));

    // 目标已提交，重放必须拒绝且不再写入。
    Run reapply = run("apply", "--config", convertConfig.toString());
    assertEquals(1, reapply.exit());
    assertTrue(reapply.err().contains("error.stage=TARGET_PRISTINE"));
    assertTrue(reapply.err().contains("outcomeUncertain=false"));
  }

  /** 真实转换失败（未安装插件的显式静态材料）只输出固定阶段，绝不回显显式秘密 sentinel。 */
  @Test
  void conversionFailureDoesNotLeakSentinelValues() throws Exception {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    Path sourceKey = UpgradeCliInputFixture.key(inputDir(), "source.key", SOURCE_KEY.getEncoded());
    Path targetKey = UpgradeCliInputFixture.key(inputDir(), "target.key", TARGET_KEY.getEncoded());
    Path config =
        writeConfig(
            "convert.json",
            convertJson(source, target, sourceKey, targetKey, "alien-plugin", SENTINEL));

    Run result = run("dry-run", "--config", config.toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=CREDENTIAL"));
    assertTrue(result.err().contains("outcomeUncertain=false"));
    assertFalse(result.out().contains(SENTINEL));
    assertFalse(result.err().contains(SENTINEL));
  }

  /** 旧 format 1 密文只能用显式提供的源主密钥解出，因此能读到重封装后的明文即证明 sourceKeyFile 真实生效。 */
  private void assertLegacyCredentialReadableWithTheTargetKey(DataSource target)
      throws SQLException {
    String pluginId = LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID;
    assertEquals(
        LegacyUpgradeSourceFixture.LEGACY_CREDENTIAL_PLAINTEXT,
        CODEC.decrypt(
            TARGET_KEY,
            pluginId,
            UUID.fromString(authGeneration(target, pluginId)),
            UpgradePostgresTestSupport.text(
                target,
                "select context_fingerprint from plugin_credential where plugin_id = '"
                    + pluginId
                    + "'"),
            LegacyUpgradeSourceFixture.LEGACY_REGION,
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_payload from plugin_credential where plugin_id = '"
                    + pluginId
                    + "'")));
  }

  private void assertStaticCredentialIsReadableWithTheTargetKey(DataSource target)
      throws SQLException {
    String payload =
        CODEC.decrypt(
            TARGET_KEY,
            "comfyui",
            UUID.fromString(authGeneration(target, "comfyui")),
            UpgradePostgresTestSupport.text(
                target,
                "select context_fingerprint from plugin_credential where plugin_id = 'comfyui'"),
            null,
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_payload from plugin_credential where plugin_id = 'comfyui'"));
    assertTrue(payload.contains(LegacyUpgradeSourceFixture.COMFYUI_API_KEY));
  }

  /** 用真实 SkillTokenCipher 与 owner-only 目标密钥文件读回 Skill 令牌：证明 targetKeyFile 真实生效。 */
  private String decryptTargetSkillToken(DataSource target) throws Exception {
    Path keyFile = UpgradeCliInputFixture.key(inputDir(), "readback.key", TARGET_KEY.getEncoded());
    SkillTokenCipher cipher =
        new SkillTokenCipher(new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString()));
    return cipher.decrypt(
        SKILL_PACKAGE,
        UpgradePostgresTestSupport.bytes(
            target,
            "select encrypted_token from skill_package where package_name = '"
                + SKILL_PACKAGE
                + "'"));
  }

  private static String authGeneration(DataSource target, String pluginId) throws SQLException {
    return UpgradePostgresTestSupport.text(
        target,
        "select auth_generation from plugin_credential where plugin_id = '" + pluginId + "'");
  }

  private static void insertSkillPackage(DataSource source, String packageName, byte[] token)
      throws SQLException {
    try (Connection connection = source.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into skill_package (package_name, description, repository_url, branch,"
                    + " current_commit, observed_head_commit, head_checked_at, head_check_error,"
                    + " skills, encrypted_token, version, create_time, update_time) values (?, ?,"
                    + " ?, 'main', ?, ?,"
                    + " timestamptz '2025-02-03 04:05:06.789+00', null, cast(? as jsonb), ?, 3,"
                    + " timestamptz '2025-01-02 03:04:05.678+00',"
                    + " timestamptz '2025-02-03 04:05:06.789+00')")) {
      insert.setString(1, packageName);
      insert.setString(2, "description of " + packageName);
      insert.setString(3, "https://git.example.invalid/" + packageName + ".git");
      insert.setString(4, COMMIT);
      insert.setString(5, OBSERVED_HEAD);
      insert.setString(6, "[{\"name\":\"" + packageName + "\",\"description\":\"legacy skill\"}]");
      insert.setBytes(7, token);
      insert.executeUpdate();
    }
  }

  /** 旧 Skill 令牌格式 1 密文：AAD 为 skill-package / 包名 / i32be(1)。 */
  private static byte[] legacySkillEnvelope(SecretKey sourceKey, String packageName, String token) {
    byte[] aad =
        LengthPrefixedAad.create().field("skill-package").field(packageName).intValue(1).build();
    return AesGcmEnvelope.encrypt(sourceKey, 1, aad, token.getBytes(StandardCharsets.UTF_8));
  }

  private Path prepareConfig(DataSource target) throws IOException {
    return writeConfig("prepare.json", prepareJson(target));
  }

  private static String prepareJson(DataSource target) {
    SimpleDriverDataSource simple = (SimpleDriverDataSource) target;
    return UpgradeCliInputFixture.prepareConfig(
        simple.getUrl(), simple.getUsername(), simple.getPassword());
  }

  private static String convertJson(
      DataSource source, DataSource target, Path sourceKey, Path targetKey) {
    return convertJson(
        source,
        target,
        sourceKey,
        targetKey,
        "comfyui",
        LegacyUpgradeSourceFixture.COMFYUI_API_KEY);
  }

  private static String convertJson(
      DataSource source,
      DataSource target,
      Path sourceKey,
      Path targetKey,
      String pluginId,
      String pluginSecret) {
    SimpleDriverDataSource sourceHandle = (SimpleDriverDataSource) source;
    SimpleDriverDataSource targetHandle = (SimpleDriverDataSource) target;
    return "{\"source\":"
        + UpgradeCliInputFixture.endpoint(
            sourceHandle.getUrl(), sourceHandle.getUsername(), sourceHandle.getPassword())
        + ",\"target\":"
        + UpgradeCliInputFixture.endpoint(
            targetHandle.getUrl(), targetHandle.getUsername(), targetHandle.getPassword())
        + ",\"sourceKeyFile\":\""
        + sourceKey
        + "\",\"targetKeyFile\":\""
        + targetKey
        + "\",\"staticSecrets\":{\""
        + pluginId
        + "\":{\"apiKey\":\""
        + pluginSecret
        + "\"}}}";
  }

  private Path writeConfig(String name, String content) throws IOException {
    return UpgradeCliInputFixture.config(inputDir(), name, content);
  }

  private Path inputDir() throws IOException {
    return UpgradeCliInputFixture.ownerOnlyDir(tempDir);
  }

  private static void execute(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** 隔离库名（URL 最后一段），用于对该库设置 server 级默认值以驱动真实 session/DDL 失败路径。 */
  private static String databaseName(DataSource dataSource) {
    String url = ((SimpleDriverDataSource) dataSource).getUrl();
    int slash = url.lastIndexOf('/');
    int query = url.indexOf('?', slash);
    return query < 0 ? url.substring(slash + 1) : url.substring(slash + 1, query);
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }

  private static Run run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit;
    try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      exit = LegacyDatabaseUpgradeCli.run(args, outStream, errStream);
    }
    return new Run(
        exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }
}
