package fun.fengwk.kkstudio.platform.cluster;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 跨节点路由（实时追帧与 IDE）的部署配置。
 *
 * <p>{@code internal-url} 是本实例对其他节点可达的 HTTPS origin；{@code secret} 是内部认证的共享 HMAC 秘密。两者都缺省时为单节点
 * 部署：只做本地交付，不注册也不接受跨节点路由。任一节点缺少所需配置时对应路由明确不可用，绝不从请求 Host 推断内部地址，也绝不把秘密写入 URL、日志或 DTO。
 *
 * <p>内部流量只允许 https：HMAC 只认证节点身份与防重放，不覆盖请求正文，明文 http 下正文可被中间人读取或改写。因此 {@code internal-url} 在属性绑定时即校验
 * scheme 与裸 origin 形状，非法配置直接使启动失败。{@code ssl-bundle} 是节点间 HTTP 客户端与 IDE WebSocket 转发使用的 Spring Boot
 * SSL bundle 名；留空表示使用 JVM 默认信任。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.cluster")
public class ClusterProperties {

  /** 本实例对其他节点可达的 HTTPS origin；留空表示不参与跨节点路由。 */
  private String internalUrl;

  /** 节点间 TLS 使用的 Spring Boot SSL bundle 名；留空表示使用 JVM 默认信任。 */
  private String sslBundle;

  /** 内部认证的共享 HMAC 秘密；留空表示不做跨节点认证。绝不外泄。 */
  private String secret;

  /** 本节点注册行的租约时长。 */
  private Duration nodeLease = Duration.ofSeconds(45);

  /** 本节点注册行心跳间隔；必须在租约到期前续约。 */
  private Duration heartbeatInterval = Duration.ofSeconds(15);

  /** 内部认证允许的时钟偏移与重放窗口。 */
  private Duration authWindow = Duration.ofSeconds(30);

  /** 内部小 metadata JSON 请求的响应读取上限（字节）。 */
  private int maxMetadataResponseBytes = 64 * 1024;

  /** 绑定时立即校验并规范化内部 origin；配置文件里写成 http 或其他 scheme 会直接使应用启动失败。 */
  public void setInternalUrl(String internalUrl) {
    this.internalUrl =
        internalUrl == null || internalUrl.isBlank()
            ? null
            : httpsOrigin(internalUrl.strip()).toString();
  }

  public boolean internalOriginConfigured() {
    return internalUrl != null;
  }

  public boolean secretConfigured() {
    return secret != null && !secret.isBlank();
  }

  /** 只有内部 origin 与共享秘密同时配置时，本节点才能签名并参与跨节点路由。 */
  public boolean remoteRoutingConfigured() {
    return internalOriginConfigured() && secretConfigured();
  }

  /** 已校验的内部 origin；单节点部署为空。 */
  public Optional<URI> internalOrigin() {
    return Optional.ofNullable(internalUrl).map(URI::create);
  }

  /** 校验内部 origin：必须是裸 https origin，不含 path/query/fragment/userinfo。 */
  private static URI httpsOrigin(String raw) {
    URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException(
          "kk-studio.cluster.internal-url is not a valid https origin", error);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !scheme.equalsIgnoreCase("https")) {
      throw new IllegalArgumentException(
          "kk-studio.cluster.internal-url must be an https origin: cross-node traffic is TLS-only,"
              + " so http and other schemes are rejected");
    }
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "kk-studio.cluster.internal-url must be a bare https origin without"
              + " userinfo/query/fragment");
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !path.equals("/")) {
      throw new IllegalArgumentException("kk-studio.cluster.internal-url must not contain a path");
    }
    return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getAuthority());
  }
}
