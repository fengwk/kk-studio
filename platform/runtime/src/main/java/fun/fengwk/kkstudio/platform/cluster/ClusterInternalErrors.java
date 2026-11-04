package fun.fengwk.kkstudio.platform.cluster;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import fun.fengwk.kkstudio.platform.error.AiDomainException;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.http.DomainErrorStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集群内部端点的统一错误响应：与 Web 全局 advice 同一种 {@link ApiResponse} 信封和同一张领域状态映射。
 *
 * <p>内部端点没有请求 locale，也不把用户输入渲染进消息：领域失败保留类型的 {@code code} 与 {@code errors} 里的 {@code
 * resource}/{@code detail}；认证失败固定 401 且只报告失败类别；按状态兜底的错误用 {@link HttpStatus#name()} 作 {@code
 * code}，与全局按状态翻译的约定一致。任何分支都不回显请求内容、凭据或底层异常。
 */
public final class ClusterInternalErrors {

  private static final String DEFAULT_DETAIL = "internal request failed";

  private ClusterInternalErrors() {}

  /** 内部认证失败：固定 401，不回显失败原因。 */
  public static ResponseEntity<ApiResponse<Void>> unauthorized() {
    return status(HttpStatus.UNAUTHORIZED, "internal authentication failed");
  }

  /** 内部请求体超出硬上限：413。 */
  public static ResponseEntity<ApiResponse<Void>> payloadTooLarge(String detail) {
    return status(HttpStatus.PAYLOAD_TOO_LARGE, detail);
  }

  /** 领域失败：状态取自 {@link DomainErrorStatus}，code 与 resource 与全局 advice 同形。 */
  public static ResponseEntity<ApiResponse<Void>> domain(AiDomainException error) {
    HttpStatus status = DomainErrorStatus.of(error);
    String detail = detail(error.getMessage());
    Map<String, Object> errors = new LinkedHashMap<>();
    errors.put("resource", error.resource());
    errors.put("detail", detail);
    return ResponseEntity.status(status)
        .body(ApiResponse.error(status, error.code().code(), detail, errors));
  }

  /** 只有状态、没有领域类型的失败：code 使用状态名，detail 由调用方给出稳定文案。 */
  private static ResponseEntity<ApiResponse<Void>> status(HttpStatus status, String detail) {
    String text = detail(detail);
    Map<String, Object> errors = new LinkedHashMap<>();
    errors.put("type", "about:blank");
    errors.put("title", status.getReasonPhrase());
    errors.put("detail", text);
    return ResponseEntity.status(status)
        .body(ApiResponse.error(status, status.name(), text, errors));
  }

  private static String detail(String text) {
    return text == null || text.isBlank() ? DEFAULT_DETAIL : text;
  }
}
