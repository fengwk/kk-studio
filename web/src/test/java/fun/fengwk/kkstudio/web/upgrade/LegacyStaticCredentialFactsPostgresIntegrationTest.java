package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 静态已录入字段与 has-required-secret 的真实事实：辅助秘密字段（{@code instanceId}）不能被当成主秘密（{@code apiKey}），主秘密缺失时「启用」
 * 前置必须拒绝，两个真实 StaticKey 插件（comfyui / opencli）按各自声明分别判定。
 */
class LegacyStaticCredentialFactsPostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x33);

  private static final SecretKey TARGET_KEY = key(0x44);

  private static final PluginCredentialCodec CODEC = new PluginCredentialCodec();

  /** 只录入 opencli 的辅助秘密：配置历史只能写真实字段名，且不能因此放行「启用但缺 apiKey」。 */
  @Test
  void auxOnlyMaterialRecordsTheRealFieldNames() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);

    LegacyDatabaseUpgradeResult result =
        convert(source, target, secrets(Map.of("instanceId", "legacy-opencli-instance")));
    assertTrue(result.applied());
    assertEquals(1L, result.installedStaticCredentials());
    assertEquals(
        "{instanceId}",
        UpgradePostgresTestSupport.text(
            target,
            "select configured_secret_fields::text from plugin_configuration_revision"
                + " where plugin_id = 'opencli' and revision = 1"));
    String payload =
        CODEC.decrypt(
            TARGET_KEY,
            "opencli",
            UUID.fromString(
                UpgradePostgresTestSupport.text(
                    target,
                    "select auth_generation from plugin_credential where plugin_id = 'opencli'")),
            UpgradePostgresTestSupport.text(
                target,
                "select context_fingerprint from plugin_credential where plugin_id = 'opencli'"),
            null,
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_payload from plugin_credential where plugin_id = 'opencli'"));
    assertTrue(payload.contains("legacy-opencli-instance"));
    assertFalse(payload.contains("apiKey"));
    assertFalse(payload.contains("instanceId\": null"));
  }

  /** 主秘密与辅助秘密同时录入：配置历史两个真实字段名都在，且顺序稳定。 */
  @Test
  void primaryAndAuxMaterialRecordBothFieldsInStableOrder() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);

    Map<String, String> opencliSecrets = new LinkedHashMap<>();
    opencliSecrets.put("instanceId", "legacy-opencli-instance");
    opencliSecrets.put("apiKey", "legacy-opencli-api-key");
    LegacyDatabaseUpgrade.convert(
        source,
        target,
        SOURCE_KEY,
        TARGET_KEY,
        secrets(opencliSecrets),
        false,
        LegacyUpgradeSourceFixture.CLOCK);

    assertEquals(
        "{apiKey,instanceId}",
        UpgradePostgresTestSupport.text(
            target,
            "select configured_secret_fields::text from plugin_configuration_revision"
                + " where plugin_id = 'opencli' and revision = 1"));
  }

  /** 旧配置启用了 openCliHub，但只录入了辅助秘密：必须在写目标之前拒绝，目标保持 pristine。 */
  @Test
  void enabledLegacyOpenCliHubWithoutPrimarySecretIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyUpgradeSourceFixture.enableLegacyOpenCliHub(source);

    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () ->
                convert(source, target, secrets(Map.of("instanceId", "legacy-opencli-instance"))));
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONVERT, error.stage());
    assertFalse(error.outcomeUncertain());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 旧配置启用了 minimaxH3，但完全没有 comfyui 材料：同样 fail closed 且目标 pristine。 */
  @Test
  void enabledLegacyMiniMaxH3WithoutComfyuiMaterialIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyUpgradeSourceFixture.enableLegacyMiniMaxH3(source);

    LegacyDatabaseUpgradeException error =
        assertThrows(LegacyDatabaseUpgradeException.class, () -> convert(source, target, Map.of()));
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONVERT, error.stage());
    assertFalse(error.outcomeUncertain());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 两个真实 StaticKey 插件各自独立判定：comfyui 的 apiKey 不满足就是缺失，即便 opencli 已录入主秘密。 */
  @Test
  void theTwoRealStaticKeyPluginsAreDetectedIndependently() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyUpgradeSourceFixture.enableLegacyMiniMaxH3(source);

    Map<String, Map<String, String>> bothPlugins = new LinkedHashMap<>();
    bothPlugins.put("opencli", Map.of("apiKey", "legacy-opencli-api-key"));
    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class, () -> convert(source, target, bothPlugins));
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONVERT, error.stage());
    UpgradePostgresTestSupport.assertPristine(target);

    bothPlugins.put("comfyui", Map.of("apiKey", LegacyUpgradeSourceFixture.COMFYUI_API_KEY));
    LegacyDatabaseUpgradeResult result = convert(source, target, bothPlugins);
    assertTrue(result.applied());
    assertEquals(2L, result.installedStaticCredentials());
    assertEquals(
        "{apiKey}",
        UpgradePostgresTestSupport.text(
            target,
            "select configured_secret_fields::text from plugin_configuration_revision"
                + " where plugin_id = 'comfyui' and revision = 1"));
    assertEquals(
        "BEARER",
        UpgradePostgresTestSupport.text(
            target,
            "select values_json::jsonb ->> 'authMode' from plugin_configuration_revision"
                + " where plugin_id = 'comfyui' and revision = 1"));
  }

  private static LegacyDatabaseUpgradeResult convert(
      DataSource source, DataSource target, Map<String, Map<String, String>> secrets) {
    return LegacyDatabaseUpgrade.convert(
        source, target, SOURCE_KEY, TARGET_KEY, secrets, false, LegacyUpgradeSourceFixture.CLOCK);
  }

  private static Map<String, Map<String, String>> secrets(Map<String, String> opencliSecrets) {
    Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
    secrets.put("opencli", opencliSecrets);
    return Map.copyOf(secrets);
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
