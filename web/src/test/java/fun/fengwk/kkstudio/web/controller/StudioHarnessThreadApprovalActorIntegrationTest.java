package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/**
 * 公开 Tool approval 入口在真实 Spring MVC 与生产 Jackson 配置下的身份边界。
 *
 * <p>测试意图：请求体只承载 {@code decision}/{@code decisionId}/{@code reason}，任何伪造的 {@code actor} 都属于未知字段，
 * 必须在反序列化阶段被拒为 400 且不进入 controller；只有身份字段不被携带时请求才会继续被处理。操作者身份只能由服务端认证主体决定， 因此客户端的这一约束在 HTTP
 * 边界上就必须成立，而不是靠前端自律。
 */
@AutoConfigureMockMvc
class StudioHarnessThreadApprovalActorIntegrationTest extends WebPostgresTestSupport {

  private static final String THREAD = "00000000-0000-0000-0000-0000000000a1";
  private static final String INVOCATION = "00000000-0000-0000-0000-0000000000a2";
  private static final String DECISION_ID = "00000000-0000-0000-0000-0000000000d1";

  @Autowired private MockMvc mockMvc;

  /** 意图：携带 {@code actor} 的审批请求在真实反序列化阶段即被 400 拒绝，并给出未知字段细节。 */
  @Test
  void forgedActorFieldIsRejectedAtTheHttpBoundary() throws Exception {
    mockMvc
        .perform(
            put(approvalPath())
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + DECISION_ID
                        + "\",\"actor\":\"forged\",\"reason\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors.type").value("about:blank"))
        .andExpect(jsonPath("$.errors.detail").value("unknown tool approval field: actor"));
  }

  /**
   * 意图：不携带身份字段的合法形状请求必须通过反序列化并进入领域判定——这里目标 Thread 不存在，Runtime 以 {@code APPROVAL_NOT_APPLICABLE}
   * 冲突作答，而不是在 wire 层被 400 拒绝。
   */
  @Test
  void actorlessBodyReachesDomainDecision() throws Exception {
    mockMvc
        .perform(
            put(approvalPath())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + DECISION_ID
                        + "\",\"reason\":null}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors.reason").value("APPROVAL_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.errors.detail").value("thread " + THREAD + " does not exist"));
  }

  private static String approvalPath() {
    return "/api/harness/threads/" + THREAD + "/tool-invocations/" + INVOCATION + "/approval";
  }
}
