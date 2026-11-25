package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.UUID;

/**
 * 公开 Tool approval 入口在真实 Spring MVC 与生产 Jackson 配置下的身份边界。
 *
 * <p>测试意图：请求体只承载 {@code decision}/{@code decisionId}/{@code reason}，任何伪造的 {@code actor} 都属于未知字段，
 * 必须在反序列化阶段被拒为 400 且不进入 controller；只有身份字段不被携带时请求才会继续被处理。操作者身份只能由服务端认证主体决定， 因此客户端的这一约束在 HTTP
 * 边界上就必须成立，而不是靠前端自律。
 */
@AutoConfigureMockMvc
class HarnessThreadApprovalActorIntegrationTest extends WebPostgresTestSupport {

  private static final String THREAD = "00000000-0000-0000-0000-0000000000a1";
  private static final String INVOCATION = "00000000-0000-0000-0000-0000000000a2";
  private static final String DECISION_ID = "00000000-0000-0000-0000-0000000000d1";

  @Autowired private MockMvc mockMvc;
  @MockitoSpyBean private ResourceAccess access;
  private Cookie session;

  @BeforeEach
  void login() throws Exception {
    session =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .header("Origin", "http://localhost")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"kkstudio\",\"password\":\"kkstudio\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("studio_session");
  }

  /** 意图：携带 {@code actor} 的审批请求在真实反序列化阶段即被 400 拒绝，并给出未知字段细节。 */
  @Test
  void forgedActorFieldIsRejectedAtTheHttpBoundary() throws Exception {
    doNothing().when(access).requireThread(any(), eq(UUID.fromString(THREAD)));
    mockMvc
        .perform(
            put(approvalPath())
                .cookie(session)
                .header("Origin", "http://localhost")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + DECISION_ID
                        + "\",\"actor\":\"forged\",\"reason\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("actor is not allowed"));
  }

  /** 合法形状也必须通过归属准入；不存在的 Thread 返回统一的 404，不回显目标 ID。 */
  @Test
  void actorlessBodyStillRequiresAnAccessibleThread() throws Exception {
    mockMvc
        .perform(
            put(approvalPath())
                .cookie(session)
                .header("Origin", "http://localhost")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + DECISION_ID
                        + "\",\"reason\":null}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.reason").value("RESOURCE_NOT_FOUND"));
  }

  private static String approvalPath() {
    return "/api/harness/threads/" + THREAD + "/tool-invocations/" + INVOCATION + "/approval";
  }
}
