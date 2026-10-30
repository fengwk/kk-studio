package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServerHttpResponse;

import fun.fengwk.kkstudio.web.api.ApiResponse;

import java.lang.reflect.Method;
import java.util.Map;

class ApiResponseStatusAdviceTest {

  private final ApiResponseStatusAdvice advice = new ApiResponseStatusAdvice();

  @Test
  void supportsOnlyDirectApiResponseReturnTypes() throws Exception {
    assertTrue(advice.supports(parameter("direct"), null));
    assertFalse(advice.supports(parameter("entity"), null));
    assertFalse(advice.supports(parameter("raw"), null));
  }

  @Test
  void copiesBodyStatusToResponseWithoutReplacingBody() {
    ApiResponse<Void> body =
        ApiResponse.error(HttpStatus.CONFLICT, "CONFLICT", "conflict", Map.of());
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    assertSame(body, advice.beforeBodyWrite(body, null, null, null, null, response));

    verify(response).setStatusCode(HttpStatusCode.valueOf(409));
  }

  @Test
  void leavesResponseStatusUntouchedForNullBody() {
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    advice.beforeBodyWrite(null, null, null, null, null, response);

    verifyNoInteractions(response);
  }

  private static MethodParameter parameter(String name) throws NoSuchMethodException {
    Method method = ProbeController.class.getDeclaredMethod(name);
    return new MethodParameter(method, -1);
  }

  @SuppressWarnings("unused")
  private static final class ProbeController {

    ApiResponse<String> direct() {
      return ApiResponse.ok("direct");
    }

    ResponseEntity<ApiResponse<String>> entity() {
      return ResponseEntity.ok(ApiResponse.ok("entity"));
    }

    String raw() {
      return "raw";
    }
  }
}
