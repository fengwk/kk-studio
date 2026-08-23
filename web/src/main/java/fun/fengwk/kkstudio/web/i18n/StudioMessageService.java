package fun.fengwk.kkstudio.web.i18n;

import fun.fengwk.convention4j.api.code.CommonErrorCodes;
import fun.fengwk.convention4j.common.i18n.AggregateResourceBundle;
import fun.fengwk.convention4j.common.i18n.StringManager;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.ai.error.DomainErrorCode;

import java.util.Collections;
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
 */
@Component
public class StudioMessageService {

  private static final String BASE_NAME = "string";
  private static final Locale EN_US = Locale.US;
  private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
  private static final String DOMAIN_PREFIX = "studio.error.domain.";
  private static final String HTTP_PREFIX = "studio.error.http.";

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
    return message(DOMAIN_PREFIX + code.code() + ".message", context);
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
