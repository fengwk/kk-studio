package fun.fengwk.kkstudio.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.harness.http.InteractionController;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.interaction.InteractionQueryService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;

import java.util.UUID;

/** 人工问卷提交的 HTTP 边界：多形态请求的具体子类型在严格 mapper 与必填校验下拒绝。 */
class InteractionControllerProblemWireTest {

  private static final UUID THREAD = new UUID(0L, 101);
  private static final UUID INVOCATION = new UUID(0L, 100);

  private final InteractionQueryService queryService = mock(InteractionQueryService.class);
  private final InteractionService interactionService = mock(InteractionService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new InteractionController(queryService, interactionService))
          .setControllerAdvice(
              new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
              new ProblemAdvice(new MessageService()))
          .setMessageConverters(
              new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
          .build();

  /** ANSWER 子类型的必填字段由边界拒绝，不触达服务，也不再依赖控制器的宽泛 catch。 */
  @Test
  void answerSubtypeRequiresSubmissionIdAndAnswers() throws Exception {
    assertInvalidProblem("{\"type\":\"ANSWER\",\"answers\":[]}", "submissionId is required");
    assertInvalidProblem(
        "{\"type\":\"ANSWER\",\"submissionId\":\"00000000-0000-0000-0000-000000000001\"}",
        "answers is required");
    verifyNoInteractions(interactionService);
  }

  /** DECLINE 子类型同样按具体子类型校验，而不是只看外层接口。 */
  @Test
  void declineSubtypeRequiresSubmissionId() throws Exception {
    assertInvalidProblem("{\"type\":\"DECLINE\"}", "submissionId is required");
    verifyNoInteractions(interactionService);
  }

  /** 答案内层 null 元素也是缺失答案，由同一必填校验指出数组下标。 */
  @Test
  void nullAnswerElementIsRejectedWithItsIndex() throws Exception {
    assertInvalidProblem(
        "{\"type\":\"ANSWER\",\"submissionId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"answers\":[[\"optionA\",null]]}",
        "answers[0][1] is required");
    verifyNoInteractions(interactionService);
  }

  /** 未知字段在严格 mapper 处拒绝，detail 指出路径。 */
  @Test
  void unknownFieldIsRejectedByTheStrictMapper() throws Exception {
    assertInvalidProblem(
        "{\"type\":\"DECLINE\",\"submissionId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"actor\":\"forged\"}",
        "actor is not allowed");
    verifyNoInteractions(interactionService);
  }

  private void assertInvalidProblem(String body, String detail) throws Exception {
    mvc.perform(
            post(
                    "/api/harness/threads/{threadId}/tool-invocations/{invocationId}/input",
                    THREAD,
                    INVOCATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value(detail));
    verifyNoInteractions(interactionService, queryService);
  }
}
