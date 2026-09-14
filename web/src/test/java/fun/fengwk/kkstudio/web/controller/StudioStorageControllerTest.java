package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
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

import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.RecordingS3PresignService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * {@link StudioStorageController} 端到端测试（真实 PostgreSQL + 内存 S3 假件）。
 *
 * <p>覆盖 reserve/complete/delete/预签名端点的 HTTP 契约、错误翻译（400/404/409）、 上传生命周期与响应不泄露 bucket/key。
 *
 * @author fengwk
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
public class StudioStorageControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private RecordingS3PresignService s3Presigner;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private StorageBlobManager storageBlobManager;
  @MockitoBean private StorageMaintenance storageMaintenance;

  @BeforeEach
  void resetS3Fakes() {
    s3Storage.clear();
    s3Presigner.clear();
  }

  @Test
  public void reserveReturnsPendingWithChecksummedCreateOnlyPutAndNoLeak() throws Exception {
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    MvcResult result =
        mockMvc
            .perform(
                post("/api/storage/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        reserveBody("photo.png", "image/png", content.length, sha256Hex(content))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.state").value("PENDING"))
            .andExpect(jsonPath("$.data.blobId").value(nullValue()))
            .andExpect(jsonPath("$.data.presignedPut.method").value("PUT"))
            .andExpect(jsonPath("$.data.presignedPut.headers['if-none-match']").value("*"))
            .andExpect(
                jsonPath("$.data.presignedPut.headers['x-amz-checksum-sha256']")
                    .value(Base64.getEncoder().encodeToString(sha256(content))))
            .andExpect(
                jsonPath("$.data.presignedPut.url").value(containsString("X-Amz-Signature=")))
            .andExpect(jsonPath("$.data.expiresAt").isNumber())
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertFalse(body.contains("\"bucket\""), "reserve response must not expose bucket: " + body);
    assertFalse(body.contains("\"key\""), "reserve response must not expose physical key: " + body);

    RecordingS3PresignService.PresignRecord record =
        s3Presigner.records().get(s3Presigner.records().size() - 1);
    assertEquals(
        "checksummedCreateOnly", record.kind(), "reserve must sign a checksummed create-only PUT");
    assertEquals(
        StorageObjectKeys.uploadOriginal(UUID.fromString(readJsonString(body, "$.data.id"))),
        record.key());
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
  }

  @Test
  public void reserveCompleteAndDeleteLifecycle() throws Exception {
    byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
    String uploadId = reserveUpload("hello.txt", "text/plain", content.length, sha256Hex(content));

    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)), content, "text/plain");

    MvcResult completed =
        mockMvc
            .perform(post("/api/storage/uploads/" + uploadId + "/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("READY"))
            .andExpect(jsonPath("$.data.blobId").isString())
            .andExpect(jsonPath("$.data.presignedPut").value(nullValue()))
            .andReturn();
    String blobId = readJsonString(completed.getResponse().getContentAsString(), "$.data.blobId");

    MvcResult original =
        mockMvc
            .perform(post("/api/storage/blobs/" + blobId + "/download-url"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.method").value("GET"))
            .andExpect(
                jsonPath("$.data.url").value(containsString("blobs/" + blobId + "/original")))
            .andExpect(jsonPath("$.data.headers").isEmpty())
            .andExpect(jsonPath("$.data.mediaType").value("text/plain"))
            .andExpect(jsonPath("$.data.sizeBytes").value(Long.toString(content.length)))
            .andReturn();
    JsonNode originalJson = objectMapper.readTree(original.getResponse().getContentAsByteArray());
    assertTrue(originalJson.at("/data/sizeBytes").isTextual(), "sizeBytes must be a JSON string");
    assertEquals(Long.toString(content.length), originalJson.at("/data/sizeBytes").textValue());

    MvcResult preview =
        mockMvc
            .perform(post("/api/storage/blobs/" + blobId + "/preview-url"))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data.url").value(containsString("blobs/" + blobId + "/preview.webp")))
            .andExpect(jsonPath("$.data.mediaType").value(nullValue()))
            .andExpect(jsonPath("$.data.sizeBytes").value(nullValue()))
            .andReturn();
    JsonNode previewJson = objectMapper.readTree(preview.getResponse().getContentAsByteArray());
    assertTrue(previewJson.has("data"));
    assertTrue(previewJson.path("data").has("mediaType"));
    assertTrue(previewJson.at("/data/mediaType").isNull());
    assertTrue(previewJson.path("data").has("sizeBytes"));
    assertTrue(previewJson.at("/data/sizeBytes").isNull());

    mockMvc.perform(delete("/api/storage/uploads/" + uploadId)).andExpect(status().isNoContent());

    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where cleanup_requested_at is not null",
            Integer.class),
        "READY delete must first persist a durable cleanup request");
    assertEquals(
        "DELETING",
        jdbc.queryForObject("select state from storage_blob", String.class),
        "last upload reference must be released in the delete transaction");
    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(UUID.fromString(blobId))),
        "HTTP delete must not perform physical S3 cleanup on the request thread");

    assertEquals(1, storageUploadService.expireOnce());
    assertEquals(1, storageBlobManager.sweepDeleting());
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_upload", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(UUID.fromString(blobId))),
        "maintenance must delete the original object after finalizing the upload row");

    mockMvc
        .perform(delete("/api/storage/uploads/" + uploadId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STORAGE_NOT_FOUND"));
    mockMvc
        .perform(post("/api/storage/blobs/" + blobId + "/download-url"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STORAGE_NOT_FOUND"));
  }

  @Test
  public void reserveHitReturnsReadyWithoutPresignedPut() throws Exception {
    byte[] content = "dedup".getBytes(StandardCharsets.UTF_8);
    String firstUploadId =
        reserveUpload("a.bin", "application/octet-stream", content.length, sha256Hex(content));
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(firstUploadId)),
        content,
        "application/octet-stream");
    mockMvc
        .perform(post("/api/storage/uploads/" + firstUploadId + "/complete"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    reserveBody(
                        "b.bin", "application/octet-stream", content.length, sha256Hex(content))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.state").value("READY"))
        .andExpect(jsonPath("$.data.blobId").isString())
        .andExpect(jsonPath("$.data.presignedPut").value(nullValue()));

    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where state = 'ACTIVE'", Integer.class),
        "dedup hit must not create a second blob");
  }

  @Test
  public void reserveRejectsStringSizeBytesRequest() throws Exception {
    byte[] content = "sized".getBytes(StandardCharsets.UTF_8);
    mockMvc
        .perform(
            post("/api/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"filename":"s.bin","mediaType":"application/octet-stream",
                     "sizeBytes":"%d","sha256":"%s"}
                    """
                        .formatted(content.length, sha256Hex(content))))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void completeChecksumMismatchReturns409AndKeepsUploadPending() throws Exception {
    byte[] declared = new byte[] {1, 2, 3};
    String uploadId = reserveUpload("bad.bin", "application/octet-stream", 3, sha256Hex(declared));
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)),
        new byte[] {9, 9, 9},
        "application/octet-stream");

    mockMvc
        .perform(post("/api/storage/uploads/" + uploadId + "/complete"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STORAGE_VERIFICATION"))
        .andExpect(jsonPath("$.errors.detail").value(containsString("checksum")));

    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ? and blob_id is null",
            Integer.class,
            UUID.fromString(uploadId)),
        "mismatch must keep the upload PENDING for retry");
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
  }

  @Test
  public void completeSizeMismatchReturns409() throws Exception {
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    String uploadId = reserveUpload("size.bin", "application/octet-stream", 5, sha256Hex(content));
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)),
        content,
        "application/octet-stream");

    mockMvc
        .perform(post("/api/storage/uploads/" + uploadId + "/complete"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STORAGE_VERIFICATION"))
        .andExpect(jsonPath("$.errors.detail").value(containsString("size")));
  }

  @Test
  public void invalidRequestsMapTo400AndUnknownResourcesTo404() throws Exception {
    StorageUploadReserveRequestDTO blankFilename = new StorageUploadReserveRequestDTO();
    blankFilename.setFilename("  ");
    blankFilename.setMediaType("image/png");
    blankFilename.setSizeBytes(1L);
    blankFilename.setSha256("0".repeat(64));
    mockMvc
        .perform(
            post("/api/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(blankFilename)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("STORAGE_VALIDATION"));

    mockMvc
        .perform(post("/api/storage/uploads/not-a-uuid/complete"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("STORAGE_VALIDATION"));

    mockMvc
        .perform(post("/api/storage/uploads/" + UUID.randomUUID() + "/complete"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STORAGE_NOT_FOUND"));

    mockMvc
        .perform(post("/api/storage/blobs/" + UUID.randomUUID() + "/download-url"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/storage/blobs/" + UUID.randomUUID() + "/preview-url"))
        .andExpect(status().isNotFound());
  }

  @Test
  public void deletePendingPersistsCleanupRequestBeforeMaintenanceRemovesObjectAndRow()
      throws Exception {
    String uploadId =
        reserveUpload("abort.bin", "application/octet-stream", 1, sha256Hex(new byte[] {1}));
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)),
        new byte[] {1},
        "application/octet-stream");

    mockMvc.perform(delete("/api/storage/uploads/" + uploadId)).andExpect(status().isNoContent());

    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId))),
        "HTTP delete must not remove the temp object on the request thread");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where cleanup_requested_at is not null",
            Integer.class),
        "PENDING delete must persist a durable cleanup request");

    assertEquals(1, storageUploadService.expireOnce());
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId))),
        "maintenance must remove the temp object before finalizing the row");
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_upload", Integer.class));
  }

  private String reserveUpload(String filename, String mediaType, long size, String sha256)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/storage/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(reserveBody(filename, mediaType, size, sha256)))
            .andExpect(status().isCreated())
            .andReturn();
    return readJsonString(result.getResponse().getContentAsString(), "$.data.id");
  }

  private String reserveBody(String filename, String mediaType, long size, String sha256)
      throws Exception {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("filename", filename);
    request.put("mediaType", mediaType);
    request.put("sizeBytes", size);
    request.put("sha256", sha256);
    return objectMapper.writeValueAsString(request);
  }

  private static String readJsonString(String json, String path) {
    try {
      String value = JsonPath.read(json, path);
      assertTrue(value != null && !value.isBlank(), "json path " + path + " must resolve: " + json);
      return value;
    } catch (RuntimeException e) {
      throw new AssertionError("json path " + path + " failed on: " + json, e);
    }
  }

  private static String sha256Hex(byte[] content) {
    return HexFormat.of().formatHex(sha256(content));
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
