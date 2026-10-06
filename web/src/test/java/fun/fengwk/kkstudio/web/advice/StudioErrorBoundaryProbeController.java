package fun.fengwk.kkstudio.web.advice;

import fun.fengwk.convention4j.api.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 断连/已提交响应异常隔离测试探针。
 *
 * <p>仅存在于 test classpath，被 {@code WebTestApplication} 组件扫描注册；用于在真实 Spring MVC resolver
 * 链上复现客户端断连、已提交响应与正常 API 错误三类边界。
 */
@RestController
public class StudioErrorBoundaryProbeController {

  /** 客户端断连：Tomcat 以消息为 {@code Broken pipe} 的 IOException 或 ClientAbortException 表达。 */
  @GetMapping("/__probe/disconnect")
  public Result<Void> disconnect() throws IOException {
    throw new IOException("Broken pipe");
  }

  @GetMapping("/__probe/client-abort")
  public Result<Void> clientAbort() throws IOException {
    throw new ClientAbortException(new IOException("Connection reset"));
  }

  /** 非断连 IOException：必须继续按正常 API 错误翻译，不能被断连隔离吞掉。 */
  @GetMapping("/__probe/local-io")
  public Result<Void> localIo() throws IOException {
    throw new IOException("simulated local failure");
  }

  /** 已提交响应：与 static resource handler 一样经 output stream 写出首段并 commit，其后的异常不得再次写 body。 */
  @GetMapping("/__probe/committed")
  public Result<Void> committed(HttpServletResponse response) throws IOException {
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    response.getOutputStream().write("first-part".getBytes(StandardCharsets.UTF_8));
    response.flushBuffer();
    throw new IllegalStateException("failure after commit");
  }

  /** 正常 API 错误：未提交响应，应保持既有 Result envelope 与状态码。 */
  @GetMapping("/__probe/api-error")
  public Result<Void> apiError() {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "probe bad request");
  }
}
