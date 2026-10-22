package fun.fengwk.kkstudio.platform.plugin;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Plugin 交还 Platform 的待加密凭据材料：路由元数据 + opaque 秘密 JSON。
 *
 * <p>只存在两种真实形态，绝不接受混合三元组：
 *
 * <ul>
 *   <li>交互凭据（deep-link 登录或刷新）：{@code region}、{@code expiresAt}、{@code nextRefreshAt} 全部存在；
 *   <li>静态密钥（{@link PluginAuthSpec.StaticKey}）：三者全部为 {@code null}，没有 region、没有过期、没有自动刷新。
 * </ul>
 *
 * <p>{@code payloadJson} 是 Plugin 自己的秘密载荷（例如 access token 与 client 标识的 JSON），Platform
 * 只把它整体加密，不解析、不投影、 不写日志；{@link #toString()} 有意省略它，避免任何偶然日志泄漏。
 *
 * @param region 凭据所属 region；静态密钥为 null
 * @param expiresAt access token 失效时刻（本地时限，不是身份事实）；静态密钥为 null
 * @param nextRefreshAt 下一次自动刷新时刻，由 Plugin 按自己的刷新公式给出；静态密钥为 null
 * @param payloadJson 秘密载荷 JSON 对象文本；只以密文形式落库（见 {@link PluginCredentialStore}）
 */
public record PluginCredentialMaterial(
    String region, Instant expiresAt, Instant nextRefreshAt, String payloadJson) {

  /** 单个秘密载荷的 UTF-8 字节上限；超出即拒绝，避免无界密文与无界解密开销。 */
  public static final int MAX_PAYLOAD_UTF8_BYTES = 64 * 1024;

  public PluginCredentialMaterial {
    boolean anyRouteMetadata = region != null || expiresAt != null || nextRefreshAt != null;
    boolean allRouteMetadata = region != null && expiresAt != null && nextRefreshAt != null;
    if (anyRouteMetadata != allRouteMetadata) {
      throw new IllegalArgumentException(
          "credential material must carry region, expiresAt and nextRefreshAt together or none");
    }
    if (anyRouteMetadata && (region.isBlank() || !region.equals(region.strip()))) {
      throw new IllegalArgumentException("region must not be blank and must not be padded");
    }
    if (payloadJson == null || payloadJson.isBlank()) {
      throw new IllegalArgumentException("payloadJson must not be blank");
    }
    if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "payloadJson must not exceed " + MAX_PAYLOAD_UTF8_BYTES + " UTF-8 bytes");
    }
    JsonValues.requireJsonObject(payloadJson, "payloadJson");
  }

  /** 静态密钥材料：没有 region、没有过期、没有自动刷新，三者必须保持 null，绝不伪造 {@code Instant.MAX} 或占位 region。 */
  public static PluginCredentialMaterial staticKey(String payloadJson) {
    return new PluginCredentialMaterial(null, null, null, payloadJson);
  }

  /** 该材料是否为交互凭据（携带 region、过期与刷新时刻）。 */
  public boolean interactive() {
    return region != null;
  }

  @Override
  public String toString() {
    return "PluginCredentialMaterial[region="
        + region
        + ", expiresAt="
        + expiresAt
        + ", nextRefreshAt="
        + nextRefreshAt
        + ", payload="
        + "<redacted>"
        + "]";
  }
}
