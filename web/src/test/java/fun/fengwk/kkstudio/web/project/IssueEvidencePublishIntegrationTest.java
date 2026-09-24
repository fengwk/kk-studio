package fun.fengwk.kkstudio.web.project;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code POST /api/issues/{issueId}/evidence} 的人工上传公开证据端到端测试（真实 PostgreSQL + 内存 S3 假件）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>人经 Issue 入口显式提交已 READY 上传后，该 Blob 成为 Issue 公开证据：HTTP 响应给出规范 URI 与权威上传文件名，未知字段不做投影；
 *   <li>公开是引用转移而不是复制：对象存储 key 集合与最终对象字节在发布前后完全不变，upload owner 同事务释放并只留下后台 cleanup 标记；
 *   <li>同一事务内留痕：Issue 持有自己的引用、有序事实流出现 HUMAN COMMENT；
 *   <li>非 READY、未知、已消费上传与已归档 Issue 都确定性拒绝，且不产生证据、不消费上传引用。
 * </ul>
 */
@AutoConfigureMockMvc
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
class IssueEvidencePublishIntegrationTest extends WebPostgresTestSupport {

  private static final AtomicLong FIXTURE_COUNTER = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private StorageUploadService uploadService;
  @MockitoBean private StorageMaintenance storageMaintenance;

  private IssueEvidenceTestSupport support;
  private UUID issueId;

  @BeforeEach
  void setUp() {
    // 后台维护停用（mock）：上传行保留到测试内显式断言，cleanup 标记不被后台删除。
    s3Storage.clear();
    support = new IssueEvidenceTestSupport(uploadService, s3Storage, jdbc);
    issueId = createIssueFixture();
  }

