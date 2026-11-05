package fun.fengwk.kkstudio.web.advice;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import fun.fengwk.kkstudio.platform.http.ApiResponse;

/**
 * 过渡期将显式 {@link ApiResponse} 翻译为资源响应。
 *
 * <p>同时处理直接返回和 {@code ResponseEntity<ApiResponse<?>>}，保留后者的响应头；其他响应不受影响。
 */
@RestControllerAdvice
public class ApiResponseStatusAdvice implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    Class<?> type = returnType.getParameterType();
    if (ApiResponse.class.isAssignableFrom(type)) {
      return true;
    }
    return ResponseEntity.class.isAssignableFrom(type)
        && ApiResponse.class.isAssignableFrom(
            ResolvableType.forMethodParameter(returnType)
                .as(ResponseEntity.class)
                .getGeneric(0)
                .resolve(Object.class));
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body instanceof ApiResponse<?> envelope) {
      int status = envelope.getStatus();
      if (!envelope.isSuccess()) {
        response.setStatusCode(HttpStatusCode.valueOf(status));
        return body;
      }
      Object data = envelope.getData();
      response.setStatusCode(
          HttpStatusCode.valueOf(
              data == null && status == 200 ? HttpStatus.NO_CONTENT.value() : status));
      return data;
    }
    return body;
  }
}
