package fun.fengwk.kkstudio.web.advice;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 HTTP 状态翻译的 problem 风格错误响应（{@code type/title/detail}）。
 *
 * <p>{@code code/message/title} 统一由 {@link StudioMessageService} 解析；未知状态回退为数字 code 与 {@code
 * studio.error.http.unknown.*} 文案，因此所有按状态兜底的 advice 使用同一实现。
 */
public final class ProblemErrorResponses {

  private ProblemErrorResponses() {}

  public static ResponseEntity<ApiResponse<Void>> of(
      StudioMessageService messageService, int status, String detail, String reason) {
    Map<String, Object> errors = new LinkedHashMap<>();
    errors.put("type", "about:blank");
    errors.put("title", messageService.httpTitle(status));
    if (detail != null) {
      errors.put("detail", detail);
    }
    if (reason != null) {
      errors.put("reason", reason);
    }
    return ResponseEntity.status(status)
        .body(
            ApiResponse.error(
                HttpStatusCode.valueOf(status),
                messageService.httpCode(status),
                messageService.httpMessage(status),
                errors));
  }
}
