package fun.fengwk.kkstudio.web.i18n;

import fun.fengwk.convention4j.api.code.CommonErrorCodes;
import fun.fengwk.convention4j.common.i18n.AggregateResourceBundle;
import fun.fengwk.convention4j.common.i18n.StringManager;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.error.DomainErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Web 边界的消息解析器，用于按请求作用域解析用户可见的错误文案。
 *
 * <p>convention starter 只暴露单一启动 locale 的 manager。本服务有意为每个受支持的请求 locale 各持有一个
 * manager，使请求可以选择自己的语言，而无需改动 platform 错误模型或进程级 convention 配置。
 *
 * <p>{@code resource} 在错误模型里是稳定的内部标识（如 {@code system_settings}）。它进入 {@code errors.resource}
 * 诊断字段时必须保持原样，进入用户可见 message 时则经 {@link #resourceDisplayName(String)} 转换为可读名称；
 * 两者由本服务分开处理，从而在不改变诊断协议的前提下改善展示文案。
 */
@Component
public class StudioMessageService {

  private static final String BASE_NAME = "string";
  private static final Locale EN_US = Locale.US;
  private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
  private static final String DOMAIN_PREFIX = "studio.error.domain.";
  private static final String HTTP_PREFIX = "studio.error.http.";

  /** 仅用于展示的 resource 名称映射；未被列出的内部标识回退为把下划线替换为空格。 */
  private static final Map<String, String> RESOURCE_DISPLAY_NAMES =
      Map.of(
          "agent_definition", "agent definition",
          "agent_model", "agent model",
          "agent_provider", "agent provider",
          "issue_agent_thread", "issue agent thread",
          "issue_run", "issue run",
          "mcp_server", "MCP server",
          "skill_package", "skill package",
          "system_settings", "system settings");

  private final Map<Locale, StringManager> stringManagers;

  public StudioMessageService() {
    this(defaultClassLoader());
  }

  StudioMessageService(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "classLoader");
    this.stringManagers =
        Map.of(
            EN_US, createStringManager(EN_US, classLoader),
            ZH_CN, createStringManager(ZH_CN, classLoader));
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
    Map<String, ?> safeContext = context == null ? Collections.emptyMap() : context;
    try {
      return stringManagers.get(normalized).getString(key, safeContext);
    } catch (MissingResourceException error) {
      if (!EN_US.equals(normalized)) {
        return stringManagers.get(EN_US).getString(key, safeContext);
      }
      throw error;
    }
  }

  public String domainMessage(DomainErrorCode code, Map<String, ?> context) {
    Objects.requireNonNull(code, "code");
    return message(DOMAIN_PREFIX + code.code() + ".message", withResourceDisplayName(context));
  }

  /**
   * 把内部 resource 标识转换为用户可见名称，例如 {@code system_settings} → {@code system settings}。
   *
   * <p>只用于展示层 message；{@code errors.resource} 中的稳定标识必须另行原样保留。
   */
  public String resourceDisplayName(String resource) {
    if (resource == null || resource.isBlank()) {
      return resource;
    }
    return RESOURCE_DISPLAY_NAMES.getOrDefault(resource, resource.replace('_', ' '));
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
    CommonErrorCodes errorCode = CommonErrorCodes.ofStatus(status);
    return errorCode == null ? Integer.toString(status) : errorCode.getCode();
  }

  private String httpMessage(int status, String part) {
    CommonErrorCodes errorCode = CommonErrorCodes.ofStatus(status);
    if (errorCode == null) {
      return message(HTTP_PREFIX + "unknown." + part, Collections.singletonMap("status", status));
    }
    String key = HTTP_PREFIX + errorCode.getCode().toLowerCase(Locale.ROOT) + "." + part;
    return message(key, Collections.emptyMap());
  }

  private static StringManager createStringManager(Locale locale, ClassLoader classLoader) {
    ResourceBundle resourceBundle =
        ResourceBundle.getBundle(BASE_NAME, locale, classLoader, AggregateResourceBundle.CONTROL);
    return new StringManager(resourceBundle);
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
