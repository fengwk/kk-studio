package fun.fengwk.kkstudio.platform.environment;

import fun.fengwk.kkstudio.common.hash.Sha256;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Environment 注册凭证：数据库只保存 SHA-256，明文只在创建、轮换与安装脚本中出现一次。
 *
 * <p>Daemon 在 HELLO 中出示明文，服务端按同一摘要查找 Environment，因此泄露的数据库或配置导出都不能直接冒充 Daemon。
 */
public final class RegistrationTokens {

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  private RegistrationTokens() {}

  /** 生成新的明文凭证。 */
  public static String generate() {
    return UUID.randomUUID().toString();
  }

  /** 明文凭证的规范摘要：UTF-8 的 SHA-256，小写十六进制。 */
  public static String hash(String token) {
    return Sha256.hex(token);
  }

  /** 摘要是否为规范形态（64 位小写十六进制）。 */
  public static boolean isHash(String value) {
    return value != null && HASH.matcher(value).matches();
  }
}
