package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillTokenCipher;
import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 真实 PostgreSQL 17 上的 Skill Package 令牌重封装：整行 binary COPY 之后，只把 {@code encrypted_token}
 * 从旧源主密钥换到目标主密钥。
 *
 * <p>源密文一律按已知的旧格式 1 算法（{@code [0x01][nonce][ciphertext+tag]} 与 {@code skill-package} + packageName
 * + i32be(1) AAD）独立合成，绝不调用生产 {@link SkillTokenCipher} 造源数据、也不用 Plugin 格式 2 冒充旧值。断言覆盖两源、同/异主密钥、dry
 * run 与真实提交、匿名 null 行、多包 AAD、旧源长度有效性边界（29 bytes 空明文）、源端零写与除令牌外整行原生值不变。
 */
class LegacySkillTokenUpgradePostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x51);

  private static final SecretKey TARGET_KEY = key(0x52);

  private static final SecretKey SAME_KEY = key(0x53);

  private static final SecretKey WRONG_KEY = key(0x54);

  private static final String PACKAGE_A = "legacy-skill-alpha";

  private static final String PACKAGE_B = "legacy-skill-beta";

  private static final String PACKAGE_ANONYMOUS = "legacy-skill-anonymous";

  private static final String TOKEN_A = "legacy-private-token-alpha";

  private static final String TOKEN_B = "legacy-private-token-beta";

  private static final String COMMIT_A = "1111111111111111111111111111111111111111";

  private static final String HEAD_A = "2222222222222222222222222222222222222222";

  private static final String COMMIT_B = "3333333333333333333333333333333333333333";

  private static final String HEAD_B = "4444444444444444444444444444444444444444";

  private static final String COMMIT_ANONYMOUS = "5555555555555555555555555555555555555555";

  private static final String HEAD_ANONYMOUS = "6666666666666666666666666666666666666666";

  @TempDir Path tempDir;

  /** DEV 源：dry run 只回滚，真实提交把两个令牌换到目标主密钥；匿名行保持 null，源端与其余列都不变。 */
  @Test
  void devSourceRewritesSkillTokensForDifferentKeys() throws Exception {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    insertSkillPackage(
        source, PACKAGE_A, COMMIT_A, HEAD_A, legacyEnvelope(SOURCE_KEY, PACKAGE_A, TOKEN_A));
    insertSkillPackage(
        source, PACKAGE_B, COMMIT_B, HEAD_B, legacyEnvelope(SOURCE_KEY, PACKAGE_B, TOKEN_B));
    insertSkillPackage(source, PACKAGE_ANONYMOUS, COMMIT_ANONYMOUS, HEAD_ANONYMOUS, null);
    byte[] sourceEnvelopeA = storedToken(source, PACKAGE_A);
    List<String> sourceRows = rowSnapshots(source, true);
    List<String> nonTokenRows = rowSnapshots(source, false);

    LegacyDatabaseUpgradeResult dry = convert(source, target, SOURCE_KEY, TARGET_KEY, true);
    assertFalse(dry.applied());
    assertEquals(2L, dry.rewrittenSkillTokens());
    assertEquals(1L, dry.rewrittenCredentials());
    UpgradePostgresTestSupport.assertPristine(target);
    assertEquals(sourceRows, rowSnapshots(source, true), "dry run 不得改写源端令牌行");

    LegacyDatabaseUpgradeResult applied = convert(source, target, SOURCE_KEY, TARGET_KEY, false);
    assertTrue(applied.applied());
    assertEquals(2L, applied.rewrittenSkillTokens());
    assertEquals(1L, applied.rewrittenCredentials());

    // 目标必须由正常实例 SkillTokenCipher（owner-only 合成密钥文件 + 实际 key loader）读回源明文。
    assertEquals(TOKEN_A, decryptTargetToken(target, PACKAGE_A, TARGET_KEY));
    assertEquals(TOKEN_B, decryptTargetToken(target, PACKAGE_B, TARGET_KEY));
    assertEquals(
        1L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from skill_package where package_name = '"
                + PACKAGE_ANONYMOUS
                + "' and encrypted_token is null"));
    byte[] targetEnvelopeA = storedToken(target, PACKAGE_A);
    assertEquals(1, targetEnvelopeA[0], "目标必须仍是 Skill 格式 1，绝不沿用 Plugin 格式 2");
    assertFalse(Arrays.equals(sourceEnvelopeA, targetEnvelopeA), "目标密文必须是目标主密钥下的新 envelope");
    assertEquals(sourceRows, rowSnapshots(source, true), "源端令牌行必须零写");
    assertEquals(nonTokenRows, rowSnapshots(target, false), "除 encrypted_token 外整行原生值必须不变");
  }

  /** MAIN 源且源/目标主密钥相同：仍走同一重封装路径，密文是新 nonce 的新 envelope 而不是原样复制。 */
  @Test
  void mainSourceRewritesSkillTokensWithTheSameKey() throws Exception {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("main");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SAME_KEY);
    insertSkillPackage(
        source, PACKAGE_A, COMMIT_A, HEAD_A, legacyEnvelope(SAME_KEY, PACKAGE_A, TOKEN_A));
    byte[] sourceEnvelope = storedToken(source, PACKAGE_A);

    LegacyDatabaseUpgradeResult applied = convert(source, target, SAME_KEY, SAME_KEY, false);

    assertTrue(applied.applied());
    assertEquals("main", applied.sourceProfile());
    assertEquals(1L, applied.rewrittenSkillTokens());
    assertEquals(TOKEN_A, decryptTargetToken(target, PACKAGE_A, SAME_KEY));
    assertFalse(Arrays.equals(sourceEnvelope, storedToken(target, PACKAGE_A)));
  }

  /** 只有匿名行时不需要任何主密钥：null 令牌保持 null，绝不造密文，也不因缺 key 失败。 */
  @Test
  void anonymousSkillTokensNeedNoKeys() throws Exception {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    deletePluginCredentials(source);
    insertSkillPackage(source, PACKAGE_ANONYMOUS, COMMIT_ANONYMOUS, HEAD_ANONYMOUS, null);

    LegacyDatabaseUpgradeResult applied = convert(source, target, null, null, false);

    assertTrue(applied.applied());
    assertEquals(0L, applied.rewrittenSkillTokens());
    assertEquals(0L, applied.rewrittenCredentials());
    assertEquals(
        1L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from skill_package where package_name = '"
                + PACKAGE_ANONYMOUS
                + "' and encrypted_token is null"));
  }

  /** 空明文负例的物理形态：格式 1 envelope 恰好 29 bytes，是旧 decoder 拒绝的边界；30 bytes（1 字节明文）才是旧源可恢复的最短形态。 */
  @Test
  void emptyPlaintextEnvelopeSitsAtTheLegacyLengthBoundary() {
    assertEquals(29, legacyEnvelope(SOURCE_KEY, PACKAGE_A, "").length);
    assertEquals(30, legacyEnvelope(SOURCE_KEY, PACKAGE_A, "x").length);
  }

  /** 缺 key、错 key、未知格式、损坏密文与搬移到别的 package 的 AAD 都固定安全失败，目标整体回滚且源端零写。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableSkillTokens")
  void rejectsUnreadableSkillTokens(
      String label, Supplier<byte[]> envelope, SecretKey sourceKey, SecretKey targetKey)
      throws Exception {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    deletePluginCredentials(source);
    insertSkillPackage(source, PACKAGE_A, COMMIT_A, HEAD_A, envelope.get());
    List<String> sourceRows = rowSnapshots(source, true);

    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () -> convert(source, target, sourceKey, targetKey, false));

    assertEquals(LegacyDatabaseUpgradeException.Stage.CREDENTIAL, error.stage(), label);
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause(), label);
    assertEquals(0, error.getSuppressed().length, label);
    UpgradePostgresTestSupport.assertPristine(target);
    assertEquals(0L, UpgradePostgresTestSupport.count(target, "skill_package"));
    assertEquals(sourceRows, rowSnapshots(source, true), "失败路径不得改写源端令牌行");
  }

  /** 每一条都是「旧源主密钥下合法格式 1」的独立合成，或明确的坏样本；绝不用 Plugin 格式 2 冒充旧 Skill 值。 */
  static Stream<Arguments> unreadableSkillTokens() {
    return Stream.of(
        arguments(
            "missing source key",
            (Supplier<byte[]>) () -> legacyEnvelope(SOURCE_KEY, PACKAGE_A, TOKEN_A),
            null,
            TARGET_KEY),
        arguments(
            "missing target key",
            (Supplier<byte[]>) () -> legacyEnvelope(SOURCE_KEY, PACKAGE_A, TOKEN_A),
            SOURCE_KEY,
            null),
        arguments(
            "wrong source key",
            (Supplier<byte[]>) () -> legacyEnvelope(WRONG_KEY, PACKAGE_A, TOKEN_A),
            SOURCE_KEY,
            TARGET_KEY),
        arguments(
            "tampered ciphertext",
            (Supplier<byte[]>) () -> tamper(legacyEnvelope(SOURCE_KEY, PACKAGE_A, TOKEN_A)),
            SOURCE_KEY,
            TARGET_KEY),
        arguments(
            "plugin format two envelope",
            (Supplier<byte[]>)
                LegacySkillTokenUpgradePostgresIntegrationTest::pluginFormatTwoEnvelope,
            SOURCE_KEY,
            TARGET_KEY),
        arguments(
            "truncated envelope",
            (Supplier<byte[]>) () -> new byte[] {1, 2, 3},
            SOURCE_KEY,
            TARGET_KEY),
        arguments(
            "empty plaintext format one envelope",
            (Supplier<byte[]>) () -> legacyEnvelope(SOURCE_KEY, PACKAGE_A, ""),
            SOURCE_KEY,
            TARGET_KEY),
        arguments(
            "envelope bound to another package",
            (Supplier<byte[]>) () -> legacyEnvelope(SOURCE_KEY, PACKAGE_B, TOKEN_A),
            SOURCE_KEY,
            TARGET_KEY));
  }

  private static LegacyDatabaseUpgradeResult convert(
      DataSource source,
      DataSource target,
      SecretKey sourceKey,
      SecretKey targetKey,
      boolean dryRun) {
    return LegacyDatabaseUpgrade.convert(
        source, target, sourceKey, targetKey, Map.of(), dryRun, LegacyUpgradeSourceFixture.CLOCK);
  }

  /** 旧源合法格式 1 密文的独立合成：绝不经过生产 SkillTokenCipher。 */
  private static byte[] legacyEnvelope(SecretKey sourceKey, String packageName, String token) {
    byte[] aad =
        LengthPrefixedAad.create().field("skill-package").field(packageName).intValue(1).build();
    return AesGcmEnvelope.encrypt(sourceKey, 1, aad, token.getBytes(StandardCharsets.UTF_8));
  }

  /** Plugin 格式 2 的密文：Skill 路径必须按自己的格式 1 拒绝它。 */
  private static byte[] pluginFormatTwoEnvelope() {
    byte[] aad = LengthPrefixedAad.create().field("minimax-mavis").field("CN").intValue(2).build();
    return AesGcmEnvelope.encrypt(SOURCE_KEY, 2, aad, TOKEN_A.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] tamper(byte[] envelope) {
    byte[] tampered = Arrays.copyOf(envelope, envelope.length);
    tampered[tampered.length - 1] ^= 0x01;
    return tampered;
  }

  /** 用真实实例 SkillTokenCipher 读回目标密文：owner-only 32 字节合成密钥文件驱动实际 key loader。 */
  private String decryptTargetToken(DataSource target, String packageName, SecretKey targetKey)
      throws Exception {
    Path keyFile = tempDir.resolve("target-skill-token.key");
    Files.write(keyFile, targetKey.getEncoded());
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    SkillTokenCipher cipher =
        new SkillTokenCipher(new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString()));
    return cipher.decrypt(packageName, storedToken(target, packageName));
  }

  private static void insertSkillPackage(
      DataSource source, String packageName, String commit, String observedHead, byte[] token)
      throws SQLException {
    try (Connection connection = source.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into skill_package (package_name, description, repository_url, branch,"
                    + " current_commit, observed_head_commit, head_checked_at, head_check_error,"
                    + " skills, encrypted_token, version, create_time, update_time) values (?, ?, ?,"
                    + " 'main', ?, ?, timestamptz '2025-02-03 04:05:06.789+00', null,"
                    + " cast(? as jsonb), ?, 3, timestamptz '2025-01-02 03:04:05.678+00',"
                    + " timestamptz '2025-02-03 04:05:06.789+00')")) {
      insert.setString(1, packageName);
      insert.setString(2, "description of " + packageName);
      insert.setString(3, "https://git.example.invalid/" + packageName + ".git");
      insert.setString(4, commit);
      insert.setString(5, observedHead);
      insert.setString(
          6,
          "[{\"name\":\""
              + packageName
              + "\",\"description\":\"legacy skill "
              + packageName
              + "\"}]");
      insert.setBytes(7, token);
      insert.executeUpdate();
    }
  }

  /** 整行原生值快照；{@code withToken} 为 false 时排除 encrypted_token，用于断言其余列未被改写。 */
  private static List<String> rowSnapshots(DataSource dataSource, boolean withToken)
      throws SQLException {
    String sql =
        "select "
            + (withToken
                ? "row_to_json(t)::text"
                : "(row_to_json(t)::jsonb - 'encrypted_token')::text")
            + " from skill_package t order by package_name";
    List<String> snapshots = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        snapshots.add(rows.getString(1));
      }
    }
    return snapshots;
  }

  private static byte[] storedToken(DataSource dataSource, String packageName) throws SQLException {
    return UpgradePostgresTestSupport.bytes(
        dataSource,
        "select encrypted_token from skill_package where package_name = '" + packageName + "'");
  }

  private static void deletePluginCredentials(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("delete from plugin_credential");
    }
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
