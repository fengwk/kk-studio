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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.RecordingS3PresignService;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * {@code StorageController} 端到端测试（真实 PostgreSQL + 内存 S3 假件）。
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
public class StorageControllerTest extends WebPostgresTestSupport {

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
                post("/api/platform/storage/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        reserveBody("photo.png", "image/png", content.length, sha256Hex(content))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.type").value("PENDING"))
            .andExpect(jsonPath("$.blobId").doesNotExist())
            .andExpect(jsonPath("$.presignedPut.method").value("PUT"))
            .andExpect(jsonPath("$.presignedPut.headers['if-none-match']").value("*"))
            .andExpect(
                jsonPath("$.presignedPut.headers['x-amz-checksum-sha256']")
                    .value(Base64.getEncoder().encodeToString(sha256(content))))
            .andExpect(jsonPath("$.presignedPut.url").value(containsString("X-Amz-Signature=")))
            .andExpect(jsonPath("$.expiresAt").isNumber())
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertFalse(body.contains("\"bucket\""), "reserve response must not expose bucket: " + body);
    assertFalse(body.contains("\"key\""), "reserve response must not expose physical key: " + body);

    RecordingS3PresignService.PresignRecord record =
        s3Presigner.records().get(s3Presigner.records().size() - 1);
    assertEquals(
        "checksummedCreateOnly", record.kind(), "reserve must sign a checksummed create-only PUT");
    assertEquals(
        StorageObjectKeys.uploadOriginal(UUID.fromString(readJsonString(body, "$.id"))),
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
            .perform(post("/api/platform/storage/uploads/" + uploadId + "/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("READY"))
            .andExpect(jsonPath("$.blobId").isString())
            .andExpect(jsonPath("$.presignedPut").doesNotExist())
            .andReturn();
    String blobId = readJsonString(completed.getResponse().getContentAsString(), "$.blobId");

    MvcResult original =
        mockMvc
            .perform(post("/api/platform/storage/blobs/" + blobId + "/download-url"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.method").value("GET"))
            .andExpect(jsonPath("$.url").value(containsString("blobs/" + blobId + "/original")))
            .andExpect(jsonPath("$.headers").isEmpty())
            .andExpect(jsonPath("$.mediaType").value("text/plain"))
            .andExpect(jsonPath("$.sizeBytes").value(Long.toString(content.length)))
            .andReturn();
    JsonNode originalJson = objectMapper.readTree(original.getResponse().getContentAsByteArray());
    assertTrue(originalJson.at("/sizeBytes").isTextual(), "sizeBytes must be a JSON string");
    assertEquals(Long.toString(content.length), originalJson.at("/sizeBytes").textValue());

    MvcResult preview =
        mockMvc
            .perform(post("/api/platform/storage/blobs/" + blobId + "/preview-url"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").value(containsString("blobs/" + blobId + "/preview.webp")))
            .andExpect(jsonPath("$.mediaType").value(nullValue()))
            .andExpect(jsonPath("$.sizeBytes").value(nullValue()))
            .andReturn();
    JsonNode previewJson = objectMapper.readTree(preview.getResponse().getContentAsByteArray());
    assertTrue(previewJson.has("mediaType"));
    assertTrue(previewJson.at("/mediaType").isNull());
    assertTrue(previewJson.has("sizeBytes"));
    assertTrue(previewJson.at("/sizeBytes").isNull());

    mockMvc
        .perform(delete("/api/platform/storage/uploads/" + uploadId))
        .andExpect(status().isNoContent());

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
        .perform(delete("/api/platform/storage/uploads/" + uploadId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
    mockMvc
        .perform(post("/api/platform/storage/blobs/" + blobId + "/download-url"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
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
        .perform(post("/api/platform/storage/uploads/" + firstUploadId + "/complete"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    reserveBody(
                        "b.bin", "application/octet-stream", content.length, sha256Hex(content))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("READY"))
        .andExpect(jsonPath("$.blobId").isString())
        .andExpect(jsonPath("$.presignedPut").doesNotExist());

    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where state = 'ACTIVE'", Integer.class),
        "dedup hit must not create a second blob");
  }

  /** sizeBytes 的 wire 形态是 canonical 十进制字符串；JSON 数字不是合法输入。 */
  @Test
  public void reserveAcceptsCanonicalStringSizeBytesAndRejectsJsonNumber() throws Exception {
    byte[] content = "sized".getBytes(StandardCharsets.UTF_8);
    mockMvc
        .perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    reserveBody(
                        "s.bin", "application/octet-stream", content.length, sha256Hex(content))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("PENDING"));

    ObjectNode numeric = objectMapper.createObjectNode();
    numeric.put("filename", "s.bin");
    numeric.put("mediaType", "application/octet-stream");
    numeric.put("sizeBytes", content.length);
    numeric.put("sha256", sha256Hex(content));
    mockMvc
        .perform(
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(numeric)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
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
        .perform(post("/api/platform/storage/uploads/" + uploadId + "/complete"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("STORAGE_VERIFICATION"))
        .andExpect(jsonPath("$.detail").value(containsString("checksum")));

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
        .perform(post("/api/platform/storage/uploads/" + uploadId + "/complete"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("STORAGE_VERIFICATION"))
        .andExpect(jsonPath("$.detail").value(containsString("size")));
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
            post("/api/platform/storage/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(blankFilename)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    mockMvc
        .perform(post("/api/platform/storage/uploads/not-a-uuid/complete"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    mockMvc
        .perform(post("/api/platform/storage/uploads/" + UUID.randomUUID() + "/complete"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));

    mockMvc
        .perform(post("/api/platform/storage/blobs/" + UUID.randomUUID() + "/download-url"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/platform/storage/blobs/" + UUID.randomUUID() + "/preview-url"))
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

    mockMvc
        .perform(delete("/api/platform/storage/uploads/" + uploadId))
        .andExpect(status().isNoContent());

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
                post("/api/platform/storage/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(reserveBody(filename, mediaType, size, sha256)))
            .andExpect(status().isCreated())
            .andReturn();
    return readJsonString(result.getResponse().getContentAsString(), "$.id");
  }

  private String reserveBody(String filename, String mediaType, long size, String sha256)
      throws Exception {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("filename", filename);
    request.put("mediaType", mediaType);
    request.put("sizeBytes", Long.toString(size));
    request.put("sha256", sha256);
    // Image reserves must report the browser-side image facts; these tests do not depend on them.
    if (mediaType.startsWith("image/")) {
      request.put("format", mediaType.substring("image/".length()));
      request.put("originalWidth", 1);
      request.put("originalHeight", 1);
      request.put("storedWidth", 1);
      request.put("storedHeight", 1);
    }
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
