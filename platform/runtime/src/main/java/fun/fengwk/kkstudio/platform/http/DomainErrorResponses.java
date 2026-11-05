package fun.fengwk.kkstudio.platform.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 领域错误的唯一响应构造：Web 全局 advice 与集群内部端点共用同一状态映射、{@code code} 与 {@code errors} 形状。
 *
 * <p>{@code message} 由调用边界选择：浏览器 API 传请求 locale 的用户文案，内部端点传固定的英文去敏 detail。{@code errors} 始终保留稳定的
 * {@code resource} 与 {@code detail}，版本冲突额外携带 {@code expectedVersion}/{@code
 * actualVersion}，调用方不自行拼装响应体。
 */
public final class DomainErrorResponses {

  private DomainErrorResponses() {}

  public static ResponseEntity<ApiResponse<Void>> of(
      DomainException error, String message, String detail) {
    HttpStatus status = DomainErrorStatus.of(error.code());
    Map<String, Object> errors = new LinkedHashMap<>();
    errors.put("resource", error.resource());
    if (error.code() == DomainErrorCode.VERSION_CONFLICT) {
      errors.put("expectedVersion", error.expectedVersion());
      errors.put("actualVersion", error.actualVersion());
    }
    errors.put("detail", detail);
    return ResponseEntity.status(status)
        .body(ApiResponse.error(status, error.code().code(), message, errors));
  }
}
