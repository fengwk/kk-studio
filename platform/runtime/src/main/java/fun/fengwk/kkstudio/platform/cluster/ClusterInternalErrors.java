package fun.fengwk.kkstudio.platform.cluster;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import fun.fengwk.kkstudio.platform.error.DomainException;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.http.DomainErrorResponses;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集群内部端点的统一错误响应：与 Web 全局 advice 共用 {@link DomainErrorResponses} 的状态映射、{@code code} 与 {@code errors}
 * 形状。
 *
 * <p>内部端点没有请求 locale，也不把用户输入渲染进消息：领域失败保留类型的 {@code code} 与 {@code errors} 里的 {@code
 * resource}/{@code detail}，{@code message} 使用固定的英文去敏 detail；认证失败固定 401 且只报告失败类别；按状态兜底的错误用 {@link
 * HttpStatus#name()} 作 {@code code}，与全局按状态翻译的约定一致。任何分支都不回显请求内容、凭据或底层异常。
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

  /** 领域失败：与 Web 全局 advice 同形，只把用户文案替换为固定的英文 detail。 */
  public static ResponseEntity<ApiResponse<Void>> domain(DomainException error) {
    String detail = detail(error.getMessage());
    return DomainErrorResponses.of(error, detail, detail);
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
