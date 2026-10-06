package fun.fengwk.kkstudio.web.advice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * 客户端断连与响应已提交边界的最前置异常隔离。
 *
 * <p>既有 {@code @RestControllerAdvice} 链对 static resource handler（没有 HandlerMethod）以及返回 {@code
 * Result} 的 handler 都会为异常构造 Result body。当响应已经 commit（流式资源已写出首段）或客户端已经 断开（Tomcat 以 {@code
 * ClientAbortException}、或以 “Broken pipe” / “connection reset by peer” 消息的 {@code IOException}
 * 表达）时，这次 body 写入既无法送达，还会触发第二次写失败和噪音堆栈。该 resolver 在 默认 resolver 之前命中这两类边界，返回空 {@link
 * ModelAndView}，让请求以“已处理、无 body”结束。
 *
 * <p>断连判定复用 Spring 的 {@link DisconnectedClientHelper}（识别容器断连异常与特定消息，并排除 {@code RestClientException}
 * 等上游连接问题），因此普通本地 {@code IOException} 仍返回 {@code null} 交给既有 Result 异常翻译链，不会被静默吞掉。正常（未提交）API
 * 错误同样不受影响。
 */
@Slf4j
@Component
public class StudioDisconnectedClientExceptionResolver
    implements HandlerExceptionResolver, Ordered {

  private static final DisconnectedClientHelper DISCONNECTED_CLIENT_HELPER =
      new DisconnectedClientHelper(StudioDisconnectedClientExceptionResolver.class.getName());

  @Override
  public ModelAndView resolveException(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      // 客户端已经离开，响应不可再写；按 Spring 约定只记录一行 debug/trace，不产生第二次 body 写。
      DISCONNECTED_CLIENT_HELPER.checkAndLogClientDisconnectedException(ex);
      return new ModelAndView();
    }
    if (response.isCommitted()) {
      // 响应已 commit，新的错误 body 无法正确送达；保留已经写出的内容，避免第二次写失败。
      log.warn(
          "Response already committed, ignoring exception for {} {}",
          request.getMethod(),
          request.getRequestURI(),
          ex);
      return new ModelAndView();
    }
    return null;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
