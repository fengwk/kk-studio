package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Real HTTP attachment-ownership checks against the shared PostgreSQL Testcontainers database. */
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
class AttachmentOwnershipHttpIntegrationTest extends WebPostgresTestSupport {
  private static final String ORIGIN = "http://localhost";
  private static final byte[] BYTES = "attachment bytes".getBytes(StandardCharsets.UTF_8);

  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter accountFilter;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InMemoryS3StorageService s3;
  @Autowired private WebAccountMockMvcTestConfiguration.AdminCredentials admin;
  private MockMvc mvc;
  private Cookie adminCookie;
  private Cookie alice;
  private Cookie bob;

  @BeforeEach
  void setUp() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(accountFilter).build();
    adminCookie = new Cookie("studio_session", admin.token());
    Cookie administrator = login("kkstudio", "kkstudio");
    createUser(administrator, "attach-a", "USER");
    createUser(administrator, "attach-b", "USER");
    alice = login("attach-a", "password-123");
    bob = login("attach-b", "password-123");
  }

  @Test
  void uploadLifecycleIsBoundToTheReservingUserAndAdminRouteIsForbidden() throws Exception {
    MvcResult pending = reserve(alice, "source.txt");
    JsonNode reserved = mapper.readTree(pending.getResponse().getContentAsString());
    String uploadId = reserved.get("id").asText();
    assertEquals("PENDING", reserved.get("type").asText());
    UUID id = UUID.fromString(uploadId);
    s3.putDirect(StorageObjectKeys.uploadOriginal(id), BYTES, "text/plain");

    mvc.perform(post("/api/platform/storage/uploads/{id}/complete", uploadId).cookie(bob).header("Origin", ORIGIN))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/platform/storage/uploads/{id}", uploadId).cookie(bob).header("Origin", ORIGIN))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/platform/storage/uploads/{id}/complete", uploadId).cookie(adminCookie).header("Origin", ORIGIN))
        .andExpect(status().isNotFound());
    assertEquals(1, jdbc.queryForObject("select count(*) from storage_upload where id = ?", Integer.class, id));

    JsonNode ready = mapper.readTree(mvc.perform(post("/api/platform/storage/uploads/{id}/complete", uploadId)
            .cookie(alice).header("Origin", ORIGIN))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals("READY", ready.get("type").asText());
    assertNotEquals(uploadId, ready.get("blobId").asText());
    mvc.perform(delete("/api/platform/storage/uploads/{id}", uploadId).cookie(bob).header("Origin", ORIGIN))
        .andExpect(status().isNotFound());
    assertEquals(1, jdbc.queryForObject("select count(*) from storage_upload where id = ?", Integer.class, id));
    mvc.perform(delete("/api/platform/storage/uploads/{id}", uploadId).cookie(alice).header("Origin", ORIGIN))
        .andExpect(status().isNoContent());
  }

  @Test
  void sessionSigningStreamAndCanvasSigningAreScopedToResourceOwnership() throws Exception {
    // A owns the workspace and session; B has a distinct authenticated browser session.
    MvcResult spaceResult = mvc.perform(post("/api/chat-spaces").cookie(alice).header("Origin", ORIGIN)
            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Attachment workspace\",\"rootFolderName\":\"Root\",\"defaultAgentName\":\"default-assistant\"}"))
        .andExpect(status().isOk()).andReturn();
    String spaceId = mapper.readTree(spaceResult.getResponse().getContentAsString()).get("id").asText();
    MvcResult treeResult = mvc.perform(get("/api/chat-spaces/{id}/tree", spaceId).cookie(alice)).andExpect(status().isOk()).andReturn();
    String folderId = mapper.readTree(treeResult.getResponse().getContentAsString()).at("/folders/0/id").asText();
    JsonNode pending = mapper.readTree(reserve(alice, "session.txt").getResponse().getContentAsString());
    String uploadId = pending.get("id").asText();
    s3.putDirect(StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)), BYTES, "text/plain");
    JsonNode ready = mapper.readTree(mvc.perform(post("/api/platform/storage/uploads/{id}/complete", uploadId)
        .cookie(alice).header("Origin", ORIGIN)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    String blobId = ready.get("blobId").asText();

    // Session message attachment forms a real session-held blob edge through the HTTP command path.
    String message = "{\"folderId\":\"" + folderId + "\",\"message\":[{\"type\":\"TEXT\",\"text\":\"file\"},{\"type\":\"ATTACHMENT\",\"uploadId\":\"" + uploadId + "\"}]}";
    MvcResult sessionResult = mvc.perform(post("/api/chat-spaces/{id}/sessions", spaceId).cookie(alice)
            .header("Origin", ORIGIN).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(message))
        .andExpect(status().isOk()).andReturn();
    String sessionId = mapper.readTree(sessionResult.getResponse().getContentAsString()).get("sessionId").asText();
    mvc.perform(post("/api/harness/sessions/{id}/blobs/{blob}/download-url", sessionId, blobId).cookie(alice))
        .andExpect(status().isOk());
    mvc.perform(post("/api/harness/sessions/{id}/blobs/{blob}/preview-url", sessionId, blobId).cookie(alice))
        .andExpect(status().isOk());
    mvc.perform(get("/api/harness/sessions/{id}/resources/{blob}", sessionId, blobId).cookie(bob))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/harness/sessions/{id}/resources/{blob}", sessionId, blobId).cookie(alice))
        .andExpect(status().isOk());

    // B's independently-owned Session has no edge to A's blob and cannot sign it.
    MvcResult bobSpace = mvc.perform(post("/api/chat-spaces").cookie(bob).header("Origin", ORIGIN)
            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Bob workspace\",\"rootFolderName\":\"Root\",\"defaultAgentName\":\"default-assistant\"}"))
        .andExpect(status().isOk()).andReturn();
    String bobSpaceId = mapper.readTree(bobSpace.getResponse().getContentAsString()).get("id").asText();
    String bobFolder = mapper.readTree(mvc.perform(get("/api/chat-spaces/{id}/tree", bobSpaceId).cookie(bob))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/folders/0/id").asText();
    MvcResult bobSessionResult = mvc.perform(post("/api/chat-spaces/{id}/sessions", bobSpaceId).cookie(bob)
            .header("Origin", ORIGIN).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"folderId\":\"" + bobFolder + "\",\"message\":[{\"type\":\"TEXT\",\"text\":\"own\"}]}"))
        .andExpect(status().isOk()).andReturn();
    String bobSession = mapper.readTree(bobSessionResult.getResponse().getContentAsString()).get("sessionId").asText();
    mvc.perform(post("/api/harness/sessions/{id}/blobs/{blob}/download-url", bobSession, blobId).cookie(bob))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/harness/sessions/{id}/blobs/{blob}/preview-url", bobSession, blobId).cookie(bob))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/harness/sessions/{id}/resources/{blob}", bobSession, blobId).cookie(bob))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/harness/sessions/{id}/blobs/{blob}/download-url", UUID.randomUUID(), blobId).cookie(alice))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/harness/sessions/{id}/blobs/{blob}/preview-url", UUID.randomUUID(), blobId).cookie(alice))
        .andExpect(status().isNotFound());
  }

  @Test
  void canvasSigningRequiresBothCanvasOwnershipAndItsResourceEdge() throws Exception {
    MvcResult space = mvc.perform(post("/api/chat-spaces").cookie(alice).header("Origin", ORIGIN)
            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Canvas owner\",\"rootFolderName\":\"Root\",\"defaultAgentName\":\"default-assistant\"}"))
        .andExpect(status().isOk()).andReturn();
    String spaceId = mapper.readTree(space.getResponse().getContentAsString()).get("id").asText();
    String folderId = mapper.readTree(mvc.perform(get("/api/chat-spaces/{id}/tree", spaceId).cookie(alice))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/folders/0/id").asText();
    MvcResult canvasResult = mvc.perform(post("/api/chat-spaces/{id}/canvases", spaceId).cookie(alice)
            .header("Origin", ORIGIN).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"folderId\":\"" + folderId + "\",\"title\":\"Source board\",\"expectedTreeVersion\":\"0\"}"))
        .andExpect(status().isOk()).andReturn();
    String canvasId = mapper.readTree(canvasResult.getResponse().getContentAsString()).get("id").asText();

    JsonNode pending = mapper.readTree(reserve(alice, "canvas.txt").getResponse().getContentAsString());
    String uploadId = pending.get("id").asText();
    s3.putDirect(StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)), BYTES, "text/plain");
    mvc.perform(post("/api/platform/storage/uploads/{id}/complete", uploadId).cookie(alice).header("Origin", ORIGIN))
        .andExpect(status().isOk());
    String nodeId = UUID.randomUUID().toString();
    String command = "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"commands\":[{\"type\":\"CREATE_NODE\",\"nodeId\":\"" + nodeId + "\",\"name\":\"source\",\"transform\":{\"x\":0,\"y\":0,\"width\":10,\"height\":10},\"resources\":[{\"type\":\"BLOB\",\"name\":\"canvas.txt\",\"uploadId\":\"" + uploadId + "\"}]}]}";
    MvcResult patch = mvc.perform(post("/api/canvas/canvases/{id}/commands", canvasId).cookie(alice)
            .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(command))
        .andExpect(status().isOk()).andReturn();
    String resourceId = mapper.readTree(patch.getResponse().getContentAsString())
        .at("/nodes/0/node/resources/0/id").asText();
    String resourcePath = "/api/canvas/canvases/" + canvasId + "/resources/" + resourceId;
    mvc.perform(post(resourcePath + "/download-url").cookie(alice)).andExpect(status().isOk());
    mvc.perform(post(resourcePath + "/preview-url").cookie(alice)).andExpect(status().isOk());
    mvc.perform(post(resourcePath + "/download-url").cookie(bob)).andExpect(status().isNotFound());
    mvc.perform(post(resourcePath + "/preview-url").cookie(bob)).andExpect(status().isNotFound());
  }

  private MvcResult reserve(Cookie cookie, String filename) throws Exception {
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
    return mvc.perform(post("/api/platform/storage/uploads").cookie(cookie).header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("filename", filename, "mediaType", "text/plain", "sizeBytes", BYTES.length, "sha256", sha))))
        .andExpect(status().isCreated()).andReturn();
  }

  private Cookie login(String username, String password) throws Exception {
    MvcResult result = mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().isOk()).andReturn();
    return result.getResponse().getCookie("studio_session");
  }

  private void createUser(Cookie administrator, String username, String role) throws Exception {
    mvc.perform(post("/api/users").cookie(administrator).header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("username", username, "password", "password-123", "role", role))))
        .andExpect(status().isOk());
  }
}
