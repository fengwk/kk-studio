package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** HTTP contract for the global realtime Stream retention policy. */
@AutoConfigureMockMvc
class StudioHarnessRealtimeStreamPolicyControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;

  @Test
  void readsReplacesAndValidatesTheGlobalPolicy() throws Exception {
    mockMvc
        .perform(get("/api/harness/realtime-stream-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.maxLength").value(5_000));

    mockMvc
        .perform(
            put("/api/harness/realtime-stream-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxLength\":100000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.maxLength").value(100_000));

    mockMvc
        .perform(get("/api/harness/realtime-stream-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.maxLength").value(100_000));

    mockMvc
        .perform(
            put("/api/harness/realtime-stream-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxLength\":0}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/realtime-stream-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
