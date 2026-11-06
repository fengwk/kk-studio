package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.http.SessionFileController;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 会话文件文本读取的真实 problem+json 形态：非文本内容的稳定 reason 是前端停用预览、保留下载的唯一依据。 */
class SessionFileControllerTest {

  private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final String PATH = "vfs:/sessions/" + SESSION_ID + "/binary.bin";

  private SessionFileStore store;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ObjectProvider<HarnessRuntime> runtimes = Mockito.mock(ObjectProvider.class);
    HarnessRuntime runtime = Mockito.mock(HarnessRuntime.class);
    Mockito.when(runtimes.getIfAvailable()).thenReturn(runtime);
    Mockito.when(runtime.sessionExists(SESSION_ID)).thenReturn(true);
    store = Mockito.mock(SessionFileStore.class);
    Mockito.when(store.find(SESSION_ID, "binary.bin"))
        .thenReturn(
            Optional.of(
                new SessionFile(SESSION_ID, "binary.bin", UUID.randomUUID(), 1, Instant.EPOCH)));
    SessionFileQueryService service =
        new SessionFileQueryService(runtimes, store, Mockito.mock(StorageBlobManager.class));
    mvc =
        MockMvcBuilders.standaloneSetup(new SessionFileController(service))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(new StudioProblemAdvice(new StudioMessageService()))
            .build();
  }

  @Test
  void nonTextContentIsValidationWithStableNotTextReason() throws Exception {
    Mockito.when(store.read(SESSION_ID, "binary.bin"))
        .thenReturn(Optional.of(new byte[] {(byte) 0xff}));

    mvc.perform(
            get("/api/harness/sessions/{sessionId}/files/content", SESSION_ID).param("path", PATH))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.reason").value(SessionFileQueryService.NOT_TEXT_REASON));
  }

  @Test
  void invalidWindowParameterIsValidationWithoutNotTextReason() throws Exception {
    Mockito.when(store.read(SESSION_ID, "binary.bin"))
        .thenReturn(Optional.of("hello".getBytes(StandardCharsets.UTF_8)));

    mvc.perform(
            get("/api/harness/sessions/{sessionId}/files/content", SESSION_ID)
                .param("path", PATH)
                .param("offset", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.reason").doesNotExist());
  }
}
