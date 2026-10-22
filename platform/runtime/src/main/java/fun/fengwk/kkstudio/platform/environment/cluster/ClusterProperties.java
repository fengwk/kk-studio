package fun.fengwk.kkstudio.platform.environment.cluster;

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
 * <p>{@code internal-url} 是本实例对其他节点可达的 HTTP(S) origin；{@code secret} 是内部认证的共享 HMAC 秘密。两者都缺省时为单节点
 * 部署：只做本地交付，不注册也不接受跨节点路由。任一节点缺少所需配置时对应路由明确不可用，绝不从请求 Host 推断内部地址，也绝不把秘密写入 URL、日志或 DTO。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.cluster")
public class ClusterProperties {

  /** 本实例对其他节点可达的 HTTP(S) origin；留空表示不参与跨节点路由。 */
  private String internalUrl;

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

  public boolean internalOriginConfigured() {
    return internalUrl != null && !internalUrl.isBlank();
  }

  public boolean secretConfigured() {
    return secret != null && !secret.isBlank();
  }

  /** 只有内部 origin 与共享秘密同时配置时，本节点才能签名并参与跨节点路由。 */
  public boolean remoteRoutingConfigured() {
    return internalOriginConfigured() && secretConfigured();
  }

  /** 解析并校验内部 origin：必须是裸 http(s) origin，不含 path/query/fragment/userinfo。 */
  public Optional<URI> internalOrigin() {
    if (!internalOriginConfigured()) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(internalUrl.trim());
    } catch (URISyntaxException error) {
      throw new IllegalStateException("kk-studio.cluster.internal-url is not a valid URI", error);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalStateException(
          "kk-studio.cluster.internal-url scheme must be http or https");
    }
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          "kk-studio.cluster.internal-url must be a bare http(s) origin without"
              + " userinfo/query/fragment");
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !path.equals("/")) {
      throw new IllegalStateException("kk-studio.cluster.internal-url must not contain a path");
    }
    return Optional.of(URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getAuthority()));
  }
}
