package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.platform.harness.prompt.PluginPromptTaskService;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

/**
 * Prompt 窄端口在 web 组合根的真实 PostgreSQL 接受事务回归。
 *
 * <p>测试意图：只有真实事务、真实会话引用管理、真实存储上传行才能证明的三件事——插件提交的媒体必须在接受事务内物化为 durable RESOURCE
 * 且释放上传归属、任一步失败必须整体回滚（不留 Session/Thread/Command/Join/引用，也不消费别人的暂存物）、同一命令的精确回放绝不重复消费。
 *
 * <p>测试上下文按 {@link WebPostgresTestSupport} 关闭生产 work dispatcher，因此这里只证明接受事务事实，不依赖任何模型执行；存储后台维护
 * 会在提交后释放上传归属，所以断言只钉住「释放已被请求」而不是瞬时的 ref_count。媒体类型只在存储边界判定，测试用内存 S3 假件的 HEAD
 * 探针取声明类型，因此声明的类型必须与字节一致。
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
class PromptTaskAcceptancePostgresIntegrationTest extends WebPostgresTestSupport {

  private static final String AGENT = "default-assistant";
  private static final String SYSTEM_TEXT = "plugin trusted prelude";
  private static final String PROMPT_TEXT = "render this reference";
  private static final String MEDIA_NAME = "tiny.png";
  private static final String MEDIA_TYPE = "image/png";

  /** PNG magic 开头的真实字节：媒体类型由存储边界判定，测试声明的类型必须与字节一致。 */
  private static final byte[] PNG_HEAD = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D
  };

  @Autowired private PluginPromptTasks promptTasks;
  @Autowired private PluginPromptResources promptResources;
  @Autowired private HarnessRuntime runtime;
  @Autowired private AgentBranchSettingsMaterializer settingsMaterializer;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clearStoredObjects() {
    s3Storage.clear();
  }

  @Test
  void submitMaterializesStagedMediaIntoDurableSessionResource() {
    PluginPromptResources.StagedResource staged = stageMedia();
    assertEquals(1, count("storage_upload", "id", staged.uploadId()));
    assertEquals(
        1L, blobRefCount(staged.blobId()), "staging must bind the blob to the upload owner");

    UUID taskId =
        promptTasks.submit(
            AGENT,
            SYSTEM_TEXT,
            List.of(
                new PluginPromptContent.Text(PROMPT_TEXT), new PluginPromptContent.Media(staged)));

    UUID sessionId = readSessionId(taskId);
    assertEquals(1, count("harness_thread", "id", taskId));
    assertEquals(1, count("harness_session", "id", sessionId));
    // 接受事务内物化：Session 恰好取得一条 durable 引用，且上传归属已被请求释放（上传行本身由后台维护收敛）。
    assertEquals(1, count("session_blob_ref", "session_id", sessionId));
    assertEquals(
        staged.blobId(),
        jdbc.queryForObject(
            "select blob_id from session_blob_ref where session_id = ?", UUID.class, sessionId));
    assertEquals(0, unreleasedUploads(staged.uploadId()));
    assertEquals(
        "ACTIVE",
        jdbc.queryForObject(
            "select state from storage_blob where id = ?", String.class, staged.blobId()));
    assertEquals(
        1,
        count(
            "harness_thread_join", "invocation_id", PluginPromptTaskService.invocationId(taskId)));
    assertEquals(
        JoinDelivery.RECEIPT_ONLY.name(),
        jdbc.queryForObject(
            "select delivery from harness_thread_join where invocation_id = ?",
            String.class,
            PluginPromptTaskService.invocationId(taskId)));

    // durable 命令的 model-visible 形态与插件给定的顺序一致：前导 system reminder、插件文本、权威文件名 RESOURCE。
    assertEquals(
        settingsMaterializer.materialize(AGENT),
        rootSettings(sessionId),
        "the task must accept the source prompt with the agent catalog settings");
    List<AgentMessageContent> contents = durablePromptContents(taskId);
    assertEquals(3, contents.size());
    assertEquals(
        new TextMessageContent(SystemReminder.wrap(SYSTEM_TEXT) + "\n\n"), contents.get(0));
    assertEquals(new TextMessageContent(PROMPT_TEXT), contents.get(1));
    ResourceMessageContent media = assertInstanceOf(ResourceMessageContent.class, contents.get(2));
    assertEquals(staged.blobId(), media.blobId());
    assertEquals(MEDIA_NAME, media.name());
    assertNull(media.totalBytes(), "staged media must not be recorded as externalized text");
  }

  @Test
  void submitRollsBackEveryHarnessFactWhenStagedMediaCannotBeConsumed() {
    PluginPromptResources.StagedResource staged = stageMedia();
    // 伪造同一个 upload 行的句柄：blob 身份复核必须在接受事务内失败。
    PluginPromptResources.StagedResource forged =
        new PluginPromptResources.StagedResource(staged.uploadId(), UUID.randomUUID());
    int sessions = countAll("harness_session");
    int threads = countAll("harness_thread");
    int commands = countAll("harness_thread_command");
    int joins = countAll("harness_thread_join");
    int refs = countAll("session_blob_ref");
    int entries = countAll("harness_entry");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            promptTasks.submit(AGENT, SYSTEM_TEXT, List.of(new PluginPromptContent.Media(forged))));

    // 整体回滚：Session/Thread/Command/Join/Entry/引用都不留痕。
    assertEquals(sessions, countAll("harness_session"));
    assertEquals(threads, countAll("harness_thread"));
    assertEquals(commands, countAll("harness_thread_command"));
    assertEquals(joins, countAll("harness_thread_join"));
    assertEquals(refs, countAll("session_blob_ref"));
    assertEquals(entries, countAll("harness_entry"));
    // 失败不接受也不替插件回收暂存物：upload 仍归插件所有，blob 仍只由该 upload 持有。
    assertEquals(1, count("storage_upload", "id", staged.uploadId()));
    assertEquals(1, unreleasedUploads(staged.uploadId()));
    assertEquals(1L, blobRefCount(staged.blobId()));
  }

  @Test
  void exactReplayOfAcceptedPromptDoesNotReconsumeMedia() {
    PluginPromptResources.StagedResource staged = stageMedia();
    UUID taskId =
        promptTasks.submit(
            AGENT,
            SYSTEM_TEXT,
            List.of(
                new PluginPromptContent.Text(PROMPT_TEXT), new PluginPromptContent.Media(staged)));
    UUID sessionId = readSessionId(taskId);

    // 持久命令只含 durable-safe 文本与媒体占位，因此可从暂存句柄重建同一条命令并精确比对身份。
    NewThreadCommand durable = durablePromptCommand(staged, taskId);
    AcceptedCommands replayed =
        runtime.spawn(
            taskSpawnRequest(
                sessionId,
                taskId,
                settingsMaterializer.materialize(AGENT),
                durable,
                failIfPromptPreflightRuns()));

    assertTrue(
        replayed.replayed(), "the same prompt command must replay instead of writing new rows");
    // 回放不重复消费：命令、Session 引用、上传归属都保持接受时的唯一状态。
    assertEquals(1, count("harness_thread", "id", taskId));
    assertEquals(1, count("harness_thread_command", "thread_id", taskId));
    assertEquals(1, count("session_blob_ref", "session_id", sessionId));
    assertEquals(0, unreleasedUploads(staged.uploadId()));
  }

  private PluginPromptResources.StagedResource stageMedia() {
    return promptResources.stage(
        MEDIA_NAME, MEDIA_TYPE, new ByteArrayInputStream(PNG_HEAD), PNG_HEAD.length);
  }

  /** 按持久形态重建已接受命令：唯一 USER CUSTOM_MESSAGE，内容为前导 reminder、插件文本与媒体占位，身份来自持久命令行。 */
  private NewThreadCommand durablePromptCommand(
      PluginPromptResources.StagedResource staged, UUID taskId) {
    assertEquals(
        1,
        count("harness_thread_command", "thread_id", taskId),
        "the accepted prompt is one command");
    UUID idempotencyKey =
        jdbc.queryForObject(
            "select idempotency_key from harness_thread_command where thread_id = ?",
            UUID.class,
            taskId);
    String durableRequestHash =
        jdbc.queryForObject(
            "select request_hash from harness_thread_command where thread_id = ?",
            String.class,
            taskId);
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                new TextMessageContent(SystemReminder.wrap(SYSTEM_TEXT) + "\n\n"),
                new TextMessageContent(PROMPT_TEXT),
                new TextMessageContent(
                    "<staged-media uploadId="
                        + staged.uploadId()
                        + " blobId="
                        + staged.blobId()
                        + ">")));
    NewThreadCommand rebuilt =
        new NewThreadCommand(new CustomMessageCommandPayload(message), idempotencyKey);
    assertEquals(
        durableRequestHash,
        rebuilt.requestHash(),
        "the durable prompt command must be exactly rebuildable from the staged media placeholder");
    return rebuilt;
  }

  private static AcceptancePreflight failIfPromptPreflightRuns() {
    return (tx, session, commands) -> {
      throw new IllegalStateException("a replayed prompt must not run its acceptance preflight");
    };
  }

  /** 与 {@link PluginPromptTaskService} 相同的插件 root 回执任务形态。 */
  private static SpawnRequest taskSpawnRequest(
      UUID sessionId,
      UUID taskId,
      BranchSettings settings,
      NewThreadCommand command,
      AcceptancePreflight preflight) {
    return new SpawnRequest(
        taskId,
        sessionId,
        settings,
        List.of(command),
        preflight,
        null,
        null,
        null,
        false,
        ThreadConstraints.NONE,
        SpawnTrigger.PLUGIN,
        JoinDelivery.RECEIPT_ONLY,
        false,
        new SpawnRequest.Budget(null, 1, 1, Integer.MAX_VALUE),
        PluginPromptTaskService.invocationId(taskId),
        command.requestHash());
  }

  private UUID readSessionId(UUID taskId) {
    return jdbc.queryForObject(
        "select session_id from harness_thread where id = ?", UUID.class, taskId);
  }

  private BranchSettings rootSettings(UUID sessionId) {
    return runtime.getSessionEntries(sessionId).stream()
        .map(Entry::payload)
        .filter(RootPayload.class::isInstance)
        .map(RootPayload.class::cast)
        .map(RootPayload::settings)
        .findFirst()
        .orElseThrow(() -> new AssertionError("the accepted session must carry a ROOT entry"));
  }

  /**
   * 已接受命令的 durable payload：命令在被执行前只以 command 行存在（测试上下文关闭 dispatcher），因此 model-visible 形态由 command
   * 的权威 codec 读回。
   */
  private List<AgentMessageContent> durablePromptContents(UUID taskId) {
    String payloadJson =
        jdbc.queryForObject(
            "select payload::text from harness_thread_command where thread_id = ?",
            String.class,
            taskId);
    CustomMessageCommandPayload payload =
        assertInstanceOf(
            CustomMessageCommandPayload.class,
            new ThreadCommandPayloadJsonCodec()
                .decode(ThreadCommandType.CUSTOM_MESSAGE, payloadJson));
    return payload.message().contents();
  }

  /** 尚未被请求释放的上传行数：0 表示上传归属已释放（行可能已被后台维护删除）。 */
  private int unreleasedUploads(UUID uploadId) {
    return jdbc.queryForObject(
        "select count(*) from storage_upload where id = ? and cleanup_requested_at is null",
        Integer.class,
        uploadId);
  }

  private long blobRefCount(UUID blobId) {
    return jdbc.queryForObject(
        "select ref_count from storage_blob where id = ?", Long.class, blobId);
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }

  private int countAll(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }
}
