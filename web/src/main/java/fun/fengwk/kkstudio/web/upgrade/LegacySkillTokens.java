package fun.fengwk.kkstudio.web.upgrade;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillTokenCipher;
import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;

import javax.crypto.SecretKey;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 在调用方的目标事务内重封装已原生 COPY 的 Skill 令牌，只更新 encrypted_token，其余列与匿名行不变。
 *
 * <p>复用 {@link SkillTokenCipher} 的格式 1 与包名 AAD，每行用目标主密钥回读校验；失败只暴露固定 CREDENTIAL 阶段，不携带底层异常。
 */
final class LegacySkillTokens {

  /** 旧源 decoder 要求密文严格长于版本、nonce 与 tag 的固定开销，拒绝空明文 envelope。 */
  private static final int FORMAT_ONE_OVERHEAD_BYTES =
      1 + AesGcmEnvelope.NONCE_BYTES + AesGcmEnvelope.TAG_BITS / 8;

  private static final String LEGACY_ROWS =
      "select package_name, encrypted_token from skill_package"
          + " where encrypted_token is not null order by package_name";

  private LegacySkillTokens() {}

  /** 返回非 null 令牌的重封装数量；只有存在这类行时才要求主密钥。 */
  static long rewriteLegacy(
      Connection source, Connection target, SecretKey sourceKey, SecretKey targetKey) {
    long rewritten = 0;
    try (PreparedStatement select = source.prepareStatement(LEGACY_ROWS);
        PreparedStatement update =
            target.prepareStatement(
                "update skill_package set encrypted_token = ? where package_name = ?");
        PreparedStatement verify =
            target.prepareStatement(
                "select encrypted_token from skill_package where package_name = ?")) {
      select.setFetchSize(LegacyTableCopy.FETCH_SIZE);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          String packageName = rows.getString("package_name");
          byte[] envelope = rows.getBytes("encrypted_token");
          if (envelope.length <= FORMAT_ONE_OVERHEAD_BYTES) {
            throw reject(
                "a legacy skill package token envelope is not a legal format 1 credential");
          }
          if (sourceKey == null || targetKey == null) {
            throw reject("legacy skill package keys are required to rewrite stored tokens");
          }
          String plaintext = SkillTokenCipher.decrypt(sourceKey, packageName, envelope);
          update.setBytes(1, SkillTokenCipher.encrypt(targetKey, packageName, plaintext));
          update.setString(2, packageName);
          if (update.executeUpdate() != 1) {
            throw reject("a copied skill package row is missing from the target");
          }
          verify.setString(1, packageName);
          try (ResultSet stored = verify.executeQuery()) {
            if (!stored.next()
                || !plaintext.equals(
                    SkillTokenCipher.decrypt(targetKey, packageName, stored.getBytes(1)))) {
              throw reject("a rewritten skill package token does not match the source plaintext");
            }
          }
          rewritten++;
        }
      }
    } catch (LegacyDatabaseUpgradeException error) {
      throw error;
    } catch (SQLException | RuntimeException error) {
      throw reject("legacy skill package tokens could not be rewrapped");
    }
    return rewritten;
  }

  private static LegacyDatabaseUpgradeException reject(String reason) {
    return new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.CREDENTIAL, reason);
  }
}
