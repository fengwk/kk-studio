package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskRequest;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskStatus;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.Optional;
import java.util.UUID;

/** Agent 任务 HTTP 入口：提交映射为控制器发起的回执任务，观察 / 停止按票据投影状态，错误经统一 advice 映射。 */
class StudioAgentTaskControllerTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("worker", new ModelSelection("provider", "model", "v1"), null);
  private static final UUID TICKET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

  private AgentTaskService agentTasks;
  private AgentBranchSettingsMaterializer materializer;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    agentTasks = mock(AgentTaskService.class);
    materializer = mock(AgentBranchSettingsMaterializer.class);
    when(materializer.materialize("worker")).thenReturn(SETTINGS);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StudioAgentTaskController(agentTasks, materializer))
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void submitAcceptsAControllerTaskAndReturnsItsTicket() throws Exception {
    mockMvc
        .perform(
            post("/api/agent-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agent\":\"worker\",\"prompt\":\"summarize\",\"maxTurns\":4}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.state").value("RUNNING"))
        .andExpect(jsonPath("$.data.agent").value("worker"));

    ArgumentCaptor<AgentTaskRequest> captor = ArgumentCaptor.forClass(AgentTaskRequest.class);
    verify(agentTasks).submit(captor.capture());
    AgentTaskRequest request = captor.getValue();
    assertEquals(SpawnTrigger.CONTROLLER, request.trigger());
    assertNull(request.parentThreadId());
    assertNull(request.continuation());
    assertEquals(SETTINGS, request.settings());
    assertEquals(4, request.budget().maxTurns());
    assertEquals(1, request.commands().size());
    assertEquals(request.commands().getFirst().requestHash(), request.requestHash());
  }

  @Test
  void submitRejectsBlankInputsAndNonPositiveBudgetWithoutSubmitting() throws Exception {
    mockMvc
        .perform(
            post("/api/agent-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agent\":\"worker\",\"prompt\":\"  \"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/agent-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"work\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/agent-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agent\":\"worker\",\"prompt\":\"work\",\"maxTurns\":0}"))
        .andExpect(status().isBadRequest());
    verify(agentTasks, never()).submit(any());
  }

  @Test
  void unknownAgentIsABadRequest() throws Exception {
    when(materializer.materialize("ghost"))
        .thenThrow(new IllegalArgumentException("subagent not found: ghost"));
    mockMvc
        .perform(
            post("/api/agent-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agent\":\"ghost\",\"prompt\":\"work\"}"))
        .andExpect(status().isBadRequest());
    verify(agentTasks, never()).submit(any());
  }

  @Test
  void getReportsARunningTaskWithoutResult() throws Exception {
    when(agentTasks.observe(TICKET_ID))
        .thenReturn(
            Optional.of(
                new AgentTaskStatus(
                    TICKET_ID, THREAD_ID, "worker", AgentTaskStatus.State.RUNNING, null)));
    mockMvc
        .perform(get("/api/agent-tasks/" + TICKET_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ticketId").value(TICKET_ID.toString()))
        .andExpect(jsonPath("$.data.threadId").value(THREAD_ID.toString()))
        .andExpect(jsonPath("$.data.state").value("RUNNING"))
        .andExpect(jsonPath("$.data.report").doesNotExist());
  }

  @Test
  void getReturnsTheFrozenResultOfAFinishedTask() throws Exception {
    when(agentTasks.observe(TICKET_ID))
        .thenReturn(
            Optional.of(
                new AgentTaskStatus(
                    TICKET_ID,
                    THREAD_ID,
                    "worker",
                    AgentTaskStatus.State.COMPLETED,
                    new ThreadJoinReceipt(
                        TICKET_ID,
                        THREAD_ID,
                        "worker",
                        ThreadJoinOutcome.COMPLETED,
                        "summarize",
                        "the summary",
                        null,
                        null))));
    mockMvc
        .perform(get("/api/agent-tasks/" + TICKET_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("COMPLETED"))
        .andExpect(jsonPath("$.data.report").value("the summary"));
  }

  @Test
  void unknownTicketIsNotFoundAndMalformedTicketIsABadRequest() throws Exception {
    when(agentTasks.observe(TICKET_ID)).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/agent-tasks/" + TICKET_ID)).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/agent-tasks/not-a-uuid")).andExpect(status().isBadRequest());
  }

  @Test
  void stopStopsTheTaskAndReturnsItsStatus() throws Exception {
    ThreadJoinReceipt cancelled =
        new ThreadJoinReceipt(
            TICKET_ID,
            THREAD_ID,
            "worker",
            ThreadJoinOutcome.CANCELLED,
            "summarize",
            null,
            "partial",
            "stopped");
    when(agentTasks.observe(TICKET_ID))
        .thenReturn(
            Optional.of(
                new AgentTaskStatus(
                    TICKET_ID, THREAD_ID, "worker", AgentTaskStatus.State.CANCELLED, cancelled)));
    mockMvc
        .perform(post("/api/agent-tasks/" + TICKET_ID + "/stop"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("CANCELLED"))
        .andExpect(jsonPath("$.data.partialResult").value("partial"))
        .andExpect(jsonPath("$.data.error").value("stopped"));
    verify(agentTasks).stop(TICKET_ID);
  }

  @Test
  void stopOfAnUnknownTaskIsNotFound() throws Exception {
    doThrow(new HarnessRuntimeNotFoundException("gone")).when(agentTasks).stop(TICKET_ID);
    mockMvc
        .perform(post("/api/agent-tasks/" + TICKET_ID + "/stop"))
        .andExpect(status().isNotFound());
  }
}
