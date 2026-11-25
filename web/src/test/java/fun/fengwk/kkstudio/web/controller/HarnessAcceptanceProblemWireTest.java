package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioPrincipalArgumentResolver;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.harness.http.HarnessCommandBatchService;
import fun.fengwk.kkstudio.platform.harness.http.HarnessThreadCommandBatchController;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.ModelImageCountLimitResolver;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;

import java.util.List;
import java.util.UUID;

/** 严格 wire 到真实接受原语的失败契约，不需要数据库或模型。 */
class HarnessAcceptanceProblemWireTest {
  private final UUID session = UUID.randomUUID();
  private final UUID thread = UUID.randomUUID();
  private final UUID upload = UUID.randomUUID();
  private final StudioPrincipal actor =
      new StudioPrincipal(UUID.randomUUID(), "wire-test", StudioRole.USER);
  private StorageUploadService uploads;
  private MockMvc mvc;
  private HarnessRuntime runtime;
  private InMemoryHarnessStore store;
  private AcceptedCommands root;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime =
        new HarnessRuntime(
            store,
            new DriverRegistry(new FakeAgentDriver("fake")),
            () -> new CompactionConfig(20_000));
    root =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(
                    session,
                    thread,
                    new BranchSettings("assistant", new ExecutionSelection("fake", "{}"), null),
                    false),
                List.of(
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER, List.of(new TextMessageContent("hello")))),
                        UUID.randomUUID()))),
            AcceptancePreflight.IDENTITY);
    ObjectProvider<HarnessRuntime> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(runtime);
    uploads = mock(StorageUploadService.class);
    ModelImageCountLimitResolver imageLimitResolver = mock(ModelImageCountLimitResolver.class);
    when(imageLimitResolver.resolve(any())).thenReturn(null);
    HarnessCommandAcceptanceOrchestrator acceptance =
        new HarnessCommandAcceptanceOrchestrator(
            provider,
            uploads,
            mock(SessionBlobRefManager.class),
            mock(StorageBlobManager.class),
            new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT),
            imageLimitResolver);
    HarnessCommandBatchService service =
        new HarnessCommandBatchService(acceptance, runtime, mock(ResourceAccess.class));
    mvc =
        MockMvcBuilders.standaloneSetup(new HarnessThreadCommandBatchController(service))
            .setCustomArgumentResolvers(new StudioPrincipalArgumentResolver())
            .defaultRequest(get("/").requestAttr(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, actor))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(
                new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
                new ProblemAdvice(new MessageService()))
            .build();
  }

  /** 跨 Session 的 Blob 按不存在处理，未就绪上传仍保留存储冲突原因。 */
  @Test
  void userMessageRejectsInvalidContentsAndPropagatesStorageConflict() throws Exception {
    when(uploads.lockReady(upload, actor.userId()))
        .thenThrow(DomainException.notFound("upload"))
        .thenThrow(DomainException.conflict("STORAGE_VERIFICATION", "pending"));
    String attachment = "{\"type\":\"ATTACHMENT\",\"uploadId\":\"" + upload + "\"}";
    postMessage(attachment)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    postMessage(attachment)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("STORAGE_VERIFICATION"));
    postMessage("{\"type\":\"RESOURCE\",\"blobId\":\"" + UUID.randomUUID() + "\",\"name\":\"x\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.reason").value("RESOURCE_NOT_FOUND"));
    postMessage("{\"type\":\"IMAGE\",\"source\":\"https://example.com/x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    postMessage("{\"type\":\"TEXT\",\"text\":\"hello\",\"unknown\":true}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  /** 缺省 environmentName 进入同一清除命令，实际收割清除由 reducer 单测覆盖。 */
  @Test
  void omittedEnvironmentNameQueuesClear() throws Exception {
    mvc.perform(
            post("/api/harness/threads/{threadId}/command-batches", thread)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedHeadEntryId":"%s","expectedNextCommandSequence":"%s",
                    "commands":[{"type":"SET_ENVIRONMENT","idempotencyKey":"%s"}]}
                    """
                        .formatted(
                            root.thread().headEntryId(),
                            root.thread().nextCommandSequence(),
                            UUID.randomUUID())))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.acceptedCommands[0].type").value("SET_ENVIRONMENT"))
        .andExpect(jsonPath("$.acceptedCommands[0].payload.environmentName").doesNotExist());
  }

  private ResultActions postMessage(String content) throws Exception {
    return mvc.perform(
        post("/api/harness/threads/{threadId}/command-batches", thread)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"commands\":[{\"type\":\"USER_MESSAGE\",\"idempotencyKey\":\""
                    + UUID.randomUUID()
                    + "\",\"contents\":["
                    + content
                    + "]}]}"));
  }
}
