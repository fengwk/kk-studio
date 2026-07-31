package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** HTTP contract for UNBOUND Thread creation, bootstrap/rebind and typed mailbox commands. */
@AutoConfigureMockMvc
class StudioHarnessThreadControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void createsUnboundThreadBootstrapsAndAcceptsTypedInputsWithHttpBoundaries() throws Exception {
    // POST /api/ai/runtime/threads takes no body and yields an UNBOUND Thread.
    MvcResult createdThread =
        mockMvc
            .perform(post("/api/ai/runtime/threads"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.threadId").isString())
            .andExpect(jsonPath("$.data.status").value("UNBOUND"))
            .andExpect(jsonPath("$.data.headEntryId").doesNotExist())
            .andExpect(jsonPath("$.data.sessionId").doesNotExist())
            .andReturn();
    String threadId = data(createdThread).path("threadId").asText();
    assertTrue(threadId.matches("\\d+"));
    String epoch = data(createdThread).path("executionEpoch").asText();
    assertEquals("0", epoch);
    mockMvc
        .perform(
            get("/api/ai/runtime/threads/{id}/events/stream", threadId)
                .param("afterRevision", " 0 "))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/ai/runtime/threads/{id}/events/stream", threadId)
                .header("Last-Event-ID", " 1 "))
        .andExpect(status().isBadRequest());

    // UNBOUND Thread refuses mailbox input (409).
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/messages", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"content\":\"hello\",\"clientMessageId\":\"pre-bootstrap\","
                        + "\"expectedExecutionEpoch\":0}"))
        .andExpect(status().isConflict());

    // Bootstrap atomically creates Session/ROOT/RUNTIME_CONFIG and binds the head.
    MvcResult bootstrapped =
        mockMvc
            .perform(
                post("/api/ai/runtime/threads/{id}/bootstrap", threadId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"title\":\"web-session\",\"agentDefinitionId\":\"1\","
                            + "\"yoloEnabled\":false,\"expectedExecutionEpoch\":0}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.session.sessionId").isString())
            .andExpect(jsonPath("$.data.thread.threadId").value(threadId))
            .andExpect(jsonPath("$.data.thread.status").value("IDLE"))
            .andReturn();
    JsonNode boot = data(bootstrapped);
    String sessionId = boot.path("session").path("sessionId").asText();
    epoch = boot.path("thread").path("executionEpoch").asText();
    assertEquals("1", epoch);
    String configEntryId = boot.path("thread").path("headEntryId").asText();

    // Session has no standalone creation endpoint; it only exists through Thread bootstrap.
    mockMvc
        .perform(
            post("/api/ai/runtime/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"standalone\"}"))
        .andExpect(status().isMethodNotAllowed());
    mockMvc
        .perform(get("/api/ai/runtime/sessions/{id}", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(sessionId))
        .andExpect(jsonPath("$.data.rootSessionId").doesNotExist())
        .andExpect(jsonPath("$.data.parentSessionId").doesNotExist())
        .andExpect(jsonPath("$.data.parentInvocationId").doesNotExist())
        .andExpect(jsonPath("$.data.depth").doesNotExist());
    mockMvc
        .perform(get("/api/ai/runtime/threads/{id}", threadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(sessionId))
        .andExpect(jsonPath("$.data.headEntryId").value(configEntryId))
        .andExpect(jsonPath("$.data.status").value("IDLE"))
        .andExpect(jsonPath("$.data.inputSequence").value(0));
    JsonNode entries = readData(get("/api/ai/runtime/sessions/{id}/entries", sessionId));
    assertEquals(2, entries.size());
    assertTrue(entries.get(0).path("sessionId").isMissingNode());
    String rootEntryId = entries.get(0).path("entryId").asText();

    // A bootstrap replay on an already bound Thread is a 409.
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/bootstrap", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"again\",\"agentDefinitionId\":\"1\","
                        + "\"yoloEnabled\":false,\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isConflict());

    MvcResult firstMessage =
        mockMvc
            .perform(
                post("/api/ai/runtime/threads/{id}/messages", threadId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"content\":\"hello\",\"clientMessageId\":\"message-1\","
                            + "\"expectedExecutionEpoch\":1}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.inputId").isString())
            .andExpect(jsonPath("$.data.threadId").value(threadId))
            .andExpect(jsonPath("$.data.inputType").value("USER_MESSAGE"))
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andExpect(jsonPath("$.data.resolvedAt").doesNotExist())
            .andReturn();
    String inputId = data(firstMessage).path("inputId").asText();

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/messages", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"content\":\"hello\",\"clientMessageId\":\"message-1\","
                        + "\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputId").value(inputId));
    // final command path is key-idempotent: same clientMessageId replays the original input.
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/messages", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"content\":\"changed\",\"clientMessageId\":\"message-1\","
                        + "\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputId").value(inputId))
        .andExpect(jsonPath("$.data.status").value("QUEUED"));

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/messages/custom", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"role\":\"system\",\"content\":\"context\","
                        + "\"clientMessageId\":\"custom-1\",\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("CUSTOM_MESSAGE"))
        .andExpect(jsonPath("$.data.status").value("QUEUED"));
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/messages/custom", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"role\":\"assistant\",\"content\":\"forbidden\","
                        + "\"clientMessageId\":\"custom-2\",\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/yolo", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"yoloEnabled\":true,\"clientMessageId\":\"yolo-1\","
                        + "\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("SET_YOLO"));
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/agent", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentDefinitionId\":\"1\",\"clientMessageId\":\"agent-1\","
                        + "\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("SET_AGENT"));
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/model", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"modelId\":\"1\",\"variant\":\"default\","
                        + "\"clientMessageId\":\"model-1\",\"expectedExecutionEpoch\":1}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("SET_MODEL"));

    // A stale expectedExecutionEpoch is a 409 for every external mutation.
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/messages", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"content\":\"stale\",\"clientMessageId\":\"stale-1\","
                        + "\"expectedExecutionEpoch\":99}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/stop", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedExecutionEpoch\":99}"))
        .andExpect(status().isConflict());

    MvcResult stop =
        mockMvc
            .perform(
                post("/api/ai/runtime/threads/{id}/stop", threadId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedExecutionEpoch\":1}"))
            .andExpect(status().isOk())
            // Long fields are stringified by convention4j for JS-safe wire form.
            .andExpect(jsonPath("$.data.executionEpoch").isString())
            .andExpect(jsonPath("$.data.cancelledInputs.length()").value(5))
            .andReturn();
    String firstEpoch = data(stop).path("executionEpoch").asText();
    assertEquals("2", firstEpoch);
    // stop is pure epoch fencing; repeated stop advances epoch with no remaining queued inputs.
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/stop", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedExecutionEpoch\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.executionEpoch").isString())
        .andExpect(jsonPath("$.data.executionEpoch").value(not(firstEpoch)))
        .andExpect(jsonPath("$.data.cancelledInputs.length()").value(0));

    // PUT /head: same-session rewind onto ROOT.
    MvcResult rewound =
        mockMvc
            .perform(
                put("/api/ai/runtime/threads/{id}/head", threadId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"headEntryId\":\"" + rootEntryId + "\",\"expectedExecutionEpoch\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.threadId").value(threadId))
            .andExpect(jsonPath("$.data.headEntryId").value(rootEntryId))
            .andExpect(jsonPath("$.data.status").value("IDLE"))
            .andReturn();
    assertEquals("4", data(rewound).path("executionEpoch").asText());
    // The command response projects the runtime Thread record; Session is derived on the query
    // side from the head Entry.
    mockMvc
        .perform(get("/api/ai/runtime/threads/{id}", threadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.headEntryId").value(rootEntryId))
        .andExpect(jsonPath("$.data.sessionId").value(sessionId));

    // Cross-session rebind keeps the same Thread and switches its derived Session.
    String otherThreadId =
        data(mockMvc
                .perform(post("/api/ai/runtime/threads"))
                .andExpect(status().isCreated())
                .andReturn())
            .path("threadId")
            .asText();
    JsonNode otherBoot =
        data(
            mockMvc
                .perform(
                    post("/api/ai/runtime/threads/{id}/bootstrap", otherThreadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"title\":\"other\",\"agentDefinitionId\":\"1\","
                                + "\"yoloEnabled\":false,\"expectedExecutionEpoch\":0}"))
                .andExpect(status().isCreated())
                .andReturn());
    String otherSessionId = otherBoot.path("session").path("sessionId").asText();
    String otherEntryId =
        readData(get("/api/ai/runtime/sessions/{id}/entries", otherSessionId))
            .get(0)
            .path("entryId")
            .asText();
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/head", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"headEntryId\":\"" + otherEntryId + "\",\"expectedExecutionEpoch\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(threadId))
        .andExpect(jsonPath("$.data.headEntryId").value(otherEntryId));
    mockMvc
        .perform(get("/api/ai/runtime/threads/{id}", threadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(otherSessionId));

    // Unbind: a null head returns the Thread to UNBOUND.
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/head", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedExecutionEpoch\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UNBOUND"))
        .andExpect(jsonPath("$.data.headEntryId").doesNotExist());

    // Head update error mapping: stale epoch 409, unknown entry 404, invalid ids 400.
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/head", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"headEntryId\":\"" + rootEntryId + "\",\"expectedExecutionEpoch\":4}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/head", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"headEntryId\":\"999999999999\",\"expectedExecutionEpoch\":6}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/head", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"headEntryId\":\"abc\",\"expectedExecutionEpoch\":6}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/head", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"headEntryId\":\"" + rootEntryId + "\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/head", "999999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedExecutionEpoch\":0}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/bootstrap", "999999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentDefinitionId\":\"1\",\"yoloEnabled\":false,"
                        + "\"expectedExecutionEpoch\":0}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/{id}/bootstrap", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentDefinitionId\":\"999999999999\",\"yoloEnabled\":false,"
                        + "\"expectedExecutionEpoch\":6}"))
        .andExpect(status().isNotFound());

    // Session-scoped Thread creation/listing routes are gone.
    mockMvc
        .perform(
            post("/api/ai/runtime/sessions/{id}/threads", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromEntryId\":\"" + rootEntryId + "\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/ai/runtime/sessions/{id}/threads", sessionId))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(get("/api/ai/runtime/threads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.threadId=='" + threadId + "')]").exists())
        .andExpect(jsonPath("$.data.items[?(@.threadId=='" + otherThreadId + "')]").exists())
        .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    mockMvc
        .perform(get("/api/ai/runtime/threads").param("sort", "unsupported"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/ai/runtime/threads").param("sort", "recent").param("limit", "101"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/ai/runtime/threads").param("sort", "recent").param("cursor", "not-a-cursor"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(post("/api/ai/runtime/threads/{id}/retry", threadId))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/ai/runtime/threads/{id}", "abc")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/ai/runtime/threads/{id}", "999999999999"))
        .andExpect(status().isNotFound());
  }

  /** 与 agent/model 有关的入队错误映射：非法字段 400，未知资源 404。 */
  @Test
  void mapsInvalidAndUnknownMailboxResourcesToBadRequestOrNotFound() throws Exception {
    Bound bound = bootstrapThread("mapping");

    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/yolo", bound.threadId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"clientMessageId\":\"invalid-yolo\",\"expectedExecutionEpoch\":"
                        + bound.epoch()
                        + "}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/agent", bound.threadId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentDefinitionId\":\"999999999999\","
                        + "\"clientMessageId\":\"invalid-agent\","
                        + "\"expectedExecutionEpoch\":"
                        + bound.epoch()
                        + "}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/model", bound.threadId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"modelId\":\"\",\"variant\":\"default\","
                        + "\"clientMessageId\":\"invalid-model\","
                        + "\"expectedExecutionEpoch\":"
                        + bound.epoch()
                        + "}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/model", bound.threadId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"modelId\":\"1\",\"variant\":\"missing\","
                        + "\"clientMessageId\":\"invalid-variant\","
                        + "\"expectedExecutionEpoch\":"
                        + bound.epoch()
                        + "}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/model", bound.threadId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"modelId\":\"999999999999\",\"variant\":\"default\","
                        + "\"clientMessageId\":\"missing-model\","
                        + "\"expectedExecutionEpoch\":"
                        + bound.epoch()
                        + "}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void exposesCompleteAutomaticRetryPolicyAndRejectsInvalidReplacements() throws Exception {
    mockMvc
        .perform(get("/api/ai/runtime/settings/retry-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.maxRetries").value(3))
        .andExpect(jsonPath("$.data.backoffStrategy").value("EXPONENTIAL"))
        .andExpect(jsonPath("$.data.baseDelayMillis").value(2000))
        .andExpect(jsonPath("$.data.maxDelayMillis").value(60000));

    mockMvc
        .perform(
            put("/api/ai/runtime/settings/retry-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"maxRetries":2,"backoffStrategy":"FIXED","baseDelayMillis":4000,"maxDelayMillis":8000}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.maxRetries").value(2))
        .andExpect(jsonPath("$.data.backoffStrategy").value("FIXED"))
        .andExpect(jsonPath("$.data.baseDelayMillis").value(4000))
        .andExpect(jsonPath("$.data.maxDelayMillis").value(8000));
    mockMvc
        .perform(get("/api/ai/runtime/settings/retry-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.maxRetries").value(2))
        .andExpect(jsonPath("$.data.backoffStrategy").value("FIXED"))
        .andExpect(jsonPath("$.data.baseDelayMillis").value(4000))
        .andExpect(jsonPath("$.data.maxDelayMillis").value(8000));
    mockMvc
        .perform(
            put("/api/ai/runtime/settings/retry-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"maxRetries":11,"backoffStrategy":"FIXED","baseDelayMillis":1000,"maxDelayMillis":1000}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/ai/runtime/settings/retry-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/ai/runtime/settings/retry-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"maxRetries":3,"backoffStrategy":"EXPONENTIAL","baseDelayMillis":2000,"maxDelayMillis":60000}
                    """))
        .andExpect(status().isOk());
  }

  /** All typed mailbox commands require a non-blank client id and preserve payload-safe replay. */
  @Test
  void rejectsMissingOrBlankClientMessageIdsAndConflictingReplays() throws Exception {
    Bound bound = bootstrapThread("idempotency");
    String threadId = bound.threadId();
    String suffix = ",\"expectedExecutionEpoch\":" + bound.epoch() + "}";

    assertBadRequest(
        post("/api/ai/runtime/threads/{id}/messages", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"message\"" + suffix));
    assertBadRequest(
        post("/api/ai/runtime/threads/{id}/messages", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"message\",\"clientMessageId\":\"\"" + suffix));
    assertBadRequest(
        post("/api/ai/runtime/threads/{id}/messages/custom", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"system\",\"content\":\"context\"" + suffix));
    assertBadRequest(
        post("/api/ai/runtime/threads/{id}/messages/custom", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"role\":\"system\",\"content\":\"context\",\"clientMessageId\":\"\"" + suffix));
    assertBadRequest(
        put("/api/ai/runtime/threads/{id}/yolo", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"yoloEnabled\":true" + suffix));
    assertBadRequest(
        put("/api/ai/runtime/threads/{id}/yolo", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"yoloEnabled\":true,\"clientMessageId\":\"\"" + suffix));
    assertBadRequest(
        put("/api/ai/runtime/threads/{id}/agent", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"agentDefinitionId\":\"1\"" + suffix));
    assertBadRequest(
        put("/api/ai/runtime/threads/{id}/agent", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"agentDefinitionId\":\"1\",\"clientMessageId\":\"\"" + suffix));
    assertBadRequest(
        put("/api/ai/runtime/threads/{id}/model", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"modelId\":\"model\",\"variant\":\"default\"" + suffix));
    assertBadRequest(
        put("/api/ai/runtime/threads/{id}/model", threadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"modelId\":\"model\",\"variant\":\"default\",\"clientMessageId\":\"\""
                    + suffix));
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/yolo", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true,\"clientMessageId\":\"replay\"" + suffix))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("SET_YOLO"));
    // Payload differences under the same clientMessageId are ignored; original input is returned.
    mockMvc
        .perform(
            put("/api/ai/runtime/threads/{id}/yolo", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":false,\"clientMessageId\":\"replay\"" + suffix))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("SET_YOLO"));
  }

  /** UNBOUND Thread + bootstrap，返回可直接用于 mailbox 调用的 threadId/epoch。 */
  private Bound bootstrapThread(String title) throws Exception {
    String threadId =
        data(mockMvc
                .perform(post("/api/ai/runtime/threads"))
                .andExpect(status().isCreated())
                .andReturn())
            .path("threadId")
            .asText();
    JsonNode result =
        data(
            mockMvc
                .perform(
                    post("/api/ai/runtime/threads/{id}/bootstrap", threadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"title\":\""
                                + title
                                + "\",\"agentDefinitionId\":\"1\",\"yoloEnabled\":false,"
                                + "\"expectedExecutionEpoch\":0}"))
                .andExpect(status().isCreated())
                .andReturn());
    return new Bound(
        threadId,
        result.path("thread").path("executionEpoch").asText(),
        result.path("session").path("sessionId").asText());
  }

  private record Bound(String threadId, String epoch, String sessionId) {}

  private void assertBadRequest(MockHttpServletRequestBuilder request) throws Exception {
    mockMvc.perform(request).andExpect(status().isBadRequest());
  }

  private JsonNode readData(MockHttpServletRequestBuilder request) throws Exception {
    return data(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
