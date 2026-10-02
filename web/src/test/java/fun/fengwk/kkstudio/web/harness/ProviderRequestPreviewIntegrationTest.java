package fun.fengwk.kkstudio.web.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanPreview;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.harness.model.DatabaseProviderResolutionService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException.Reason;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResolutionService;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.ai.chat.ChatIntegrationSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 请求预览在真实 PostgreSQL + 内存 S3 + 真实 Provider adapter 编码下的回归。
 *
 * <p>测试意图：预览必须复用正式发送的完整 DB 路径——真实历史（由生产 Thread/Model Processor 处理出的 TURN_START/MESSAGE/
 * TURN_END）、真实 Session blob ref、真实 upload 行与真实 provider 连接配置——产出的请求体还必须与「同一草稿走 durable 接受
 * 路径后重新规划」得到的请求体逐字节一致，且预览自身不写任何 durable 状态、不消费 upload、不触发 transport。
 *
 * <p>本类不依赖 {@code workers-enabled=true}：fixture 用一个内联 executor 的自建 dispatcher 同步驱动生产
 * Processor，因此既走真实 claim/handoff 语义，又保证断言时机确定；应用 dispatcher 保持关闭，不影响其它测试类。
 */
@Import(WebStorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class ProviderRequestPreviewIntegrationTest extends WebPostgresTestSupport {

  private static final String CANNED_ASSISTANT_TEXT = "canned assistant reply";

  @Autowired private ChatService chatService;
  @Autowired private HarnessCommandAcceptanceOrchestrator acceptanceService;
  @Autowired private HarnessRuntime runtime;
  @Autowired private ProviderRequestPreviewService previewService;
  @Autowired private DatabaseTurnResolver turnResolver;
  @Autowired private DatabaseProviderResolutionService providerResolution;
  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentProviderRepository agentProviderRepository;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private HarnessStore harnessStore;
  @Autowired private ThreadProcessor threadProcessor;
  @Autowired private ModelProcessor modelProcessor;
  @Autowired private ToolProcessor toolProcessor;
  @Autowired private Clock clock;
  @Autowired private WebApplicationContext webContext;
  @Autowired private ObjectMapper objectMapper;

  private ChatIntegrationSupport storage;
  private HttpServer providerServer;
  private ExecutorService providerServerExecutor;
  private ScheduledExecutorService fixturePollScheduler;
  private HarnessWorkDispatcher fixtureDispatcher;
  private final AtomicInteger providerRequests = new AtomicInteger();
  private final List<JsonNode> capturedProviderBodies = new CopyOnWriteArrayList<>();
  private volatile boolean includeReasoning;

  @BeforeEach
  void setUpProvider() throws IOException {
    s3Storage.clear();
    providerRequests.set(0);
    capturedProviderBodies.clear();
    includeReasoning = false;
    storage = new ChatIntegrationSupport(storageUploadService, s3Storage, jdbc);
    startProviderServer();
    pointStubProviderAtLocalServer();
    fixturePollScheduler = Executors.newSingleThreadScheduledExecutor();
    fixtureDispatcher =
        new HarnessWorkDispatcher(
            harnessStore,
            fixtureDispatcherConfig(),
            clock,
            Runnable::run,
            Runnable::run,
            fixturePollScheduler,
            threadProcessor,
            modelProcessor,
            toolProcessor);
    fixtureDispatcher.start();
  }

  @AfterEach
  void tearDownProvider() {
    if (fixtureDispatcher != null) {
      fixtureDispatcher.close();
    }
    if (fixturePollScheduler != null) {
      fixturePollScheduler.shutdownNow();
    }
    if (providerServer != null) {
      providerServer.stop(0);
    }
    if (providerServerExecutor != null) {
      providerServerExecutor.shutdownNow();
    }
  }

  /**
   * 核心回归：真实历史 + 真实附件（历史 turn 消费的会话资源 + 草稿新增的 READY upload）+ 真实 Provider 连接配置下，预览请求体必须
   * 包含完整历史与新草稿内容，必须与 durable 接受路径重新规划出的请求体逐字节一致，且预览本身零 durable 副作用、零 transport。
   */
  @Test
  void previewEncodesDraftWithRealHistoryAttachmentsAndProviderWithoutAnySideEffect() {
    Fixture fixture = fixtureWithCompletedTurn();
    int requestsBefore = providerRequests.get();
    String draftUploadId =
        storage.completeUpload(
            "draft-attachment.txt", "draft attachment body".getBytes(StandardCharsets.UTF_8));
    UUID draftUploadBlobId =
        jdbc.queryForObject(
            "select blob_id from storage_upload where id = ?",
            UUID.class,
            UUID.fromString(draftUploadId));
    long draftBlobRefCount = blobRefCount(draftUploadBlobId);
    UserMessageCommandPayload draftMessage =
        userMessage(
            List.of(
                new TextMessageContent("draft message text"),
                new AttachmentMessageContent(UUID.fromString(draftUploadId)),
                ResourceMessageContent.media(
                    fixture.resourceBlobId(), "history-attachment.txt", "preview")));
    AcceptCommandsCommand draft =
        new AcceptCommandsCommand(fixture.threadTarget(), draftCommands(draftMessage));

    Map<String, Object> before = durableState(fixture, draftUploadId);
    HarnessProviderRequestPreviewDTO preview =
        previewService.preview(fixture.threadId(), fixture.owner(), draft);
    Map<String, Object> after = durableState(fixture, draftUploadId);

    // 预览是纯读取：Entry/Command/Work/Thread 行数、Session ref、storage blob/upload 行与 head cursor 全部原样。
    assertEquals(before, after, "预览不得产生任何 durable 状态变化");
    assertEquals(draftUploadBlobId, before.get("draft_upload_blob_id"), "预览不得改写 upload 的 blob_id");
    assertEquals(draftBlobRefCount, blobRefCount(draftUploadBlobId), "预览不得 retain 新的 Session ref");
    assertEquals(requestsBefore, providerRequests.get(), "预览不得触发任何 transport");

    // 请求体事实：最终 wire 请求含真实历史、新草稿文本、权威附件文件名与权威 blobId，且不含凭据/endpoint。
    assertEquals(HarnessProviderRequestPreviewDTO.KIND, preview.getKind());
    assertEquals("OPENAI", preview.getProviderType());
    assertEquals("acceptance-stub", preview.getModelName());
    assertEquals(HarnessProviderRequestPreviewDTO.SNAPSHOT_NOTICE, preview.getSnapshotNotice());
    assertEquals(
        fixture.headEntryId().toString(), preview.getSourceHeadEntryId(), "预览必须绑定当前 source head");
    assertEquals(
        preview.getBodyJson().getBytes(StandardCharsets.UTF_8).length,
        preview.getBodyByteSize(),
        "bodyByteSize 必须是 UTF-8 字节数");
    String body = preview.getBodyJson();
    assertTrue(body.contains("history message"), "请求体必须包含真实历史用户消息");
    assertTrue(body.contains(CANNED_ASSISTANT_TEXT), "请求体必须包含真实历史 assistant 回复");
    assertTrue(body.contains("draft message text"), "请求体必须包含草稿新消息");
    assertTrue(body.contains("draft-attachment.txt"), "请求体必须使用权威上传文件名");
    assertTrue(body.contains("history-attachment.txt"), "请求体必须包含会话已持有资源的权威文件名");
    assertTrue(body.contains(fixture.resourceBlobId().toString()), "请求体必须引用会话资源的 blobId");
    assertTrue(body.contains("acceptance-stub"), "请求体必须使用解析出的模型名");
    assertTrue(!body.contains("stub-key"), "请求体绝不得包含 provider 凭据");
    assertTrue(!body.contains(fixture.providerBaseUrl()), "请求体绝不得包含 provider endpoint");

    // 与 durable 发送路径同源：接受同一草稿后，用只读规划把 durable 命令重新物化并编码，字节必须与预览完全一致。期望的 durable
    // 内容是预览侧 peek 出的权威事实（上传行的 blobId 与文件名）加上候选顺序，因此这里也验证「peek 与消费产出同一 RESOURCE」。
    List<AgentMessageContent> expectedDurableContents =
        List.of(
            new TextMessageContent("draft message text"),
            ResourceMessageContent.media(draftUploadBlobId, "draft-attachment.txt"),
            ResourceMessageContent.media(
                fixture.resourceBlobId(), "history-attachment.txt", "preview"));
    byte[] durableBody = encodeDurableSendBody(fixture, draft, expectedDurableContents);
    assertEquals(
        preview.getBodyJson(),
        new String(durableBody, StandardCharsets.UTF_8),
        "预览体必须与 durable 发送路径的请求体逐字节一致");
  }

  /**
   * 测试意图：真实 adapter 将普通 reasoning_content 持久化后，同一连接/模型只改变当前 system prompt 不应阻断预览或正式发送，也不得丢弃任一轮
   * reasoning。闭合 turn 必须明确 COMPLETED，不能把失败后的静止误当成功。
   */
  @Test
  void previewPreservesAllPlainReasoningAfterCurrentSystemPromptChanges() throws Exception {
    includeReasoning = true;
    String previousPrompt = "Today is 2026-10-01. Answer briefly.";
    String currentPrompt = "Today is 2026-10-02. Answer briefly.";
    updateSystemPrompt(previousPrompt);
    Fixture fixture = fixtureWithCompletedTurn();
    assertCompletedTurns(fixture, 1);
    assertEquals(1, providerRequests.get());
    assertTrue(
        capturedProviderBodies
            .getFirst()
            .path("messages")
            .get(0)
            .path("content")
            .asText()
            .startsWith(previousPrompt),
        "历史 turn 必须实际使用变更前的 system prompt");

    acceptanceService.accept(
        fixture.owner(),
        new AcceptCommandsCommand(
            fixture.threadTarget(),
            draftCommands(userMessage(List.of(new TextMessageContent("second history message"))))));
    drainUntilQuiescent(fixture.threadId());
    assertCompletedTurns(fixture, 2);
    assertEquals(2, providerRequests.get(), "两轮历史都必须实际经过本地 transport");
    assertAssistantReasoning(capturedProviderBodies.get(1), List.of(cannedReasoning(1)));
    assertEquals(
        2,
        jdbc.queryForObject(
            "select count(*) from harness_entry where session_id = ? and entry_type = 'MESSAGE'"
                + " and payload->'message'->>'role' = 'ASSISTANT'"
                + " and provider_replay_state is not null",
            Integer.class,
            fixture.sessionId()),
        "两轮 reasoning 历史都必须由真实 adapter 持久化 replay state");
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(fixture.threadId());
    fixture =
        new Fixture(
            fixture.owner(),
            fixture.sessionId(),
            fixture.threadId(),
            snapshot.entryPath().head().id(),
            snapshot.thread().nextCommandSequence(),
            fixture.resourceBlobId(),
            fixture.providerBaseUrl());

    // 只修改 Testcontainers 专用数据库中的 agent 定义，不更换 provider generation 或模型。
    updateSystemPrompt(currentPrompt);
    String uploadId =
        storage.completeUpload(
            "reasoning-draft.txt", "reasoning draft attachment".getBytes(StandardCharsets.UTF_8));
    UUID blobId =
        jdbc.queryForObject(
            "select blob_id from storage_upload where id = ?",
            UUID.class,
            UUID.fromString(uploadId));
    long refsBefore = blobRefCount(blobId);
    AcceptCommandsCommand draft =
        new AcceptCommandsCommand(
            fixture.threadTarget(),
            draftCommands(
                userMessage(
                    List.of(
                        new TextMessageContent("third message after date change"),
                        new AttachmentMessageContent(UUID.fromString(uploadId))))));
    Map<String, Object> before = durableState(fixture, uploadId);
    int requestsBefore = providerRequests.get();
    HarnessProviderRequestPreviewDTO preview =
        previewService.preview(fixture.threadId(), fixture.owner(), draft);

    assertEquals(before, durableState(fixture, uploadId), "预览不得写入 durable 状态或消费 upload");
    assertEquals(refsBefore, blobRefCount(blobId), "预览不得 retain 草稿附件");
    assertEquals(requestsBefore, providerRequests.get(), "预览不得触发 transport");
    JsonNode previewBody = objectMapper.readTree(preview.getBodyJson());
    assertAssistantReasoning(previewBody, List.of(cannedReasoning(1), cannedReasoning(2)));
    assertEquals("system", previewBody.path("messages").get(0).path("role").asText());
    // 生产 prompt composer 还会附加环境/工具说明，因此检查 agent prompt 前缀而非整个复合 prompt。
    assertTrue(
        previewBody.path("messages").get(0).path("content").asText().startsWith(currentPrompt));
    assertTrue(!preview.getBodyJson().contains(previousPrompt), "旧 system prompt 不得覆盖当前配置");
    assertEquals("acceptance-stub", preview.getModelName());

    byte[] durableBody =
        encodeDurableSendBody(
            fixture,
            draft,
            List.of(
                new TextMessageContent("third message after date change"),
                ResourceMessageContent.media(blobId, "reasoning-draft.txt")));
    assertEquals(preview.getBodyJson(), new String(durableBody, StandardCharsets.UTF_8));
    assertEquals(requestsBefore, providerRequests.get(), "只读 durable 规划与编码不得发送请求");

    // 正式执行刚接受的同一草稿，验证生产 Processor 没有静默关闭为 FAILED。
    drainUntilQuiescent(fixture.threadId());
    assertCompletedTurns(fixture, 3);
    assertEquals(requestsBefore + 1, providerRequests.get());
    JsonNode wireBody = capturedProviderBodies.getLast();
    assertEquals(previewBody, wireBody, "实际 transport 必须发送与预览相同的完整请求");
    assertAssistantReasoning(wireBody, List.of(cannedReasoning(1), cannedReasoning(2)));
  }

  private void updateSystemPrompt(String prompt) {
    assertEquals(
        1,
        jdbc.update(
            "update agent_definition set system_prompt = ? where name = 'default-assistant'",
            prompt));
  }

  private void assertCompletedTurns(Fixture fixture, int expected) {
    assertEquals(
        expected,
        jdbc.queryForObject(
            "select count(*) from harness_entry where session_id = ? and entry_type = 'TURN_END'",
            Integer.class,
            fixture.sessionId()));
    assertEquals(
        expected,
        jdbc.queryForObject(
            "select count(*) from harness_entry where session_id = ? and entry_type = 'TURN_END'"
                + " and payload->>'outcome' = 'COMPLETED'",
            Integer.class,
            fixture.sessionId()),
        "所有 turn 必须是 COMPLETED，而非失败后 quiescent");
  }

  private void assertAssistantReasoning(JsonNode body, List<String> expected) {
    List<String> actual = new ArrayList<>();
    for (JsonNode message : body.path("messages")) {
      if ("assistant".equals(message.path("role").asText())) {
        assertEquals(CANNED_ASSISTANT_TEXT, message.path("content").asText());
        assertTrue(
            message.path("reasoning_content").isTextual(), "每轮 assistant 必须保留普通文本 reasoning");
        actual.add(message.path("reasoning_content").asText());
      }
    }
    assertEquals(expected, actual, "全部 assistant reasoning 必须按历史顺序逐字保留");
  }

  private static String cannedReasoning(int turn) {
    return "plain reasoning for turn " + turn + "\n检查日期并保留完整思考。";
  }

  /** 未 READY（PENDING）的 upload 必须 409 拒绝，且该 upload 行不得被消费、改写或删除。 */
  @Test
  void previewRejectsPendingUploadWithoutConsumingIt() {
    Fixture fixture = fixtureWithCompletedTurn();
    StorageUploadDTO pending =
        storage.reserve(
            "pending.txt",
            "text/plain",
            7,
            storage.sha256Hex("pending".getBytes(StandardCharsets.UTF_8)));
    AcceptCommandsCommand draft =
        new AcceptCommandsCommand(
            fixture.threadTarget(),
            draftCommands(
                userMessage(
                    List.of(
                        new TextMessageContent("draft"),
                        new AttachmentMessageContent(UUID.fromString(pending.getId()))))));

    Map<String, Object> before = durableState(fixture, pending.getId());
    ProviderRequestPreviewUnavailableException error =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () -> previewService.preview(fixture.threadId(), fixture.owner(), draft));
    assertEquals(Reason.PREVIEW_ATTACHMENT_NOT_READY, error.reason());
    assertEquals(before, durableState(fixture, pending.getId()), "拒绝路径同样不得产生副作用");
  }

  /** 测试意图：真实 HTTP/DB 下旧 head 或 sequence 拒绝，重读快照后预览成功，全程零写入、零 transport。 */
  @Test
  void httpPreviewRecoversFromStaleCursorByReadingFreshSnapshot() throws Exception {
    Fixture fixture = fixtureWithCompletedTurn();
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    String snapshotPath = "/api/harness/threads/" + fixture.threadId();
    String previewPath = snapshotPath + "/provider-request-preview";
    Map<String, Object> before = durableState(fixture, null);
    int requestsBefore = providerRequests.get();
    JsonNode fresh = readHttpSnapshot(mvc, snapshotPath);
    String head = fresh.path("thread").path("headEntryId").asText();
    String sequence = fresh.path("thread").path("nextCommandSequence").asText();
    mvc.perform(
            post(previewPath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(httpDraft(fixture, head, sequence)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sourceHeadEntryId").value(head));
    UUID oldHead =
        jdbc.queryForObject(
            "select id from harness_entry where session_id = ? and entry_type = 'ROOT'",
            UUID.class,
            fixture.sessionId());
    for (String body :
        List.of(
            httpDraft(fixture, oldHead.toString(), sequence),
            httpDraft(fixture, head, Long.toString(Long.parseLong(sequence) - 1)))) {
      mvc.perform(post(previewPath).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.errors.reason").value("PREVIEW_STALE_CURSOR"));
      assertEquals(before, durableState(fixture, null));
      assertEquals(requestsBefore, providerRequests.get());
    }
    JsonNode retry = readHttpSnapshot(mvc, snapshotPath);
    mvc.perform(
            post(previewPath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    httpDraft(
                        fixture,
                        retry.path("thread").path("headEntryId").asText(),
                        retry.path("thread").path("nextCommandSequence").asText())))
        .andExpect(status().isOk());
    assertEquals(before, durableState(fixture, null));
    assertEquals(requestsBefore, providerRequests.get());
  }

  private JsonNode readHttpSnapshot(MockMvc mvc, String path) throws Exception {
    return objectMapper
        .readTree(
            mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data");
  }

  private String httpDraft(Fixture fixture, String head, String sequence) throws IOException {
    try (var input = getClass().getResourceAsStream("preview-draft.json")) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8)
          .formatted(
              ((OwnerRef.Chat) fixture.owner()).chatId(), fixture.threadId(), head, sequence);
    }
  }

  /** 游标漂移与 queued 命令都必须 409 拒绝：预览绝不猜测「用户此刻看到的」历史。 */
  @Test
  void previewRejectsDriftedCursorAndQueuedCommands() {
    Fixture fixture = fixtureWithCompletedTurn();
    UUID rootEntryId =
        jdbc.queryForObject(
            "select id from harness_entry where session_id = ? and entry_type = 'ROOT'",
            UUID.class,
            fixture.sessionId());
    // head 已经推进到 turn 结束 Entry，但客户端仍按 ROOT cursor 预览：确定性拒绝。
    AcceptCommandsCommand drifted =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                fixture.threadId(), rootEntryId, fixture.nextCommandSequence()),
            draftCommands(userMessage(List.of(new TextMessageContent("draft")))));
    ProviderRequestPreviewUnavailableException cursorRejection =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () -> previewService.preview(fixture.threadId(), fixture.owner(), drifted));
    assertTrue(cursorRejection.getMessage().contains("head/next command sequence"));
    assertEquals(Reason.PREVIEW_STALE_CURSOR, cursorRejection.reason());

    // 先把同一草稿 durable 接受（队列非空、尚未被 worker 处理），随后按当前 cursor 预览仍必须拒绝。
    acceptanceService.accept(
        fixture.owner(),
        new AcceptCommandsCommand(
            fixture.threadTarget(),
            draftCommands(userMessage(List.of(new TextMessageContent("queued draft"))))));
    ThreadSnapshot queued = runtime.getThreadSnapshot(fixture.threadId());
    assertEquals(2, queued.queuedCommands().size(), "接受后命令必须处于 QUEUED（应用 worker 已关闭）");
    AcceptCommandsCommand whileQueued =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                fixture.threadId(),
                queued.thread().headEntryId(),
                queued.thread().nextCommandSequence()),
            draftCommands(userMessage(List.of(new TextMessageContent("another draft")))));
    Map<String, Object> before = durableState(fixture, null);
    int requestsBefore = providerRequests.get();
    ProviderRequestPreviewUnavailableException queuedRejection =
        assertThrows(
            ProviderRequestPreviewUnavailableException.class,
            () -> previewService.preview(fixture.threadId(), fixture.owner(), whileQueued));
    assertTrue(queuedRejection.getMessage().contains("queued commands"));
    assertEquals(Reason.PREVIEW_QUEUED_COMMANDS, queuedRejection.reason());
    assertEquals(before, durableState(fixture, null));
    assertEquals(requestsBefore, providerRequests.get());
  }

  /** 跨 owner 与跨 Session 资源都是请求错误（400），且绝不产生任何写入。 */
  @Test
  void previewRejectsCrossOwnerAndForeignResourceWithoutAnyWrite() {
    Fixture fixture = fixtureWithCompletedTurn();
    Fixture other = fixtureWithCompletedTurn();
    Map<String, Object> before = durableState(fixture, null);

    // 另一个产品的 Thread：owner 授权必须复用正式接受路径的判定。
    IllegalArgumentException crossOwner =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                previewService.preview(
                    fixture.threadId(),
                    other.owner(),
                    new AcceptCommandsCommand(
                        fixture.threadTarget(),
                        draftCommands(userMessage(List.of(new TextMessageContent("x")))))));
    assertNotNull(crossOwner.getMessage());

    // 目标 Session 不持有该 blob：预览与发送同样拒绝，绝不顺带 retain。
    IllegalArgumentException foreignResource =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                previewService.preview(
                    fixture.threadId(),
                    fixture.owner(),
                    new AcceptCommandsCommand(
                        fixture.threadTarget(),
                        draftCommands(
                            userMessage(
                                List.of(
                                    ResourceMessageContent.media(
                                        other.resourceBlobId(), "foreign.txt", "preview")))))));
    assertTrue(foreignResource.getMessage().contains("not owned"), foreignResource.getMessage());
    assertEquals(before, durableState(fixture, null), "拒绝路径不得写入任何 durable 状态");
  }

  /** Issue Agent Session 的 owner 不属于本预览能力范围：400 且不触达任何 Runtime/存储事实。 */
  @Test
  void previewRejectsIssueAgentSessionOwner() {
    Fixture fixture = fixtureWithCompletedTurn();
    Map<String, Object> before = durableState(fixture, null);
    IllegalArgumentException rejection =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                previewService.preview(
                    fixture.threadId(),
                    new OwnerRef.IssueAgent(UUID.randomUUID(), "executor"),
                    new AcceptCommandsCommand(
                        fixture.threadTarget(),
                        draftCommands(userMessage(List.of(new TextMessageContent("x")))))));
    assertTrue(rejection.getMessage().contains("limited to CHAT owners"));
    assertEquals(before, durableState(fixture, null));
  }

  /**
   * 与发送完全相同的草稿经过 durable 接受后按只读规划重新编码：这是「预览与发送同源」的独立对照——durable 路径消费 upload 并写入 Session ref，而预览只
   * peek，两者必须给出同一份 wire 请求体。
   */
  private byte[] encodeDurableSendBody(
      Fixture fixture,
      AcceptCommandsCommand draft,
      List<AgentMessageContent> expectedDurableContents) {
    int acceptedCommandCount = draft.commands().size();
    acceptanceService.accept(fixture.owner(), draft);
    ThreadSnapshot queued = runtime.getThreadSnapshot(fixture.threadId());
    List<ThreadCommand> commands = queued.queuedCommands();
    assertEquals(acceptedCommandCount, commands.size(), "durable 队列必须与草稿命令一一对应");
    UserMessageCommandPayload durableMessage =
        assertInstanceOf(UserMessageCommandPayload.class, commands.getLast().payload());
    assertEquals(
        expectedDurableContents,
        durableMessage.message().contents(),
        "durable 接受必须产出与预览一致的 RESOURCE 内容（权威 blobId 与权威文件名）");
    List<NewThreadCommand> durableCommands =
        commands.stream()
            .map(
                command ->
                    new NewThreadCommand(
                        command.payload(), command.idempotencyKey(), command.requestHash()))
            .toList();
    EntryPath path =
        TurnPlanPreview.inputCandidatePath(
            fixture.threadId(),
            queued.entryPath(),
            queued.thread().nextCommandSequence() - acceptedCommandCount,
            durableCommands,
            Instant.now(clock));
    LiveTurnPlan plan = turnResolver.planLive(fixture.threadId(), path);
    ModelRequestSpec spec = assertInstanceOf(LiveTurnPlan.Planned.class, plan).spec();
    ProviderRequest request = new ModelRequestMaterializer().materialize(path, spec);
    ProviderResolutionService.ResolvedExecution resolved =
        providerResolution.resolve(
            spec.providerType(), spec.providerConnectionGenerationId(), request);
    return resolved.encodeRequestBody();
  }

  /** 真实 DB 路径 fixture：一个由生产 Processor 处理完成的 turn（历史消息 + 会话资源 ref）。 */
  private Fixture fixtureWithCompletedTurn() {
    UUID chatId = createChat("preview-fixture");
    OwnerRef owner = new OwnerRef.Chat(chatId);
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    // 每个 fixture 必须用不同内容：存储按内容寻址去重，而本测试需要「另一个 Session 独有的 blob」。
    String uploadId =
        storage.completeUpload(
            "history-attachment.txt",
            ("history attachment body " + chatId).getBytes(StandardCharsets.UTF_8));
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
            List.of(
                new NewThreadCommand(
                    userMessage(
                        List.of(
                            new TextMessageContent("history message"),
                            new AttachmentMessageContent(UUID.fromString(uploadId)))),
                    UUID.randomUUID()))));
    drainUntilQuiescent(threadId);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    assertTrue(snapshot.queuedCommands().isEmpty(), "fixture turn 必须已被完整应用");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_entry where session_id = ? and entry_type = 'TURN_END'",
            Integer.class,
            sessionId),
        "fixture turn 必须产生闭合 turn");
    UUID resourceBlobId =
        jdbc.queryForObject(
            "select blob_id from session_blob_ref where session_id = ?", UUID.class, sessionId);
    assertNotNull(resourceBlobId, "fixture turn 的附件必须已建立 Session ref");
    return new Fixture(
        owner,
        sessionId,
        threadId,
        snapshot.entryPath().head().id(),
        snapshot.thread().nextCommandSequence(),
        resourceBlobId,
        providerBaseUrl());
  }

  /** 同步驱动生产 Processor：内联 executor 让 wake 直接在当前线程完成 drain 与 handoff。 */
  private void drainUntilQuiescent(UUID threadId) {
    awaitTrue(
        () -> {
          fixtureDispatcher.wake();
          return quiescent(threadId);
        },
        "fixture turn did not reach quiescence");
  }

  private boolean quiescent(UUID threadId) {
    return count("harness_work") == 0
        && jdbc.queryForObject(
                "select count(*) from harness_thread_command where thread_id = ?"
                    + " and applied_turn_start_entry_id is null and cancelled_at is null",
                Integer.class,
                threadId)
            == 0;
  }

  private void awaitTrue(BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
    }
    throw new AssertionError(message);
  }

  /** 草稿命令：SET_MODEL 设置前缀 + 恰好一条末尾 USER_MESSAGE，与产品 HTTP 请求形状一致。 */
  private static List<NewThreadCommand> draftCommands(UserMessageCommandPayload message) {
    return List.of(
        new NewThreadCommand(
            new SetModelCommandPayload(new ModelSelection("stub", "acceptance-stub", "default")),
            UUID.randomUUID()),
        new NewThreadCommand(message, UUID.randomUUID()));
  }

  private static UserMessageCommandPayload userMessage(List<AgentMessageContent> contents) {
    return new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, contents));
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        "default-assistant", new ModelSelection("stub", "acceptance-stub", "default"), null);
  }

  private UUID createChat(String title) {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle(title);
    create.setAgentName("default-assistant");
    return UUID.fromString(chatService.createChat(create).getId());
  }

  private void startProviderServer() throws IOException {
    providerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    providerServer.createContext(
        "/chat/completions",
        exchange -> {
          int turn = providerRequests.incrementAndGet();
          try {
            capturedProviderBodies.add(objectMapper.readTree(exchange.getRequestBody()));
            Map<String, String> delta = new LinkedHashMap<>();
            delta.put("content", CANNED_ASSISTANT_TEXT);
            if (includeReasoning) {
              delta.put("reasoning_content", cannedReasoning(turn));
            }
            byte[] body =
                ("data: {\"choices\":[{\"index\":0,\"delta\":"
                        + objectMapper.writeValueAsString(delta)
                        + ",\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,"
                        + "\"completion_tokens\":4,\"total_tokens\":16}}\n\n"
                        + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
              output.write(body);
            }
          } catch (IOException error) {
            throw new IllegalStateException(error);
          }
        });
    providerServerExecutor = Executors.newSingleThreadExecutor();
    providerServer.setExecutor(providerServerExecutor);
    providerServer.start();
  }

  /** 真实 provider 连接配置：把 dev seed 的 stub provider 指到本地 deterministic SSE 服务。 */
  private void pointStubProviderAtLocalServer() {
    AgentProvider provider = agentProviderRepository.getByName("stub");
    assertNotNull(provider, "dev seed 必须提供 stub provider");
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setBaseUrl(providerBaseUrl());
    update.setCredential("stub-key");
    update.setModelCallTimeoutMillis(30_000L);
    update.setModelCallIdleTimeoutMillis(10_000L);
    update.setExpectedVersion(CatalogVersions.format(provider.getVersion()));
    agentProviderService.updateProvider("stub", update);
  }

  private String providerBaseUrl() {
    return "http://127.0.0.1:" + providerServer.getAddress().getPort();
  }

  private HarnessWorkDispatcherConfig fixtureDispatcherConfig() {
    return new HarnessWorkDispatcherConfig(
        Duration.ofMinutes(1),
        Duration.ofMinutes(1),
        Duration.ofMinutes(1),
        Duration.ofHours(1),
        Duration.ofMillis(50),
        64);
  }

  private long blobRefCount(UUID blobId) {
    return jdbc.queryForObject(
        "select ref_count from storage_blob where id = ?", Long.class, blobId);
  }

  /** 预览前后必须完全一致的 durable 事实指纹（行数 + 目标 upload 行 + head cursor）。 */
  private Map<String, Object> durableState(Fixture fixture, String uploadId) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("harness_entry", count("harness_entry", "session_id", fixture.sessionId()));
    state.put("harness_thread", count("harness_thread", "id", fixture.threadId()));
    state.put(
        "harness_thread_command", count("harness_thread_command", "thread_id", fixture.threadId()));
    state.put("harness_work", count("harness_work"));
    state.put("session_blob_ref", count("session_blob_ref", "session_id", fixture.sessionId()));
    state.put("storage_blob", count("storage_blob"));
    state.put("storage_upload", count("storage_upload"));
    state.put(
        "thread_row",
        jdbc.queryForMap("select * from harness_thread where id = ?", fixture.threadId()));
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(fixture.threadId());
    state.put("head_entry_id", snapshot.entryPath().head().id());
    state.put("next_command_sequence", snapshot.thread().nextCommandSequence());
    if (uploadId != null) {
      Map<String, Object> upload =
          jdbc.queryForMap(
              "select blob_id, filename, cleanup_requested_at, cleanup_token, cleanup_until"
                  + " from storage_upload where id = ?",
              UUID.fromString(uploadId));
      state.put("draft_upload_blob_id", upload.get("blob_id"));
      state.put("draft_upload_filename", upload.get("filename"));
      state.put("draft_upload_cleanup_requested_at", upload.get("cleanup_requested_at"));
      state.put("draft_upload_cleanup_token", upload.get("cleanup_token"));
      state.put("draft_upload_cleanup_until", upload.get("cleanup_until"));
    }
    return state;
  }

  private int count(String table) {
    return count(table, null, null);
  }

  private int count(String table, String column, UUID value) {
    if (column == null) {
      return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }

  /** 已完成真实 turn 的 fixture 事实。 */
  private record Fixture(
      OwnerRef owner,
      UUID sessionId,
      UUID threadId,
      UUID headEntryId,
      long nextCommandSequence,
      UUID resourceBlobId,
      String providerBaseUrl) {

    AcceptCommandsTarget.Thread threadTarget() {
      return new AcceptCommandsTarget.Thread(threadId, headEntryId, nextCommandSequence);
    }
  }
}
