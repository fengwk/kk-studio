package fun.fengwk.kkstudio.project.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.http.DomainErrorResponses;

import java.util.Map;

/** 仅供独立控制器测试；生产映射由 Web 组合根的 advice 测试覆盖。 */
@RestControllerAdvice
final class ProjectControllerTestAdvice {
  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiResponse<Void>> domain(DomainException error) {
    return DomainErrorResponses.of(error, error.getMessage(), error.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> validation(IllegalArgumentException error) {
    return ResponseEntity.badRequest()
        .body(
            ApiResponse.error(
                HttpStatus.BAD_REQUEST,
                "PROJECT_VALIDATION_ERROR",
                error.getMessage(),
                Map.of("detail", error.getMessage())));
  }
}
