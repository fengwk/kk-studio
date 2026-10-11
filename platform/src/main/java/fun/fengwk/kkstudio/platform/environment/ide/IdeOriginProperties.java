package fun.fengwk.kkstudio.platform.environment.ide;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * IDE 独立 Origin 的部署配置。
 *
 * <p>IDE 与 Studio 必须不同 Origin；{@link #originTemplate} 锁定形如 {@code
 * https://{environmentId}.ide.example.com} 的模板，缺省时 IDE 视图明确不可用，绝不回退 Studio 同源。模板必须恰好含一个 {@code
 * {environmentId}} 占位符，且只能是 scheme+authority，不接受任意 path/query/fragment/userinfo。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.ide")
public class IdeOriginProperties {

  private static final String PLACEHOLDER = "{environmentId}";

  /** 形如 {@code https://{environmentId}.ide.example.com}；留空表示本部署不提供 IDE 能力。 */
  private String originTemplate;

  /** bootstrap 入口路径：固定受控 endpoint，不接受浏览器提供的任意 proxy 地址。 */
  private String bootstrapPath = "/__ide/bootstrap";

  public boolean configured() {
    return originTemplate != null && !originTemplate.isBlank();
  }

  /** 解析该 Environment 的受控 Origin；未配置或模板非法时抛 {@link IllegalStateException}（fail-closed）。 */
  public String requireOrigin(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    String template = originTemplate;
    if (template == null || template.isBlank()) {
      throw new IllegalStateException("kk-studio.harness.ide.origin-template is not configured");
    }
    int first = template.indexOf(PLACEHOLDER);
    if (first < 0 || template.indexOf(PLACEHOLDER, first + PLACEHOLDER.length()) >= 0) {
      throw new IllegalStateException(
          "origin-template must contain exactly one {environmentId} placeholder");
    }
    URI uri;
    try {
      uri = new URI(template.replace(PLACEHOLDER, environmentId.toString()));
    } catch (URISyntaxException error) {
      throw new IllegalStateException("origin-template is not a valid URI", error);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalStateException("origin-template scheme must be http or https");
    }
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          "origin-template must be a bare http(s) origin without userinfo/query/fragment");
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !path.equals("/")) {
      throw new IllegalStateException("origin-template must not contain a path");
    }
    return scheme.toLowerCase(Locale.ROOT) + "://" + uri.getAuthority();
  }

  /** 生成该 Environment 上一次性 bootstrap 入口 URL。 */
  public String bootstrapEntryUrl(EnvironmentId environmentId, String ticket) {
    Objects.requireNonNull(ticket, "ticket");
    return requireOrigin(environmentId) + requireBootstrapPath() + "?token=" + ticket;
  }

  private String requireBootstrapPath() {
    String path = bootstrapPath;
    if (path == null
        || path.isBlank()
        || !path.startsWith("/")
        || path.startsWith("//")
        || path.contains("?")
        || path.contains("#")) {
      throw new IllegalStateException(
          "bootstrapPath must be an absolute path without query/fragment");
    }
    return path;
  }
}
