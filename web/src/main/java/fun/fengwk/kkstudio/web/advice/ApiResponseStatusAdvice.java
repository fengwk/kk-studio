package fun.fengwk.kkstudio.web.advice;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import fun.fengwk.kkstudio.web.api.ApiResponse;

/**
 * 将显式 {@link ApiResponse} 的业务 status 同步为 HTTP status。
 *
 * <p>本 advice 只匹配直接返回 {@code ApiResponse} 的 handler；不匹配 {@code
 * ResponseEntity}、下载、HTML、裸流或任意其它响应，也不构造或替换响应体。
 */
@RestControllerAdvice
public class ApiResponseStatusAdvice implements ResponseBodyAdvice<ApiResponse<?>> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return ApiResponse.class.isAssignableFrom(returnType.getParameterType());
  }

  @Override
  public ApiResponse<?> beforeBodyWrite(
      ApiResponse<?> body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body != null) {
      response.setStatusCode(HttpStatusCode.valueOf(body.getStatus()));
    }
    return body;
  }
}
