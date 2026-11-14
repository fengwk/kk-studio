package fun.fengwk.kkstudio.web.advice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JacksonException.Reference;
import tools.jackson.databind.exc.IgnoredPropertyException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.i18n.MessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP 失败的统一翻译；已提交的响应与断连由前置 resolver 处理。 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ProblemAdvice {

  private final MessageService messages;

  public ProblemAdvice(MessageService messages) {
    this.messages = messages;
  }

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<Map<String, Object>> domain(DomainException error) {
    Map<String, Object> errors = error.errors();
    if (error.code() == DomainErrorCode.VERSION_CONFLICT) {
      errors = new LinkedHashMap<>();
      errors.put("expectedVersion", error.expectedVersion());
      errors.put("actualVersion", error.actualVersion());
    }
    String resource = error.resource() == null ? "resource" : error.resource();
    return problem(
        status(error.code()),
        error.code(),
        error.reason(),
        error.getMessage(),
        errors,
        messages.domainMessage(error.code(), error.reason(), Map.of("resource", resource)));
  }

  @ExceptionHandler(ClusterInternalAuthenticationException.class)
  public ResponseEntity<Map<String, Object>> authentication(
      ClusterInternalAuthenticationException error) {
    return framework(401, "internal authentication failed", null);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException error) {
    String reason = null;
    if (error.getCause() instanceof DomainException domain
        && domain.code() == DomainErrorCode.CONFLICT) {
      reason = domain.reason();
    }
    return framework(error.getStatusCode().value(), error.getReason(), reason);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> missingParameter(
      MissingServletRequestParameterException error) {
    String name = error.getParameterName();
    return problem(
        400,
        DomainErrorCode.VALIDATION,
        null,
        name + " is required",
        null,
        messages.validationRequired(name));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> typeMismatch(
      MethodArgumentTypeMismatchException error) {
    String name = error.getName();
    return problem(
        400,
        DomainErrorCode.VALIDATION,
        null,
        name + " has an invalid value",
        null,
        messages.validationTypeMismatch(name));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException error) {
    return framework(400, requestBodyDetail(error), null);
  }

  /** 响应体不暴露 5xx 细节，因此必须在这里记录原始异常，否则服务端故障无迹可查。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception error) {
    if (error instanceof ErrorResponse response && response.getStatusCode().value() < 500) {
      return framework(response.getStatusCode().value(), response.getBody().getDetail(), null);
    }
    int status = error instanceof ErrorResponse response ? response.getStatusCode().value() : 500;
    log.error("unexpected HTTP failure status={}", status, error);
    return framework(status, null, null);
  }

  private ResponseEntity<Map<String, Object>> framework(int status, String detail, String reason) {
    DomainErrorCode code =
        switch (status) {
          case 400, 405, 406, 413, 415, 422 -> DomainErrorCode.VALIDATION;
          case 401, 403 -> DomainErrorCode.UNAUTHORIZED;
          case 404 -> DomainErrorCode.NOT_FOUND;
          case 409 -> DomainErrorCode.CONFLICT;
          case 502, 504 -> DomainErrorCode.UPSTREAM_FAILED;
          case 503 -> DomainErrorCode.UNAVAILABLE;
          default -> DomainErrorCode.INTERNAL;
        };
    if (code == DomainErrorCode.CONFLICT && reason == null) {
      reason = "HTTP_CONFLICT";
    }
    return problem(status, code, reason, detail, null, messages.httpMessage(status));
  }

  /**
   * 请求体读取失败的 detail：有字段路径时只回报路径与错误类别，绝不回显原始输入。
   *
   * <p>未知或忽略字段写成 {@code <path> is not allowed}；类型与格式错误写成 {@code <path> has an invalid value}，与
   * query/path 参数的同名文案一致；无法定位到字段的非法 JSON（语法错误、重复键、尾随内容）回退固定文本。
   */
  private static String requestBodyDetail(HttpMessageNotReadableException error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (!(cause instanceof JacksonException jackson)) {
        continue;
      }
      String path = jsonPath(jackson);
      if (path.isEmpty()) {
        continue;
      }
      boolean unknown =
          jackson instanceof UnrecognizedPropertyException
              || jackson instanceof IgnoredPropertyException;
      return path + (unknown ? " is not allowed" : " has an invalid value");
    }
    return "Failed to read request";
  }

  private static String jsonPath(JacksonException error) {
    StringBuilder path = new StringBuilder();
    for (Reference reference : error.getPath()) {
      if (reference.getPropertyName() != null) {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(reference.getPropertyName());
      } else if (reference.getIndex() >= 0) {
        path.append('[').append(reference.getIndex()).append(']');
      }
    }
    return path.toString();
  }

  private ResponseEntity<Map<String, Object>> problem(
      int status,
      DomainErrorCode code,
      String reason,
      String detail,
      Map<String, Object> errors,
      String title) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status);
    if (detail != null) {
      body.put("detail", detail);
    }
    body.put("code", code.code());
    if (reason != null) {
      body.put("reason", reason);
    }
    if (errors != null && !errors.isEmpty()) {
      body.put("errors", errors);
    }
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  private static int status(DomainErrorCode code) {
    return switch (code) {
      case VALIDATION -> HttpStatus.BAD_REQUEST.value();
      case NOT_FOUND -> HttpStatus.NOT_FOUND.value();
      case VERSION_CONFLICT, CONFLICT, DUPLICATE, IN_USE, UNAVAILABLE -> HttpStatus.CONFLICT
          .value();
      case UPSTREAM_FAILED -> HttpStatus.BAD_GATEWAY.value();
      case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED.value();
      case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR.value();
    };
  }
}
