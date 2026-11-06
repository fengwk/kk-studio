package fun.fengwk.kkstudio.web.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import fun.fengwk.kkstudio.canvas.http.CanvasResourceController;
import fun.fengwk.kkstudio.chat.http.ChatController;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.ide.web.StudioEnvironmentIdeController;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.http.DomainErrorResponses;
import fun.fengwk.kkstudio.platform.http.DomainErrorStatus;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.controller.StudioAgentDefinitionController;
import fun.fengwk.kkstudio.web.controller.StudioAgentModelController;
import fun.fengwk.kkstudio.web.controller.StudioAgentProviderController;
import fun.fengwk.kkstudio.web.controller.StudioConfigSyncController;
import fun.fengwk.kkstudio.web.controller.StudioEnvironmentController;
import fun.fengwk.kkstudio.web.controller.StudioMcpServerController;
import fun.fengwk.kkstudio.web.controller.StudioSessionFileController;
import fun.fengwk.kkstudio.web.controller.StudioSkillCatalogController;
import fun.fengwk.kkstudio.web.controller.StudioStorageController;
import fun.fengwk.kkstudio.web.controller.StudioSystemSettingsController;
import fun.fengwk.kkstudio.web.plugin.StudioPluginController;

import java.util.Map;

/**
 * AI Catalog、Chat、MCP、Environment 与 Plugin 领域错误的 Web 层统一翻译器。
 *
 * <p>所有领域失败都由同一种 {@link DomainException} 表达；handler 只把错误码、资源与 detail 交给 {@link
 * DomainErrorResponses}，HTTP 状态统一取自 {@link DomainErrorStatus}。用户可见 {@code message} 按请求 locale 从
 * {@code studio.error.domain.<code>.message} 解析，版本冲突额外保留 expected/actual。
 *
 * <p>数据库唯一性 / 完整性竞争条件有意不在此处翻译：相关服务自行包装 {@link org.springframework.dao.DuplicateKeyException}
 * 并上报领域错误，因此全局 advice 不会影响无关 controller。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
      StudioAgentProviderController.class,
      StudioAgentModelController.class,
      StudioAgentDefinitionController.class,
      CanvasResourceController.class,
      ChatController.class,
      StudioMcpServerController.class,
      StudioSessionFileController.class,
      StudioEnvironmentController.class,
      StudioSkillCatalogController.class,
      StudioStorageController.class,
      StudioConfigSyncController.class,
      StudioSystemSettingsController.class,
      StudioEnvironmentIdeController.class,
      StudioPluginController.class
    })
public class StudioDomainErrorAdvice {

  private final StudioMessageService messageService;

  public StudioDomainErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiResponse<Void>> handle(DomainException error) {
    return DomainErrorResponses.of(
        error,
        messageService.domainMessage(
            error.code(),
            error.reason(),
            Map.of("resource", error.resource() == null ? "resource" : error.resource())),
        error.getMessage());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingParam(
      MissingServletRequestParameterException error) {
    DomainException wrapped =
        DomainException.validation(
            error.getParameterName(), error.getParameterName() + " is required");
    return DomainErrorResponses.of(
        wrapped, messageService.validationRequired(error.getParameterName()), wrapped.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
      MethodArgumentTypeMismatchException error) {
    DomainException wrapped =
        DomainException.validation(
            error.getName(), error.getName() + " has invalid value: " + error.getValue());
    return DomainErrorResponses.of(
        wrapped, messageService.validationTypeMismatch(error.getName()), wrapped.getMessage());
  }
}
