package fun.fengwk.kkstudio.web.plugin;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.advice.ProblemErrorResponses;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;

/**
 * 构建期 Plugin 管理面 REST 控制器的插件特有错误处理器。
 *
 * <p>通用领域错误由 {@link StudioDomainErrorAdvice} 统一翻译；本类只保留插件请求体的安全解析边界。严禁在响应或日志中回显认证凭据、明文 token 或敏感配置。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = StudioPluginController.class)
public class StudioPluginErrorAdvice {

  private static final String DEFAULT_READABLE_ERROR_DETAIL = "Failed to read request";

  private final StudioMessageService messageService;

  public StudioPluginErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(
      HttpMessageNotReadableException error) {
    // 固定去敏文案：Jackson 的 cause/message 可能内嵌请求正文片段（含秘密补丁值），绝不回显。
    return ProblemErrorResponses.of(
        messageService, HttpStatus.BAD_REQUEST.value(), DEFAULT_READABLE_ERROR_DETAIL, null);
  }
}
