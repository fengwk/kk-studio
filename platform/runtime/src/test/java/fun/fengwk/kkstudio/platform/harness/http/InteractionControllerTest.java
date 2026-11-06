package fun.fengwk.kkstudio.platform.harness.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.api.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessToolInputDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionOwnerDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionPageDTO;
import fun.fengwk.kkstudio.platform.interaction.InteractionQueryService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 待处理交互列表与工具调用输入路由的形状。 */
class InteractionControllerTest {

  private static final UUID THREAD = new UUID(0L, 101);
  private static final UUID INVOCATION = new UUID(0L, 100);
  private static final UUID SUBMISSION = new UUID(0L, 102);

  private InteractionQueryService queryService;
  private InteractionService interactionService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    queryService = mock(InteractionQueryService.class);
    interactionService = mock(InteractionService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new InteractionController(queryService, interactionService))
            .build();
  }

  @Test
  void listsInteractionsWithDefaultLimitAndUnionDiscriminators() throws Exception {
    // 默认 limit=50；每个条目只携带自身形态的字段，判别属性为 type。
    InteractionPageDTO page = new InteractionPageDTO();
    InteractionOwnerDTO owner = new InteractionOwnerDTO();
    owner.setKind("CHAT");
    owner.setId("chat-1");
    page.setItems(
        List.of(
            new InteractionDTO.Input(
                INVOCATION.toString(),
                THREAD.toString(),
                "session-1",
                THREAD.toString(),
                owner,
                "call-1",
                "ask_user",
                "{\"questions\":[]}",
                Instant.parse("2026-03-01T10:00:00Z")),
            new InteractionDTO.EnvironmentWait(
                THREAD.toString(),
                owner,
                "env-1",
                "dev",
                2,
                Instant.parse("2026-03-01T10:00:01Z"))));
    page.setTotal(2);
    when(queryService.listInteractions(null, null, 50)).thenReturn(page);

    mvc.perform(get("/api/harness/interactions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].type").value("INPUT"))
        .andExpect(jsonPath("$.items[0].toolInvocationId").value(INVOCATION.toString()))
        .andExpect(jsonPath("$.items[0].threadId").value(THREAD.toString()))
        .andExpect(jsonPath("$.items[0].owner.kind").value("CHAT"))
        .andExpect(jsonPath("$.items[0].argumentsJson").value("{\"questions\":[]}"))
        .andExpect(jsonPath("$.items[0].createdAt").hasJsonPath())
        .andExpect(jsonPath("$.items[0].waitingCount").doesNotExist())
        .andExpect(jsonPath("$.items[1].type").value("ENVIRONMENT_WAIT"))
        .andExpect(jsonPath("$.items[1].environmentId").value("env-1"))
        .andExpect(jsonPath("$.items[1].waitingCount").value(2))
        .andExpect(jsonPath("$.items[1].toolInvocationId").doesNotExist())
        .andExpect(jsonPath("$.total").value(2));

    verify(queryService).listInteractions(null, null, 50);
  }

  @Test
  void passesRootCursorAndLimitAsTypedParameters() throws Exception {
    UUID root = new UUID(0L, 7);
    when(queryService.listInteractions(root, "1772445600000:" + INVOCATION, 20))
        .thenReturn(new InteractionPageDTO());

    mvc.perform(
            get("/api/harness/interactions")
                .param("rootThreadId", root.toString())
                .param("cursor", "1772445600000:" + INVOCATION)
                .param("limit", "20"))
        .andExpect(status().isOk());

    verify(queryService).listInteractions(root, "1772445600000:" + INVOCATION, 20);
  }

  @Test
  void rejectsMalformedPathOrQueryValuesBeforeTheService() throws Exception {
    mvc.perform(get("/api/harness/interactions").param("rootThreadId", "not-a-uuid"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/harness/interactions").param("limit", "many"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(queryService);
  }

  @Test
  void rejectsIncompleteToolInputBeforeTheService() {
    InteractionController controller = new InteractionController(queryService, interactionService);

    assertThrows(
        DomainException.class,
        () ->
            controller.submitToolInput(
                THREAD,
                INVOCATION,
                new HarnessToolInputDTO.Answer(null, List.of(List.of("optionA"))),
                new MockHttpServletRequest()));
    assertThrows(
        DomainException.class,
        () ->
            controller.submitToolInput(
                THREAD,
                INVOCATION,
                new HarnessToolInputDTO.Answer(SUBMISSION, null),
                new MockHttpServletRequest()));

    verifyNoInteractions(interactionService);
  }

  @Test
  void answersAreSubmittedForThePathInvocationWithTheAuthenticatedActor() throws Exception {
    ToolInputAcceptance acceptance =
        new ToolInputAcceptance(
            THREAD,
            INVOCATION,
            new ToolInputReceipt(SUBMISSION, "alice", Instant.parse("2026-03-01T10:00:00Z")),
            false);
    when(interactionService.submitInput(any())).thenReturn(acceptance);

    mvc.perform(
            post(
                    "/api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/input",
                    THREAD,
                    INVOCATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"ANSWER","submissionId":"%s","answers":[["optionA","optionB"],["optionC"]]}
                    """
                        .formatted(SUBMISSION))
                .principal(() -> "  alice  "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.threadId").value(THREAD.toString()))
        .andExpect(jsonPath("$.toolInvocationId").value(INVOCATION.toString()))
        .andExpect(jsonPath("$.submissionId").value(SUBMISSION.toString()))
        .andExpect(jsonPath("$.actor").value("alice"))
        .andExpect(jsonPath("$.materialized").value(false));

    ArgumentCaptor<ToolInputSubmissionCommand> captor =
        ArgumentCaptor.forClass(ToolInputSubmissionCommand.class);
    verify(interactionService).submitInput(captor.capture());
    ToolInputSubmissionCommand command = captor.getValue();
    assertEquals(THREAD, command.threadId());
    assertEquals(INVOCATION, command.toolInvocationId());
    assertEquals(SUBMISSION, command.submissionId());
    assertEquals("alice", command.actor());
    assertFalse(command.declined());
    assertEquals(List.of(List.of("optionA", "optionB"), List.of("optionC")), command.answers());
  }

  @Test
  void declinesWithoutAnswersFallBackToLocalOperator() throws Exception {
    // 没有认证主体时使用固定的本地操作者；拒答不携带任何答案。
    when(interactionService.submitInput(any()))
        .thenReturn(
            new ToolInputAcceptance(
                THREAD,
                INVOCATION,
                new ToolInputReceipt(
                    SUBMISSION, "local-user", Instant.parse("2026-03-01T10:00:00Z")),
                true));

    mvc.perform(
            post(
                    "/api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/input",
                    THREAD,
                    INVOCATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"DECLINE","submissionId":"%s"}
                    """
                        .formatted(SUBMISSION)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actor").value("local-user"))
        .andExpect(jsonPath("$.materialized").value(true));

    ArgumentCaptor<ToolInputSubmissionCommand> captor =
        ArgumentCaptor.forClass(ToolInputSubmissionCommand.class);
    verify(interactionService).submitInput(captor.capture());
    ToolInputSubmissionCommand command = captor.getValue();
    assertTrue(command.declined());
    assertEquals(List.of(), command.answers());
    assertEquals("local-user", command.actor());
  }
}
