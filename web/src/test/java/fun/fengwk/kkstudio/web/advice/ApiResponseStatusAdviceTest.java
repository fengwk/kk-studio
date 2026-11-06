package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import fun.fengwk.kkstudio.platform.http.ApiResponse;

import java.lang.reflect.Method;

class ApiResponseStatusAdviceTest {

  private final ApiResponseStatusAdvice advice = new ApiResponseStatusAdvice();

  @Test
  void supportsDirectAndResponseEntityApiResponseReturnTypes() throws Exception {
    assertTrue(advice.supports(parameter("direct"), null));
    assertTrue(advice.supports(parameter("entity"), null));
    assertFalse(advice.supports(parameter("raw"), null));
  }

  @Test
  void keepsEnvelopeAndCopiesStatus() {
    Object data = new Object();
    ApiResponse<Object> body = ApiResponse.created(data);
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    // 返回信封本身，由 @JsonValue 输出 data；Spring 不会把返回值按声明类型强转。
    assertSame(body, advice.beforeBodyWrite(body, null, null, null, null, response));

    verify(response).setStatusCode(HttpStatusCode.valueOf(201));
  }

  @Test
  void emptySuccessUsesNoContentExceptForExplicitStatus() {
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    assertNull(advice.beforeBodyWrite(ApiResponse.ok(), null, null, null, null, response));
    verify(response).setStatusCode(HttpStatus.NO_CONTENT);
    ServerHttpResponse accepted = mock(ServerHttpResponse.class);
    assertNull(advice.beforeBodyWrite(ApiResponse.accepted(), null, null, null, null, accepted));
    verify(accepted).setStatusCode(HttpStatus.ACCEPTED);
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