  @Test
  void humanUploadBecomesIssueEvidenceWithoutCopyingBytes() throws Exception {
    String content = "acceptance evidence body";
    UUID uploadId = support.readyUpload("acceptance.txt", content);
    UUID blobId = support.blobIdOf(uploadId);
    Set<String> objectsBeforePublish = s3Storage.keys();
    assertEquals(1L, support.refCount(blobId), "READY upload owns the only reference");

    String uri = "kkstudio:/resources/" + blobId;
    MvcResult result =
        mockMvc
            .perform(
                post("/api/issues/" + issueId + "/evidence")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"uploadId\":\"" + uploadId + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.issueId").value(issueId.toString()))
            .andExpect(jsonPath("$.data.blobId").value(blobId.toString()))
            .andExpect(jsonPath("$.data.uri").value(uri))
            .andExpect(jsonPath("$.data.origin").value("HUMAN"))
            .andExpect(jsonPath("$.data.name").value("acceptance.txt"))
            .andExpect(jsonPath("$.data.runId").value(nullValue()))
            .andExpect(jsonPath("$.data.publishedAt").isString())
            .andReturn();

    // 公开不复制字节：对象 key 集合与最终对象内容在发布前后逐字节一致，响应也不暴露物理 key
    assertEquals(objectsBeforePublish, s3Storage.keys());
    assertArrayEquals(
        content.getBytes(StandardCharsets.UTF_8),
        s3Storage.objectBytes(StorageObjectKeys.blobOriginal(blobId)));
    String body = result.getResponse().getContentAsString();
    assertFalse(
        body.contains("uploads/"), "response must not expose physical object keys: " + body);

    // 引用账本：Issue 持有自己的引用（无参与者 Session 时共 1），upload owner 同事务释放并只留 cleanup 标记
    assertEquals(1L, support.refCount(blobId));
    assertNotNull(
        jdbc.queryForObject(
            "select cleanup_requested_at from storage_upload where id = ?",
            Instant.class,
            uploadId),
        "consumed upload must be marked for cleanup in the publication transaction");
    assertEquals(1, support.count("project_issue_evidence", "issue_id", issueId));

    // 有序事实流留痕：人工发布是人为事实（建单事实之外恰好一条 COMMENT）
    assertEquals(
        1, commentActivityCount(), "human publication must leave exactly one ordered COMMENT fact");
    assertEquals(
        "HUMAN",
        jdbc.queryForObject(
            "select actor_type from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
            String.class,
            issueId));
    assertTrue(
        jdbc.queryForObject(
                "select body from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
                String.class,
                issueId)
            .contains(uri));

    // 详情读取面与发布响应同源：同一规范 URI 与元数据
    mockMvc
        .perform(get("/api/issues/" + issueId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.evidence[0].blobId").value(blobId.toString()))
        .andExpect(jsonPath("$.data.evidence[0].uri").value(uri))
        .andExpect(jsonPath("$.data.evidence[0].origin").value("HUMAN"))
        .andExpect(jsonPath("$.data.evidence[0].name").value("acceptance.txt"));
  }

  @Test
  void publishingConsumedUploadIsRejectedWithoutDoubleCounting() throws Exception {
    UUID uploadId = support.readyUpload("report.txt", "report body");
    UUID blobId = support.blobIdOf(uploadId);
    publishEvidence(uploadId);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"" + uploadId + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PROJECT_VALIDATION_ERROR"));

    assertEquals(1, support.count("project_issue_evidence", "issue_id", issueId));
    assertEquals(1L, support.refCount(blobId), "rejected replay must not retain the blob again");
  }

  @Test
  void unknownOrNotReadyUploadIsRejectedWithoutPublishingAnything() throws Exception {
    StorageUploadReserveRequestDTO reserve = new StorageUploadReserveRequestDTO();
    reserve.setFilename("pending.txt");
    reserve.setMediaType("text/plain");
    reserve.setSizeBytes(6L);
    reserve.setSha256(support.sha256Hex("pending".getBytes(StandardCharsets.UTF_8)));
    StorageUploadDTO pending = uploadService.reserve(reserve);

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"" + pending.getId() + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PROJECT_VALIDATION_ERROR"));

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PROJECT_RESOURCE_NOT_FOUND"));

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"not-a-uuid\"}"))
        .andExpect(status().isBadRequest());

    assertEquals(0, support.count("project_issue_evidence", "issue_id", issueId));
    assertEquals(0, commentActivityCount(), "refused publication must not leave a fact");
    assertNull(
        jdbc.queryForObject(
            "select blob_id from storage_upload where id = ?",
            UUID.class,
            UUID.fromString(pending.getId())),
        "PENDING upload stays pending: refused publication must not bind or consume it");
  }

  @Test
  void archivedIssueRejectsPublicationWithoutConsumingTheUpload() throws Exception {
    UUID uploadId = support.readyUpload("late.txt", "late body");
    UUID blobId = support.blobIdOf(uploadId);
    // 已归档 Issue 只读：取消到终态后再归档，任何公开入口都必须拒绝
    Issue issue = issueService.getIssue(issueId);
    Issue canceled = issueService.cancelIssue(issueId, issue.getVersion(), "Not needed anymore");
    issueService.archiveIssue(issueId, canceled.getVersion());

    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"" + uploadId + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PROJECT_VALIDATION_ERROR"));

    assertEquals(0, support.count("project_issue_evidence", "issue_id", issueId));
    assertEquals(
        1L, support.refCount(blobId), "refused publication must keep the upload reference");
    assertNull(
        jdbc.queryForObject(
            "select cleanup_requested_at from storage_upload where id = ?",
            Instant.class,
            uploadId),
        "refused publication must not consume the upload");
  }

  private int commentActivityCount() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
            Integer.class,
            issueId);
    return count != null ? count : 0;
  }

  private void publishEvidence(UUID uploadId) throws Exception {
    mockMvc
        .perform(
            post("/api/issues/" + issueId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"" + uploadId + "\"}"))
        .andExpect(status().isCreated());
  }

  private UUID createIssueFixture() {
    Project project = projectService.createProject("Evidence HTTP project", "Desc", false, 0);
    String executor = createTestAgent();
    Issue issue =
        issueService.createIssue(
            project.getId(), "Publish through HTTP", "Desc", executor, null, IssueStatus.TODO);
    return issue.getId();
  }

  private String createTestAgent() {
    long id = FIXTURE_COUNTER.incrementAndGet();
    String providerName = "prov-" + id;
    String modelName = "mod-" + id;
    String agentName = "agent-" + id;
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) "
            + "values (?, 'openai', '{}'::jsonb, ?::uuid)",
        providerName,
        UUID.randomUUID());
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config) values (?, ?, ?, ?::jsonb)",
        providerName,
        modelName,
        "wire-" + id,
        "{\"limit\":{\"context\":128000,\"output\":8192},"
            + "\"abilities\":{\"tools\":true,\"reasoning\":true,\"inputModalities\":[\"TEXT\"]},"
            + "\"variants\":[{\"id\":\"default\"}],\"defaultVariant\":\"default\","
            + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"standard\","
            + "\"serviceTier\":\"standard\",\"serviceTierMultiplier\":1.0,"
            + "\"version\":\"2026-01-01\",\"inputPerMillionTokens\":0,"
            + "\"outputPerMillionTokens\":0,\"cacheReadPerMillionTokens\":0,"
            + "\"cacheWritePerMillionTokens\":0,\"cacheWriteLongPerMillionTokens\":0,"
            + "\"reasoningPerMillionTokens\":0}}");
    jdbc.update(
        "insert into agent_definition (name, model_provider_name, model_name, config) "
            + "values (?, ?, ?, '{}'::jsonb)",
        agentName,
        providerName,
        modelName);
    return agentName;
  }
}
