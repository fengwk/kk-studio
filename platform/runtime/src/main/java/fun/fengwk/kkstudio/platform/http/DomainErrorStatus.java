package fun.fengwk.kkstudio.platform.http;

import org.springframework.http.HttpStatus;

import fun.fengwk.kkstudio.platform.error.DomainErrorCode;
import fun.fengwk.kkstudio.platform.error.DomainException;

/**
 * 领域错误码到 HTTP 状态的唯一映射。
 *
 * <p>Web 全局 advice 与集群内部端点都从这里取状态：同一组领域失败在任何 HTTP 边界都翻译成同一个状态。每个 {@link DomainErrorCode}
 * 对应恰好一个状态，新增错误码必须同时在这里给出状态，从而不会出现只在某一边处理的缺口。
 */
public final class DomainErrorStatus {

  private DomainErrorStatus() {}

  /** 取领域失败对应的 HTTP 状态。 */
  public static HttpStatus of(DomainException error) {
    return of(error.code());
  }

  /** 取错误码对应的 HTTP 状态。 */
  public static HttpStatus of(DomainErrorCode code) {
    return switch (code) {
      case VALIDATION -> HttpStatus.BAD_REQUEST;
      case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case VERSION_CONFLICT,
          DUPLICATE,
          IN_USE,
          IDE_UNAVAILABLE,
          ENVIRONMENT_UNAVAILABLE -> HttpStatus.CONFLICT;
      case UPSTREAM_FAILED -> HttpStatus.BAD_GATEWAY;
    };
  }
}
