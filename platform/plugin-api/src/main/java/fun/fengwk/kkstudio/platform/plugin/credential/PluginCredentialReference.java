package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 冻结的凭据身份引用：{@code pluginId} + 认证代际 {@code authGeneration} + 认证上下文指纹 {@code contextFingerprint}。
 *
 * <p>它是「这份秘密属于哪个 Plugin 的哪一代、哪个认证上下文」的唯一权威坐标。只有 scoped 上下文、秘密存取与受保护执行引用消费它；普通管理 DTO
 * 不暴露代际或引用。代际变化（重新登录、SET/CLEAR、context 字段变化）与上下文变化都会让旧引用无法再解析到新秘密。
 *
 * @param pluginId Plugin 全局唯一不可变安装身份
 * @param authGeneration 认证代际：每次人工完整替换凭据或 context 变化都重新生成
 * @param contextFingerprint 认证上下文指纹：64 位小写 SHA-256，绑定 plugin id/code version/schema 身份与
 *     contextFields 严格规范值
 */
public record PluginCredentialReference(
    String pluginId, UUID authGeneration, String contextFingerprint) {

  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

  public PluginCredentialReference {
    // 身份规则与安装身份完全同一：绝不在这里放宽或另立一套。
    pluginId = PluginDescriptor.requireCanonicalPluginId(pluginId);
    authGeneration = Objects.requireNonNull(authGeneration, "authGeneration");
    if (contextFingerprint == null || !FINGERPRINT.matcher(contextFingerprint).matches()) {
      throw new IllegalArgumentException("contextFingerprint must be a 64-char lowercase SHA-256");
    }
  }
}
