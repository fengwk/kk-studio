package fun.fengwk.kkstudio.web.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Web 层显式响应信封。
 *
 * <p>{@code status} 同时表达业务结果和对应的 HTTP 状态；{@code code} 是稳定机器码；成功响应省略空的 {@code data}/{@code
 * errors}。所有 Controller 必须显式返回本类型，不能依赖自动包装任意返回值。
 *
 * @param <T> 响应数据类型
 */
@Getter
@EqualsAndHashCode
@ToString
public final class ApiResponse<T> {

  private final int status;
  private final String code;
  private final String message;
  private final T data;
  private final Map<String, Object> errors;

  private ApiResponse(int status, String code, String message, T data, Map<String, Object> errors) {
    this.status = status;
    this.code = code;
    this.message = message;
    this.data = data;
    this.errors = errors;
  }

  /** 保留既有 wire 契约中的 {@code success} 派生属性。 */
  public boolean isSuccess() {
    return status >= 200 && status < 300;
  }

  public static <T> ApiResponse<T> ok() {
    return success(HttpStatus.OK, null);
  }

  public static <T> ApiResponse<T> ok(T data) {
    return success(HttpStatus.OK, data);
  }

  public static <T> ApiResponse<T> created(T data) {
    return success(HttpStatus.CREATED, data);
  }

  public static <T> ApiResponse<T> accepted() {
    return success(HttpStatus.ACCEPTED, null);
  }

  public static <T> ApiResponse<T> accepted(T data) {
    return success(HttpStatus.ACCEPTED, data);
  }

  public static <T> ApiResponse<T> noContent() {
    return success(HttpStatus.NO_CONTENT, null);
  }

  public static <T> ApiResponse<T> error(
      HttpStatusCode status, String code, String message, Map<String, ?> errors) {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(errors, "errors");
    Map<String, Object> context = new LinkedHashMap<>();
    errors.forEach(
        (key, value) -> {
          context.put(Objects.requireNonNull(key, "errors key"), value);
        });
    return new ApiResponse<>(
        status.value(), code, message, null, Collections.unmodifiableMap(context));
  }

  private static <T> ApiResponse<T> success(HttpStatus status, T data) {
    return new ApiResponse<>(status.value(), status.name(), status.getReasonPhrase(), data, null);
  }
}
