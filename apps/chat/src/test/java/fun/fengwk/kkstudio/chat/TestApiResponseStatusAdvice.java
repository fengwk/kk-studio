package fun.fengwk.kkstudio.chat;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import fun.fengwk.kkstudio.platform.http.ApiResponse;

/**
 * 测试期状态同步：把 {@link ApiResponse} 的业务 status 同步为 HTTP status。
 *
 * <p>生产实现是 Web 组合根的 {@code fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice}，Chat 模块不依赖
 * Web，因此独立 MVC 链以本类复刻同一语义，使控制器单测继续断言真实 HTTP 状态码而不是放宽为裸 200。
 */
@RestControllerAdvice
final class TestApiResponseStatusAdvice implements ResponseBodyAdvice<ApiResponse<?>> {

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
