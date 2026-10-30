package fun.fengwk.kkstudio.web.i18n;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.error.DomainErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web 边界的消息解析器，用于按请求作用域解析用户可见的错误文案。
 *
 * <p>本服务为受支持的请求 locale 各持有一个 JDK {@link ResourceBundle}，使请求可以选择自己的语言，而无需改动 platform 错误模型或进程级
 * locale。中文资源缺键时回退到英文资源；两种资源都缺键时抛出 {@link MissingResourceException}，不静默返回空文案。
 *
 * <p>{@code errors.resource} 保留稳定内部标识；用户可见 message 经 {@link #resourceDisplayName(String)} 使用本地化名称。
 */
@Component
public class StudioMessageService {

  private static final String BASE_NAME = "string";
  private static final Locale EN_US = Locale.US;
  private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
  private static final String DOMAIN_PREFIX = "studio.error.domain.";
  private static final String RESOURCE_PREFIX = "studio.error.domain.resource.";
  private static final String HTTP_PREFIX = "studio.error.http.";
  private static final Pattern NAMED_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  private final Map<Locale, ResourceBundle> bundles;

  public StudioMessageService() {
    this(defaultClassLoader());
  }

  StudioMessageService(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "classLoader");
    this.bundles =
        Map.of(
            EN_US, createBundle(EN_US, classLoader),
            ZH_CN, createBundle(ZH_CN, classLoader));
  }

  /** 返回请求 locale 对应的规范受支持 locale。 */
  public Locale normalize(Locale locale) {
    if (locale != null) {
      if (isLocale(locale, EN_US)) {
        return EN_US;
      }
      if (isLocale(locale, ZH_CN)) {
        return ZH_CN;
      }
    }
    return EN_US;
  }

  /** 使用绑定到当前 Spring web 请求的 locale 解析消息。 */
  public String message(String key, Map<String, ?> context) {
    return message(LocaleContextHolder.getLocale(), key, context);
  }

  /** 为显式 locale 解析消息，主要用于确定性的 web 层测试。 */
  public String message(Locale locale, String key, Map<String, ?> context) {
    Objects.requireNonNull(key, "key");
    Locale normalized = normalize(locale);
    try {
      return resolve(bundles.get(normalized), key, context);
    } catch (MissingResourceException error) {
      if (!EN_US.equals(normalized)) {
        return resolve(bundles.get(EN_US), key, context);
      }
      throw error;
    }
  }

  public String domainMessage(DomainErrorCode code, Map<String, ?> context) {
    Objects.requireNonNull(code, "code");
    return message(DOMAIN_PREFIX + code.code() + ".message", withResourceDisplayName(context));
  }

  /** 解析 resource 的本地化展示名；未登记标识回退为下划线分隔的可读形式。 */
  public String resourceDisplayName(String resource) {
    if (resource == null || resource.isBlank()) {
      return resource;
    }
    try {
      return message(RESOURCE_PREFIX + resource, Collections.emptyMap());
    } catch (MissingResourceException missing) {
      return resource.replace('_', ' ');
    }
  }

  private Map<String, ?> withResourceDisplayName(Map<String, ?> context) {
    if (context == null || !(context.get("resource") instanceof String resource)) {
      return context;
    }
    Map<String, Object> display = new LinkedHashMap<>(context);
    display.put("resource", resourceDisplayName(resource));
    return display;
  }

  public String validationRequired(String resource) {
    return message(
        DOMAIN_PREFIX + DomainErrorCode.VALIDATION.code() + ".required",
        Collections.singletonMap("resource", resource));
  }

  public String validationTypeMismatch(String resource) {
    return message(
        DOMAIN_PREFIX + DomainErrorCode.VALIDATION.code() + ".type_mismatch",
        Collections.singletonMap("resource", resource));
  }

  public String httpMessage(int status) {
    return httpMessage(status, "message");
  }

  public String httpTitle(int status) {
    return httpMessage(status, "title");
  }

  public String httpCode(int status) {
    HttpStatus resolved = HttpStatus.resolve(status);
    return resolved == null ? Integer.toString(status) : resolved.name();
  }

  private String httpMessage(int status, String part) {
    HttpStatus resolved = HttpStatus.resolve(status);
    if (resolved == null) {
      return message(HTTP_PREFIX + "unknown." + part, Map.of("status", status));
    }
    String key = HTTP_PREFIX + resolved.name().toLowerCase(Locale.ROOT) + "." + part;
    return message(key, Collections.emptyMap());
  }

  private static ResourceBundle createBundle(Locale locale, ClassLoader classLoader) {
    return ResourceBundle.getBundle(BASE_NAME, locale, classLoader);
  }

  private static String resolve(ResourceBundle bundle, String key, Map<String, ?> context) {
    return format(bundle.getString(key), context);
  }

  /** 替换资源中的 {@code ${name}} 占位符；缺失值按空字符串处理。 */
  private static String format(String template, Map<String, ?> context) {
    Map<String, ?> values = context == null ? Collections.emptyMap() : context;
    Matcher matcher = NAMED_PLACEHOLDER.matcher(template);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      Object value = values.get(matcher.group(1));
      matcher.appendReplacement(
          result, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static boolean isLocale(Locale actual, Locale expected) {
    return expected.getLanguage().equalsIgnoreCase(actual.getLanguage())
        && expected.getCountry().equalsIgnoreCase(actual.getCountry())
        && expected.getVariant().equals(actual.getVariant())
        && expected.getScript().equalsIgnoreCase(actual.getScript());
  }

  private static ClassLoader defaultClassLoader() {
    ClassLoader classLoader = StudioMessageService.class.getClassLoader();
    return classLoader == null ? ClassLoader.getSystemClassLoader() : classLoader;
  }
}
