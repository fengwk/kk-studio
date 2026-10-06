package fun.fengwk.kkstudio.web.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/**
 * 真实 Spring MVC resolver 链上的客户端断连/已提交响应异常隔离集成测试。
 *
 * <p>复现基线：static resource handler 与返回 {@code Result} 的 handler 在响应已 commit 或客户端断连时，仍会 被既有 Result
 * 异常翻译链构造并写出 JSON body（即 “Broken pipe 后 JSON 二次写入”）。验证修复后这两类边界 以“无 body”结束，而不影响普通 API 错误翻译。
 */
@AutoConfigureMockMvc
class StudioDisconnectedClientExceptionResolverIntegrationTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;

  /** 测试意图：客户端断连（Broken pipe IOException）不得在被中断的响应上再写 JSON body（body 保持为空）。 */
  @Test
  void brokenPipeDoesNotWriteSecondaryJsonBody() throws Exception {
    mockMvc.perform(get("/__probe/disconnect")).andExpect(content().string(""));
  }

  /** 测试意图：Tomcat {@code ClientAbortException} 同样被视为断连，不产生第二次 body 写。 */
  @Test
  void clientAbortExceptionDoesNotWriteSecondaryJsonBody() throws Exception {
    mockMvc.perform(get("/__probe/client-abort")).andExpect(content().string(""));
  }

  /** 测试意图：已提交响应（static resource 形态）其后异常必须保留已写出的首段，不得追加 JSON。 */
  @Test
  void committedResponseKeepsFirstBodyWithoutSecondaryJson() throws Exception {
    mockMvc
        .perform(get("/__probe/committed"))
        .andExpect(status().isOk())
        .andExpect(content().string("first-part"));
  }

  /** 测试意图：非断连 IOException 仍走既有 Result 异常翻译，返回 500 envelope 而非被静默吞掉。 */
  @Test
  void localIoExceptionStillReturnsUnifiedResultEnvelope() throws Exception {
    mockMvc
        .perform(get("/__probe/local-io"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.success").value(false));
  }

  /** 测试意图：未提交的普通 API 错误翻译与状态码保持不变。 */
  @Test
  void normalApiErrorKeepsUnifiedResultEnvelope() throws Exception {
    mockMvc
        .perform(get("/__probe/api-error"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.success").value(false));
  }

  /** 测试意图：静态资源 404 的既有路由与状态码不受断连隔离影响。 */
  @Test
  void missingStaticResourceStillNotFound() throws Exception {
    mockMvc.perform(get("/studio-missing.txt")).andExpect(status().isNotFound());
  }
}
