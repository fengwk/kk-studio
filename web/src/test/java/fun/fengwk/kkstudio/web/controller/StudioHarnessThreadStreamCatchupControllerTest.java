package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.harness.builtin.realtime.HarnessStreamCatchupService;
import fun.fengwk.kkstudio.platform.harness.http.HarnessThreadStreamCatchupController;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.HttpBoundaryConfiguration;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;

import java.util.UUID;

/** 冻结的流式追帧 HTTP 契约：成功固定 202；请求形状严格 400；Thread 不存在 404；不匹配/过期 attempt 仍是 202 no-op。 */
class StudioHarnessThreadStreamCatchupControllerTest {

  private static final UUID THREAD = new UUID(0L, 1L);
  private static final UUID INVOCATION = new UUID(0L, 2L);
  private static final String THREAD_TEXT = THREAD.toString();
  private static final String INVOCATION_TEXT = INVOCATION.toString();

  private HarnessStreamCatchupService catchupService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    catchupService = mock(HarnessStreamCatchupService.class);
    HarnessThreadStreamCatchupController controller =
        new HarnessThreadStreamCatchupController(catchupService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(
                new StudioProblemAdvice(new StudioMessageService()),
                new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
                new HttpBoundaryConfiguration.CanonicalUuidBinding())
            .build();
  }

  @Test
  void acceptedRequestReturns202AndForwardsExactTarget() throws Exception {
    doNothing().when(catchupService).catchUp(THREAD, INVOCATION, 3);

    mockMvc
        .perform(
            post("/api/harness/builtin/threads/{threadId}/stream-catchup", THREAD_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));

    verify(catchupService).catchUp(THREAD, INVOCATION, 3);
  }

  @Test
  void staleAttemptNoOpStillReturns202() throws Exception {
    // 服务对过期 attempt / 不匹配关联做 no-op；HTTP 仍表示请求已被接受。
    mockMvc
        .perform(
            post("/api/harness/builtin/threads/{threadId}/stream-catchup", THREAD_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isAccepted());
  }

  @Test
  void malformedShapesAreStrictlyRejectedWith400() throws Exception {
    for (String body :
        new String[] {
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":\"3\"}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":3.0}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":2147483648}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":9223372036854775808}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":null}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":true}",
          "{\"invocationId\":123,\"attempt\":3}",
          "{\"invocationId\":null,\"attempt\":3}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":3,\"extra\":true}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":3,\"attempt\":3}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":3} trailing",
          "{\"invocationId\":\"00000000-0000-0000-0000-00000000002\",\"attempt\":3}",
          "{\"attempt\":3}",
          "{\"invocationId\":\"" + INVOCATION_TEXT + "\"}"
        }) {
      mockMvc
          .perform(
              post("/api/harness/builtin/threads/{threadId}/stream-catchup", THREAD_TEXT)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }

    mockMvc
        .perform(
            post("/api/harness/builtin/threads/{threadId}/stream-catchup", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest());

    verify(catchupService, never()).catchUp(any(), any(), anyInt());
  }

  @Test
  void invalidAttemptReturnsValidationProblem() throws Exception {
    // 领域拒绝必须经真实 HTTP advice 输出 validation，而不是伪造成功。
    doThrow(DomainException.validation("stream_catchup", "attempt must be positive"))
        .when(catchupService)
        .catchUp(THREAD, INVOCATION, 0);
    mockMvc
        .perform(
            post("/api/harness/builtin/threads/{threadId}/stream-catchup", THREAD_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  /** 旧路径不保留兼容入口。 */
  @Test
  void legacyPathIsNotMapped() throws Exception {
    mockMvc
        .perform(
            post("/api/harness/threads/{threadId}/stream-catchup", THREAD_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isNotFound());
    verify(catchupService, never()).catchUp(any(), any(), anyInt());
  }

  private static String validBody() {
    return "{\"invocationId\":\"" + INVOCATION_TEXT + "\",\"attempt\":3}";
  }
}
