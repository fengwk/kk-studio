package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从请求 {@code Host} 解析 IDE 独立 Origin 所属 Environment。
 *
 * <p>模板（{@link IdeOriginProperties#getOriginTemplate()}）形如 {@code
 * https://{environmentId}.ide.example.com}；本类把它编译为 只匹配该 authority 形状的正则（占位符处捕获规范 UUID），因此只有真正落在 IDE
 * Origin 上的请求才会被识别。Studio Origin、 未知子域、大小写变体或非 UUID 段一律返回 empty（视为 Studio 请求）。
 */
@Component
public final class IdeHostResolver {

  private static final String PLACEHOLDER = "{environmentId}";

  private static final String UUID_PATTERN =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  /** 解析模板时用固定 UUID 替换占位符，因为 URI 不允许 {@code {}}。 */
  private static final String PROBE_UUID = "00000000-0000-0000-0000-000000000000";

  private final IdeOriginProperties originProperties;

  public IdeHostResolver(IdeOriginProperties originProperties) {
    this.originProperties = Objects.requireNonNull(originProperties, "originProperties");
  }

  /** 解析 {@code Host} 头；不匹配 IDE Origin 时返回 empty。 */
  public Optional<EnvironmentId> resolve(String hostHeader) {
    if (hostHeader == null || hostHeader.isBlank()) {
      return Optional.empty();
    }
    String authority = hostAuthority(hostHeader);
    if (authority == null) {
      return Optional.empty();
    }
    Authority template = templateAuthority();
    if (template == null) {
      return Optional.empty();
    }
    String candidate = template.hasPort ? authority : stripPort(authority);
    Matcher matcher = template.pattern.matcher(candidate);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(EnvironmentId.parse(matcher.group(1).toLowerCase(Locale.ROOT)));
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }

  /** 该请求是否落在 IDE Origin 上。 */
  public boolean isIdeOrigin(String hostHeader) {
    return resolve(hostHeader).isPresent();
  }

  private Authority templateAuthority() {
    String template = originProperties.getOriginTemplate();
    if (template == null || template.isBlank() || !template.contains(PLACEHOLDER)) {
      return null;
    }
    URI uri;
    try {
      uri = new URI(template.replace(PLACEHOLDER, PROBE_UUID));
    } catch (URISyntaxException error) {
      return null;
    }
    String authority = uri.getAuthority();
    if (authority == null) {
      return null;
    }
    int index = authority.indexOf(PROBE_UUID);
    if (index < 0) {
      return null;
    }
    String prefix = authority.substring(0, index);
    String suffix = authority.substring(index + PROBE_UUID.length());
    Pattern pattern =
        Pattern.compile(
            "^" + Pattern.quote(prefix) + "(" + UUID_PATTERN + ")" + Pattern.quote(suffix) + "$");
    return new Authority(pattern, authority.indexOf(':') >= 0);
  }

  /** 去掉请求 Host 的端口；无端口时原样返回。 */
  private static String stripPort(String authority) {
    int colon = authority.indexOf(':');
    return colon < 0 ? authority : authority.substring(0, colon);
  }

  /** 取 Host 头的 authority 部分（去掉 IPv6 方括号之外的空格与路径），小写化。 */
  private static String hostAuthority(String hostHeader) {
    String value = hostHeader.trim().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.contains("/") || value.contains(" ")) {
      return null;
    }
    return value;
  }

  private record Authority(Pattern pattern, boolean hasPort) {}
}
