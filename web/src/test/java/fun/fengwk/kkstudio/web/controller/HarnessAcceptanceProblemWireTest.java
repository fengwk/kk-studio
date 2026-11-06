package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
import fun.fengwk.kkstudio.platform.harness.http.HarnessCommandBatchController;
import fun.fengwk.kkstudio.platform.harness.http.HarnessCommandBatchService;
import fun.fengwk.kkstudio.platform.harness.http.HarnessThreadCommandBatchController;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;
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
  private StorageUploadService uploads;
  private MockMvc mvc;
  private HarnessRuntime runtime;
  private AcceptedCommands root;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtime =
        new HarnessRuntime(
            new InMemoryHarnessStore(),
            new DriverRegistry(new FakeAgentDriver("fake")),
            () -> CompactionConfig.DEFAULT);
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
    SessionOwnership owner = mock(SessionOwnership.class);
    when(owner.kind()).thenReturn("CHAT");
    when(owner.allowsDirectCommands()).thenReturn(true);
    when(owner.ownsSession(any(), any())).thenReturn(true);
    uploads = mock(StorageUploadService.class);
    HarnessCommandAcceptanceOrchestrator acceptance =
        new HarnessCommandAcceptanceOrchestrator(
            List.of(owner),
            provider,
            uploads,
            mock(SessionBlobRefManager.class),
            mock(StorageBlobManager.class));
    HarnessCommandBatchService service = new HarnessCommandBatchService(acceptance, runtime);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new HarnessCommandBatchController(service),
                new HarnessThreadCommandBatchController(service))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(
                new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
                new ProblemAdvice(new MessageService()))
            .build();
  }

  /** 附件不存在与跨 Session 资源是用户输入错误，未就绪附件则保留存储冲突原因。 */
  @Test
  void userMessageRejectsInvalidContentsAndPropagatesStorageConflict() throws Exception {
    when(uploads.lockReady(upload))
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
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    postMessage("{\"type\":\"IMAGE\",\"source\":\"https://example.com/x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  /** 合法 ROOT 切点可 fork；跨 Session 切点和未知 owner kind 均是 validation。 */
  @Test
  void newThreadChecksEntrySessionAndOwnerKind() throws Exception {
    AcceptedCommands other =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new BranchSettings("assistant", new ExecutionSelection("fake", "{}"), null),
                    false),
                List.of(
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER, List.of(new TextMessageContent("other")))),
                        UUID.randomUUID()))),
            AcceptancePreflight.IDENTITY);
    postFork("CHAT", other.rootEntry().id())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    postFork("PROJECT", root.rootEntry().id())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    postFork("CHAT", root.rootEntry().id()).andExpect(status().isAccepted());
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

  private ResultActions postFork(String kind, UUID entry) throws Exception {
    return mvc.perform(
        post("/api/harness/command-batches")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"owner":{"kind":"%s","id":"owner"},"target":{"type":"NEW_THREAD",
                "sessionId":"%s","startEntryId":"%s","threadId":"%s",
                "threadName":"branch","yoloEnabled":false},
                "commands":[{"type":"USER_MESSAGE","idempotencyKey":"%s",
                "contents":[{"type":"TEXT","text":"hello"}]}]}
                """
                    .formatted(kind, session, entry, UUID.randomUUID(), UUID.randomUUID())));
  }
}
