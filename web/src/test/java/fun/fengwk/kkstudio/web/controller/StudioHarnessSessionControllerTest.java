package fun.fengwk.kkstudio.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Runtime Session 查询的 HTTP 契约：摘要列表与 parent-linked Entry tree 都走新 Session 路径。 */
class StudioHarnessSessionControllerTest {

  private static final UUID SESSION_ID = new UUID(0L, 1L);

  private HarnessOwnerQueryService harnessQueryService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    harnessQueryService = mock(HarnessOwnerQueryService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StudioHarnessSessionController(harnessQueryService))
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void listsThreadSummariesAndEntriesBySession() throws Exception {
    HarnessThreadSummaryDTO thread = new HarnessThreadSummaryDTO();
    thread.setThreadId(new UUID(0L, 2L).toString());
    thread.setCreatedAt(Instant.parse("2026-08-10T00:00:00Z"));
    thread.setUpdatedAt(Instant.parse("2026-08-10T00:01:00Z"));
    thread.setStatus("IDLE");
    HarnessModelSelectionDTO model = new HarnessModelSelectionDTO();
    model.setProviderName("openai");
    model.setModelName("gpt-5");
    model.setVariant("default");
    thread.setModel(model);
    thread.setHeadMessagePreview("hello");
    when(harnessQueryService.listThreadSummaries(SESSION_ID)).thenReturn(List.of(thread));

    mockMvc
        .perform(get("/api/ai/runtime/sessions/" + SESSION_ID + "/threads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].threadId").value(thread.getThreadId()))
        .andExpect(jsonPath("$.data[0].status").value("IDLE"))
        .andExpect(jsonPath("$.data[0].model.providerName").value("openai"))
        .andExpect(jsonPath("$.data[0].model.modelName").value("gpt-5"))
        .andExpect(jsonPath("$.data[0].model.variant").value("default"))
        .andExpect(jsonPath("$.data[0].headMessagePreview").value("hello"));
    verify(harnessQueryService).listThreadSummaries(SESSION_ID);

    Entry root =
        new Entry(
            new UUID(0L, 3L),
            SESSION_ID,
            null,
            new RootPayload(
                new BranchSettings(
                    null, "assistant", new ModelSelection("openai", "gpt-5", "default"))),
            Instant.parse("2026-08-10T00:00:00Z"));
    when(harnessQueryService.listSessionEntries(SESSION_ID)).thenReturn(List.of(root));

    mockMvc
        .perform(get("/api/ai/runtime/sessions/" + SESSION_ID + "/entries"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].entryId").value(root.id().toString()))
        .andExpect(jsonPath("$.data[0].sessionId").value(SESSION_ID.toString()))
        .andExpect(jsonPath("$.data[0].parentEntryId").value((Object) null))
        .andExpect(jsonPath("$.data[0].entryType").value("ROOT"));
    verify(harnessQueryService).listSessionEntries(SESSION_ID);
  }

  @Test
  void translatesMissingSessionToNotFound() throws Exception {
    when(harnessQueryService.listThreadSummaries(SESSION_ID))
        .thenThrow(new HarnessRuntimeNotFoundException("session is missing"));

    mockMvc
        .perform(get("/api/ai/runtime/sessions/" + SESSION_ID + "/threads"))
        .andExpect(status().isNotFound());
  }
}
