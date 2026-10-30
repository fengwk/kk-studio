package fun.fengwk.kkstudio.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import fun.fengwk.kkstudio.platform.error.DomainErrorCode;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

class StudioMessageServiceTest {

  private final StudioMessageService messageService = new StudioMessageService();

  private Locale originalDefault;

  @BeforeEach
  void pinDefaultLocaleToEnglish() {
    // 回退契约（缺失/不支持 locale -> 英文）依赖 JVM default locale；显式锚定为 US，
    // 避免宿主环境（如 zh_CN）使回退语义不确定。
    originalDefault = Locale.getDefault();
    Locale.setDefault(Locale.US);
  }

  @AfterEach
  void resetLocaleContext() {
    LocaleContextHolder.resetLocaleContext();
    Locale.setDefault(originalDefault);
  }

  @Test
  void resolvesDomainAndHttpMessagesForSupportedLocales() {
    LocaleContextHolder.setLocale(Locale.US);
    assertEquals(
        "The agent model was modified by another request.",
        messageService.domainMessage(
            DomainErrorCode.VERSION_CONFLICT, Map.of("resource", "agent_model")));
    assertEquals("The request is invalid.", messageService.httpMessage(400));
    assertEquals("Bad Request", messageService.httpTitle(400));
    assertEquals("The server encountered an unexpected error.", messageService.httpMessage(500));
    assertEquals("Internal Server Error", messageService.httpTitle(500));

    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    assertEquals(
        "Agent Model 已被其他请求修改。",
        messageService.domainMessage(
            DomainErrorCode.VERSION_CONFLICT, Map.of("resource", "agent_model")));
    assertEquals("limit 参数为必填项。", messageService.validationRequired("limit"));
    assertEquals("limit 参数的值无效。", messageService.validationTypeMismatch("limit"));
    assertEquals("请求无效。", messageService.httpMessage(400));
    assertEquals("请求错误", messageService.httpTitle(400));
    assertEquals("服务器发生意外错误。", messageService.httpMessage(500));
    assertEquals("服务器错误", messageService.httpTitle(500));
  }

  @Test
  void absentAndUnsupportedLocalesFallBackToEnglish() {
    LocaleContextHolder.resetLocaleContext();
    assertEquals("The requested resource was not found.", messageService.httpMessage(404));

    LocaleContextHolder.setLocale(Locale.FRANCE);
    assertEquals("The requested resource was not found.", messageService.httpMessage(404));
  }

  @Test
  void missingKeyFailsInsteadOfReturningRawKeyOrBlankText() {
    LocaleContextHolder.setLocale(Locale.US);
    assertThrows(
        MissingResourceException.class,
        () -> messageService.message("studio.error.missing.key", Map.of()));

    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    assertThrows(
        MissingResourceException.class,
        () -> messageService.message("studio.error.missing.key", Map.of()));
  }

  @Test
  void preservesCommonErrorCodesAndProvidesUnknownStatusFallback() {
    assertEquals("BAD_REQUEST", messageService.httpCode(400));
    assertEquals("NOT_FOUND", messageService.httpCode(404));
    assertEquals("SERVICE_UNAVAILABLE", messageService.httpCode(503));
    assertEquals(
        "The request conflicts with the current resource state.", messageService.httpMessage(409));
    assertEquals("Conflict", messageService.httpTitle(409));
    assertEquals("The service is temporarily unavailable.", messageService.httpMessage(503));
    assertEquals("Service Unavailable", messageService.httpTitle(503));

    assertEquals("599", messageService.httpCode(599));
    assertEquals("HTTP error (status 599).", messageService.httpMessage(599));
    assertEquals("HTTP Error", messageService.httpTitle(599));
    assertFalse(messageService.httpMessage(599).isBlank());
  }

  /** 展示层按请求 locale 解析 resource 名称；未登记标识回退为下划线分隔的可读形式，空值原样返回。 */
  @Test
  void mapsResourceCodesToDisplayNamesPerLocale() {
    LocaleContextHolder.setLocale(Locale.US);
    assertEquals("system settings", messageService.resourceDisplayName("system_settings"));
    assertEquals("MCP server", messageService.resourceDisplayName("mcp_server"));
    assertEquals("skill package", messageService.resourceDisplayName("skill_package"));
    assertEquals("chat", messageService.resourceDisplayName("chat"));
    assertEquals(
        "some future resource", messageService.resourceDisplayName("some_future_resource"));
    assertNull(messageService.resourceDisplayName(null));

    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    assertEquals("系统设置", messageService.resourceDisplayName("system_settings"));
    assertEquals("MCP 服务", messageService.resourceDisplayName("mcp_server"));
    assertEquals("技能包", messageService.resourceDisplayName("skill_package"));
    assertEquals("对话", messageService.resourceDisplayName("chat"));
  }

  @Test
  void providesMessageKeysForEveryDomainErrorCode() {
    Map<DomainErrorCode, String> expected =
        Map.of(
            DomainErrorCode.VALIDATION, "Invalid agent request.",
            DomainErrorCode.RESOURCE_NOT_FOUND, "The agent was not found.",
            DomainErrorCode.VERSION_CONFLICT, "The agent was modified by another request.",
            DomainErrorCode.DUPLICATE, "The agent already exists.",
            DomainErrorCode.IN_USE, "The agent is still in use.",
            DomainErrorCode.IDE_UNAVAILABLE,
                "The environment IDE is unavailable. Try again later.");

    LocaleContextHolder.setLocale(Locale.US);
    for (Map.Entry<DomainErrorCode, String> entry : expected.entrySet()) {
      assertEquals(
          entry.getValue(),
          messageService.domainMessage(entry.getKey(), Map.of("resource", "agent")));
    }
  }

  /** IDE 通道不可用是新增 domain 码：两种受支持语言都必须提供该 key，缺失会导致运行期解析失败。 */
  @Test
  void localizesIdeUnavailableDomainMessageInBothLocales() {
    LocaleContextHolder.setLocale(Locale.US);
    assertEquals(
        "The environment IDE is unavailable. Try again later.",
        messageService.domainMessage(
            DomainErrorCode.IDE_UNAVAILABLE, Map.of("resource", "environment")));

    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    assertEquals(
        "环境 IDE 当前不可用，请稍后重试。",
        messageService.domainMessage(
            DomainErrorCode.IDE_UNAVAILABLE, Map.of("resource", "environment")));
  }
}
