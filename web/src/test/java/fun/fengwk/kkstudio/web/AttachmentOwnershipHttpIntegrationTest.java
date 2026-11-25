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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

/** HTTP attachment-ownership contracts backed by PostgreSQL Testcontainers and in-memory S3. */
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
  private static final byte[] CONTENT = "real attachment bytes".getBytes(StandardCharsets.UTF_8);

  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter accountFilter;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InMemoryS3StorageService s3;
  private MockMvc mvc;
  private Cookie administrator;
  private Cookie alice;
  private Cookie bob;

  @BeforeEach
  void setUp() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(accountFilter).build();
    administrator =
        login(WebAccountTestFixture.ADMIN_USERNAME, WebAccountTestFixture.ADMIN_PASSWORD);
    createUser("attach-owner-a");
    createUser("attach-owner-b");
    alice = login("attach-owner-a", "password-123");
    bob = login("attach-owner-b", "password-123");
  }

  @Test
  void usersCannotCompleteDeleteOrReferenceAnotherUsersUploadAndOwnerCanStillConsumeIt()
      throws Exception {
    mvc.perform(get("/api/platform/settings").cookie(alice).header("Origin", ORIGIN))
        .andExpect(status().isForbidden());
    Upload source = upload(alice, "shared-source.txt");

    // Upload endpoints are user-owned: neither another normal user nor an administrator can
    // complete or delete this upload; each refusal leaves the pending row available to Alice.
    postJson(uploadPath(source.id()) + "/complete", bob, "").andExpect(status().isNotFound());
    deleteWithOrigin(uploadPath(source.id()), bob).andExpect(status().isNotFound());
    postJson(uploadPath(source.id()) + "/complete", administrator, "")
        .andExpect(status().isNotFound());
    deleteWithOrigin(uploadPath(source.id()), administrator).andExpect(status().isNotFound());
    assertEquals(
        1, count("select count(*) from storage_upload where id = ?", UUID.fromString(source.id())));

    source = complete(alice, source);
    String blobId = source.blobId();
    String aliceSpace = createSpace(alice, "Alice");
    String aliceFolder = rootFolder(alice, aliceSpace);
    String bobSpace = createSpace(bob, "Bob");
    String bobFolder = rootFolder(bob, bobSpace);
    String bobSession = createSession(bob, bobSpace, bobFolder, "Bob's own session");

    // Cross-user Session attachment is rejected before a session/reference edge is created.
    long refCountBeforeMessageAttempt = blobRefCount(blobId);
    String badBobMessage = attachmentMessage(bobFolder, source.id());
    postJson("/api/chat-spaces/" + bobSpace + "/sessions", bob, badBobMessage)
        .andExpect(status().isBadRequest());
    assertEquals(refCountBeforeMessageAttempt, blobRefCount(blobId));
    assertEquals(
        0,
        count(
            "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
            UUID.fromString(bobSession),
            UUID.fromString(blobId)));

    // The same upload remains consumable by its owner after the rejected cross-user attempt.
    String aliceSession =
        createSession(alice, aliceSpace, aliceFolder, attachmentMessage(aliceFolder, source.id()));
    assertEquals(
        1,
        count(
            "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
            UUID.fromString(aliceSession),
            UUID.fromString(blobId)));
    // Text blobs have no generated preview object. Presigning is authorization against an ACTIVE
    // blob (StorageBlobManager.presignPreviewUrl); seed the object so the in-memory fixture also
    // models a usable preview URL target.
    s3.putDirect(StorageObjectKeys.blobPreview(UUID.fromString(blobId)), CONTENT, "image/webp");
    signSession(alice, aliceSession, blobId, "download-url").andExpect(status().isOk());
    signSession(alice, aliceSession, blobId, "preview-url").andExpect(status().isOk());

    signSession(bob, aliceSession, blobId, "download-url").andExpect(status().isNotFound());
    signSession(bob, aliceSession, blobId, "preview-url").andExpect(status().isNotFound());
    signSession(bob, bobSession, blobId, "download-url").andExpect(status().isNotFound());
    signSession(bob, bobSession, blobId, "preview-url").andExpect(status().isNotFound());
    getResource(bob, aliceSession, blobId).andExpect(status().isNotFound());
    getResource(alice, aliceSession, blobId).andExpect(status().isOk());
    getResource(bob, bobSession, blobId).andExpect(status().isNotFound());

    assertCanvasOwnership(alice, bob, aliceSpace, aliceFolder, bobSpace, bobFolder);

    Upload disposable = complete(alice, upload(alice, "delete-me.txt"));
    deleteWithOrigin(uploadPath(disposable.id()), alice).andExpect(status().isNoContent());
  }

  private void assertCanvasOwnership(
      Cookie owner,
      Cookie other,
      String ownerSpace,
      String ownerFolder,
      String otherSpace,
      String otherFolder)
      throws Exception {
    String canvasId = createCanvas(owner, ownerSpace, ownerFolder, "Owner canvas");
    String otherCanvas = createCanvas(other, otherSpace, otherFolder, "Other canvas");
    Upload source = complete(owner, upload(owner, "canvas-source.txt"));
    String blobId = source.blobId();

    // The other user's Canvas cannot consume Alice's READY upload; no resource edge is retained.
    long refCountBefore = blobRefCount(blobId);
    postJson(canvasCommandPath(otherCanvas), other, createBlobNodeCommand(source.id()))
        .andExpect(status().isNotFound());
    assertEquals(
        0,
        count(
            "select count(*) from canvas_resource where canvas_id = ?",
            UUID.fromString(otherCanvas)));
    assertEquals(refCountBefore, blobRefCount(blobId));

    // Alice's same upload still succeeds after Bob's rejected reference attempt.
    JsonNode accepted =
        json(
            postJson(canvasCommandPath(canvasId), owner, createBlobNodeCommand(source.id()))
                .andExpect(status().isOk())
                .andReturn());
    String resourceBlobId = accepted.at("/nodes/0/node/resources/0/blobId").asText();
    assertEquals(blobId, resourceBlobId);
    assertEquals(
        1,
        count(
            "select count(*) from canvas_resource where canvas_id = ? and blob_id = ?",
            UUID.fromString(canvasId),
            UUID.fromString(blobId)));

    // Canvas signing is keyed by Canvas + blob ID, not the chat-space route or resource-row ID.
    String path = "/api/canvas/canvases/" + canvasId + "/blobs/" + blobId;
    postJson(path + "/download-url", owner, "").andExpect(status().isOk());
    postJson(path + "/preview-url", owner, "").andExpect(status().isOk());
    postJson(path + "/download-url", other, "").andExpect(status().isNotFound());
    postJson(path + "/preview-url", other, "").andExpect(status().isNotFound());
    String otherCanvasBlobPath = "/api/canvas/canvases/" + otherCanvas + "/blobs/" + blobId;
    postJson(otherCanvasBlobPath + "/download-url", other, "").andExpect(status().isNotFound());
    postJson(otherCanvasBlobPath + "/preview-url", other, "").andExpect(status().isNotFound());
    // Also reject a blob not held by Alice's Canvas, even though Alice owns the Canvas.
    postJson(
            "/api/canvas/canvases/" + canvasId + "/blobs/" + UUID.randomUUID() + "/download-url",
            owner,
            "")
        .andExpect(status().isNotFound());
  }

  private Upload upload(Cookie user, String filename) throws Exception {
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
    String body =
        mapper.writeValueAsString(
            Map.of(
                "filename",
                filename,
                "mediaType",
                "text/plain",
                "sizeBytes",
                Integer.toString(CONTENT.length),
                "sha256",
                sha));
    JsonNode reserved =
        json(
            postJson("/api/platform/storage/uploads", user, body)
                .andExpect(status().isCreated())
                .andReturn());
    String id = reserved.get("id").asText();
    if ("READY".equals(reserved.path("type").asText())) {
      return new Upload(id, reserved.get("blobId").asText());
    }
    assertEquals("PENDING", reserved.path("type").asText());
    s3.putDirect(StorageObjectKeys.uploadOriginal(UUID.fromString(id)), CONTENT, "text/plain");
    return new Upload(id, null);
  }

  private Upload complete(Cookie user, Upload pending) throws Exception {
    if (pending.blobId() != null) {
      return pending;
    }
    JsonNode ready =
        json(
            postJson(uploadPath(pending.id()) + "/complete", user, "")
                .andExpect(status().isOk())
                .andReturn());
    assertEquals("READY", ready.get("type").asText());
    assertNotEquals(pending.id(), ready.get("blobId").asText());
    return new Upload(pending.id(), ready.get("blobId").asText());
  }

  private String createSpace(Cookie owner, String title) throws Exception {
    String body =
        mapper.writeValueAsString(
            Map.of(
                "title",
                title + " workspace",
                "rootFolderName",
                "Root",
                "defaultAgentName",
                "default-assistant"));
    return json(postJson("/api/chat-spaces", owner, body).andExpect(status().isOk()).andReturn())
        .get("id")
        .asText();
  }

  private String rootFolder(Cookie owner, String spaceId) throws Exception {
    return json(mvc.perform(
                get("/api/chat-spaces/{id}/tree", spaceId).cookie(owner).header("Origin", ORIGIN))
            .andExpect(status().isOk())
            .andReturn())
        .at("/folders/0/id")
        .asText();
  }

  private String createSession(Cookie owner, String spaceId, String folderId, String message)
      throws Exception {
    String body = message.startsWith("{") ? message : textMessage(folderId, message);
    return json(postJson("/api/chat-spaces/" + spaceId + "/sessions", owner, body)
            .andExpect(status().isOk())
            .andReturn())
        .get("sessionId")
        .asText();
  }

  private String createCanvas(Cookie owner, String spaceId, String folderId, String title)
      throws Exception {
    String treeVersion =
        json(mvc.perform(
                    get("/api/chat-spaces/{id}/tree", spaceId)
                        .cookie(owner)
                        .header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andReturn())
            .get("treeVersion")
            .asText();
    String body =
        mapper.writeValueAsString(
            Map.of("folderId", folderId, "title", title, "expectedTreeVersion", treeVersion));
    return json(postJson("/api/chat-spaces/" + spaceId + "/canvases", owner, body)
            .andExpect(status().isOk())
            .andReturn())
        .get("id")
        .asText();
  }

  private String createBlobNodeCommand(String uploadId) throws Exception {
    return new ClassPathResource("attachment-create-node.json", getClass())
        .getContentAsString(StandardCharsets.UTF_8)
        .formatted(UUID.randomUUID(), UUID.randomUUID(), uploadId);
  }

  private String attachmentMessage(String folderId, String uploadId) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "folderId",
            folderId,
            "message",
            new Object[] {Map.of("type", "ATTACHMENT", "uploadId", uploadId)}));
  }

  private String textMessage(String folderId, String text) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "folderId", folderId, "message", new Object[] {Map.of("type", "TEXT", "text", text)}));
  }

  private ResultActions signSession(Cookie user, String sessionId, String blobId, String action)
      throws Exception {
    return postJson(
        "/api/harness/sessions/" + sessionId + "/blobs/" + blobId + "/" + action, user, "");
  }

  private ResultActions getResource(Cookie user, String sessionId, String blobId) throws Exception {
    return mvc.perform(
        get("/api/harness/sessions/{sessionId}/resources/{resourceId}", sessionId, blobId)
            .cookie(user)
            .header("Origin", ORIGIN));
  }

  private ResultActions postJson(String path, Cookie user, String body) throws Exception {
    MockHttpServletRequestBuilder request =
        post(path)
            .header("Origin", ORIGIN)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (user != null) {
      request.cookie(user);
    }
    return mvc.perform(request);
  }

  private ResultActions deleteWithOrigin(String path, Cookie user) throws Exception {
    return mvc.perform(delete(path).cookie(user).header("Origin", ORIGIN));
  }

  private Cookie login(String username, String password) throws Exception {
    MvcResult result =
        postJson(
                "/api/auth/login",
                null,
                mapper.writeValueAsString(Map.of("username", username, "password", password)))
            .andExpect(status().isOk())
            .andReturn();
    return result.getResponse().getCookie("studio_session");
  }

  private void createUser(String username) throws Exception {
    postJson(
            "/api/users",
            administrator,
            mapper.writeValueAsString(
                Map.of("username", username, "password", "password-123", "role", "USER")))
        .andExpect(status().isOk());
  }

  private String uploadPath(String id) {
    return "/api/platform/storage/uploads/" + id;
  }

  private String canvasCommandPath(String id) {
    return "/api/canvas/canvases/" + id + "/commands";
  }

  private JsonNode json(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private long blobRefCount(String blobId) {
    return jdbc.queryForObject(
        "select ref_count from storage_blob where id = ?", Long.class, UUID.fromString(blobId));
  }

  private record Upload(String id, String blobId) {}
}
