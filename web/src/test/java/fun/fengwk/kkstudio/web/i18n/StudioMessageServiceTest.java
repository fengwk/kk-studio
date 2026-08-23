package fun.fengwk.kkstudio.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import fun.fengwk.kkstudio.platform.error.DomainErrorCode;

import java.util.Locale;
import java.util.Map;

class StudioMessageServiceTest {

  private final StudioMessageService messageService = new StudioMessageService();

  @AfterEach
  void resetLocaleContext() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void resolvesDomainAndHttpMessagesForSupportedLocales() {
    LocaleContextHolder.setLocale(Locale.US);
    assertEquals(
        "The agent_model was modified by another request.",
        messageService.domainMessage(
            DomainErrorCode.VERSION_CONFLICT, Map.of("resource", "agent_model")));
    assertEquals("The request is invalid.", messageService.httpMessage(400));
    assertEquals("Bad Request", messageService.httpTitle(400));

    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    assertEquals(
        "agent_model 已被其他请求修改。",
        messageService.domainMessage(
            DomainErrorCode.VERSION_CONFLICT, Map.of("resource", "agent_model")));
    assertEquals("limit 参数为必填项。", messageService.validationRequired("limit"));
    assertEquals("limit 参数的值无效。", messageService.validationTypeMismatch("limit"));
    assertEquals("请求无效。", messageService.httpMessage(400));
    assertEquals("请求错误", messageService.httpTitle(400));
  }

  @Test
  void absentAndUnsupportedLocalesFallBackToEnglish() {
    LocaleContextHolder.resetLocaleContext();
    assertEquals("The requested resource was not found.", messageService.httpMessage(404));

    LocaleContextHolder.setLocale(Locale.FRANCE);
    assertEquals("The requested resource was not found.", messageService.httpMessage(404));
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

  @Test
  void providesMessageKeysForEveryDomainErrorCode() {
    Map<DomainErrorCode, String> expected =
        Map.of(
            DomainErrorCode.VALIDATION, "Invalid agent request.",
            DomainErrorCode.RESOURCE_NOT_FOUND, "The agent was not found.",
            DomainErrorCode.VERSION_CONFLICT, "The agent was modified by another request.",
            DomainErrorCode.DUPLICATE, "The agent already exists.",
            DomainErrorCode.IN_USE, "The agent is still in use.");

    LocaleContextHolder.setLocale(Locale.US);
    for (Map.Entry<DomainErrorCode, String> entry : expected.entrySet()) {
      assertEquals(
          entry.getValue(),
          messageService.domainMessage(entry.getKey(), Map.of("resource", "agent")));
    }
  }
}
