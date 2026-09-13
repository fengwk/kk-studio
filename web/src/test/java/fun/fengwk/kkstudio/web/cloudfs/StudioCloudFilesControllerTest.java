package fun.fengwk.kkstudio.web.cloudfs;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.cloudfs.blob.BlobReadResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditPatternNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudVersionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@link StudioCloudFilesController} 与 {@link StudioCloudFilesErrorAdvice} 契约测试。 */
class StudioCloudFilesControllerTest {

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private CloudFileSystemService fileSystemService;
  private CloudQueryService queryService;
  private StorageBlobManager blobManager;
  private StorageBlobFileReader blobFileReader;

  @BeforeEach
  void setUp() {
    fileSystemService = mock(CloudFileSystemService.class);
    queryService = mock(CloudQueryService.class);
    blobManager = mock(StorageBlobManager.class);
    blobFileReader = mock(StorageBlobFileReader.class);
    objectMapper = new ObjectMapper();

    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("storageBlobManager", blobManager);
    beanFactory.addBean("storageBlobFileReader", blobFileReader);

    StudioCloudFilesController controller =
        new StudioCloudFilesController(
            fileSystemService,
            queryService,
            beanFactory.getBeanProvider(StorageBlobManager.class),
            beanFactory.getBeanProvider(StorageBlobFileReader.class));

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new StudioCloudFilesErrorAdvice())
            .build();
  }

  @Test
  void getFilesDirectoryListingExcludesArtifacts() throws Exception {
    // 意图：根目录 listing 返回直接子节点，排除 /.artifacts，且按规范封装 CloudNodeDTO
    CloudNode rootNode =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name("")
            .kind(CloudNodeKind.DIRECTORY)
            .version(0L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    CloudNode docDir =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name("docs")
            .kind(CloudNodeKind.DIRECTORY)
            .version(1L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    CloudNode artifactDir =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name(".artifacts")
            .kind(CloudNodeKind.DIRECTORY)
            .version(0L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(fileSystemService.getNode(CloudPath.of("/"))).thenReturn(rootNode);
    when(fileSystemService.listChildren(CloudPath.of("/")))
        .thenReturn(List.of(artifactDir, docDir));

    mockMvc
        .perform(get("/api/cloud/files").param("path", "/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("OK"))
        .andExpect(jsonPath("$.data.node.path").value("/"))
        .andExpect(jsonPath("$.data.node.kind").value("DIRECTORY"))
        .andExpect(jsonPath("$.data.children", hasSize(1)))
        .andExpect(jsonPath("$.data.children[0].path").value("/docs"))
        .andExpect(jsonPath("$.data.children[0].name").value("docs"))
        .andExpect(jsonPath("$.data.children[0].version").value("1"))
        .andExpect(jsonPath("$.data.text").value(nullValue()))
        .andExpect(jsonPath("$.data.blob").value(nullValue()));
  }

  @Test
  void getFilesTextWindowingReturnsStructuredLines() throws Exception {
    // 意图：获取文本文件快照，lines 必须是结构化的 {lineNumber, content, truncated}
    CloudNode textNode =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name("readme.md")
            .kind(CloudNodeKind.TEXT)
            .version(3L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    CloudTextRevision rev =
        CloudTextRevision.builder()
            .nodeId(textNode.getId())
            .revision(3L)
            .content("line1\nline2\nline3\n")
            .sizeBytes(18)
            .sha256("c".repeat(64))
            .current(true)
            .build();

    when(fileSystemService.getNode(CloudPath.of("/readme.md"))).thenReturn(textNode);
    when(fileSystemService.readCurrentText(CloudPath.of("/readme.md"))).thenReturn(rev);

    CloudQueryService.TextWindow window =
        new CloudQueryService.TextWindow(
            1,
            2,
            3,
            3,
            true,
            false,
            List.of(
                new CloudQueryService.TextLine(1, "line1", false),
                new CloudQueryService.TextLine(2, "line2", false)),
            "line1\nline2");
    when(queryService.windowText(anyString(), eq(1), eq(2), anyInt(), anyInt())).thenReturn(window);

    mockMvc
        .perform(
            get("/api/cloud/files")
                .param("path", "/readme.md")
                .param("offset", "1")
                .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.node.path").value("/readme.md"))
        .andExpect(jsonPath("$.data.node.name").value("readme.md"))
        .andExpect(jsonPath("$.data.node.revision").value("3"))
        .andExpect(jsonPath("$.data.node.sizeBytes").value("18"))
        .andExpect(jsonPath("$.data.text.revision").value("3"))
        .andExpect(jsonPath("$.data.text.offset").value(1))
        .andExpect(jsonPath("$.data.text.limit").value(2))
        .andExpect(jsonPath("$.data.text.totalLines").value(3))
        .andExpect(jsonPath("$.data.text.nextOffset").value(3))
        .andExpect(jsonPath("$.data.text.lines", hasSize(2)))
        .andExpect(jsonPath("$.data.text.lines[0].lineNumber").value(1))
        .andExpect(jsonPath("$.data.text.lines[0].content").value("line1"))
        .andExpect(jsonPath("$.data.text.lines[0].truncated").value(false))
        .andExpect(jsonPath("$.data.children").value(nullValue()));
  }

  @Test
  void getFilesArtifactCanonicalAllowedAndNonCanonicalForbidden() throws Exception {
    // 意图：/.artifacts 仅支持精确 canonical 格式读取，非规范格式返回 403
    mockMvc
        .perform(get("/api/cloud/files").param("path", "/.artifacts/random_file"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLOUD_PATH_FORBIDDEN"));

    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath canonicalArtifactPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    CloudNode artifactNode =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name(invocationId + ".txt")
            .kind(CloudNodeKind.BLOB)
            .version(0L)
            .blobId(blobId)
            .build();
    when(fileSystemService.getNode(canonicalArtifactPath)).thenReturn(artifactNode);
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setMediaType("text/plain");
    blob.setSizeBytes(16L);
    blob.setSha256("a".repeat(64));
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    when(blobFileReader.read(blobId))
        .thenReturn(new BlobReadResult.Text("artifact content", "text/plain", 16L));
    when(queryService.windowText(eq("artifact content"), eq(1), eq(200), anyInt(), anyInt()))
        .thenReturn(
            new CloudQueryService.TextWindow(
                1,
                200,
                1,
                null,
                false,
                false,
                List.of(new CloudQueryService.TextLine(1, "artifact content", false)),
                "artifact content"));

    mockMvc
        .perform(get("/api/cloud/files").param("path", canonicalArtifactPath.value()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.node.path").value(canonicalArtifactPath.value()))
        .andExpect(jsonPath("$.data.text").value(nullValue()))
        .andExpect(jsonPath("$.data.blob.text.lines[0].content").value("artifact content"));
  }

  @Test
  void putTextAndPatchTextEndpoints() throws Exception {
    // 意图：验证 PUT /api/cloud/text 与 PATCH /api/cloud/text 契约
    CloudNode node =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name("notes.md")
            .kind(CloudNodeKind.TEXT)
            .version(1L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(fileSystemService.getNode(CloudPath.of("/notes.md"))).thenReturn(node);
    CloudTextRevision createdRevision =
        CloudTextRevision.builder()
            .nodeId(node.getId())
            .revision(1L)
            .content("# Title")
            .sizeBytes(7L)
            .sha256("a".repeat(64))
            .current(true)
            .build();
    CloudTextRevision editedRevision =
        CloudTextRevision.builder()
            .nodeId(node.getId())
            .revision(2L)
            .content("# New Title")
            .sizeBytes(11L)
            .sha256("b".repeat(64))
            .current(true)
            .build();
    when(fileSystemService.writeText(CloudPath.of("/notes.md"), "# Title", 0L))
        .thenReturn(createdRevision);
    when(fileSystemService.editText(CloudPath.of("/notes.md"), "Title", "New Title", 1L, false))
        .thenReturn(editedRevision);

    // PUT create/replace
    String putBody =
        """
        {
          "path": "/notes.md",
          "content": "# Title",
          "expectedRevision": "0"
        }
        """;
    mockMvc
        .perform(put("/api/cloud/text").contentType(MediaType.APPLICATION_JSON).content(putBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("/notes.md"))
        .andExpect(jsonPath("$.data.revision").value("1"))
        .andExpect(jsonPath("$.data.name").value("notes.md"));

    verify(fileSystemService).writeText(CloudPath.of("/notes.md"), "# Title", 0L);

    // PATCH edit
    String patchBody =
        """
        {
          "path": "/notes.md",
          "oldString": "Title",
          "newString": "New Title",
          "expectedRevision": "1",
          "replaceAll": false
        }
        """;
    mockMvc
        .perform(
            patch("/api/cloud/text").contentType(MediaType.APPLICATION_JSON).content(patchBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("/notes.md"))
        .andExpect(jsonPath("$.data.revision").value("2"))
        .andExpect(jsonPath("$.data.name").value("notes.md"));

    verify(fileSystemService).editText(CloudPath.of("/notes.md"), "Title", "New Title", 1L, false);
  }

  @Test
  void moveAndDeleteEndpoints() throws Exception {
    // 意图：验证 POST /api/cloud/nodes/move 与 DELETE /api/cloud/nodes
    CloudNode moved =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name("target.txt")
            .kind(CloudNodeKind.TEXT)
            .version(2L)
            .build();
    when(fileSystemService.moveNode(CloudPath.of("/src.txt"), CloudPath.of("/target.txt"), 1L))
        .thenReturn(moved);

    String moveBody =
        """
        {
          "sourcePath": "/src.txt",
          "destinationPath": "/target.txt",
          "expectedVersion": "1"
        }
        """;
    mockMvc
        .perform(
            post("/api/cloud/nodes/move").contentType(MediaType.APPLICATION_JSON).content(moveBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("/target.txt"))
        .andExpect(jsonPath("$.data.name").value("target.txt"));

    mockMvc
        .perform(
            delete("/api/cloud/nodes").param("path", "/target.txt").param("expectedVersion", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("NO_CONTENT"));

    verify(fileSystemService).deleteNode(CloudPath.of("/target.txt"), 2L);
  }

  @Test
  void mountBlobEndpoint() throws Exception {
    // 意图：验证 POST /api/cloud/blobs 挂载 READY upload
    UUID uploadId = UUID.randomUUID();
    CloudNode blobNode =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .name("image.png")
            .kind(CloudNodeKind.BLOB)
            .version(0L)
            .build();
    when(fileSystemService.attachBlobUpload(CloudPath.of("/image.png"), uploadId, true))
        .thenReturn(blobNode);

    String mountBody =
        """
        {
          "path": "/image.png",
          "uploadId": "%s",
          "expectedAbsent": true
        }
        """
            .formatted(uploadId);

    mockMvc
        .perform(
            post("/api/cloud/blobs").contentType(MediaType.APPLICATION_JSON).content(mountBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("/image.png"))
        .andExpect(jsonPath("$.data.name").value("image.png"));
  }

  @Test
  void findAndGrepEndpoints() throws Exception {
    // 意图：验证 POST /api/cloud/find 与 POST /api/cloud/grep
    when(queryService.find(eq(CloudPath.of("/")), eq("*.md"), eq(200), any(Duration.class)))
        .thenReturn(new CloudQueryService.FindResult(List.of("/readme.md"), false));

    String findBody =
        """
        {
          "path": "/",
          "pattern": "*.md"
        }
        """;
    mockMvc
        .perform(post("/api/cloud/find").contentType(MediaType.APPLICATION_JSON).content(findBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.paths[0]").value("/readme.md"))
        .andExpect(jsonPath("$.data.limited").value(false));

    when(queryService.grep(
            eq(CloudPath.of("/")),
            eq("TODO"),
            eq(null),
            eq(false),
            eq(false),
            eq(false),
            eq(100),
            any(Duration.class)))
        .thenReturn(
            new CloudQueryService.GrepResult(
                List.of(
                    new CloudQueryService.GrepMatch(CloudPath.of("/readme.md"), 5, "// TODO: fix")),
                false));

    String grepBody =
        """
        {
          "path": "/",
          "pattern": "TODO"
        }
        """;
    mockMvc
        .perform(post("/api/cloud/grep").contentType(MediaType.APPLICATION_JSON).content(grepBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matches[0].path").value("/readme.md"))
        .andExpect(jsonPath("$.data.matches[0].lineNumber").value(5))
        .andExpect(jsonPath("$.data.matches[0].content").value("// TODO: fix"));
  }

  @Test
  void errorAdviceCasExposesOnlyVersionAndRevisionAndNoContent() throws Exception {
    // 意图：409 CAS 冲突错误响应必须包含 expected/actual 字段与 path，且严禁泄露文件正文或正则
    when(fileSystemService.moveNode(any(), any(), anyLong()))
        .thenThrow(new CloudVersionConflictException(CloudPath.of("/file.txt"), 5L, 2L));

    String moveBody =
        """
        {
          "sourcePath": "/file.txt",
          "destinationPath": "/new.txt",
          "expectedVersion": "2"
        }
        """;
    mockMvc
        .perform(
            post("/api/cloud/nodes/move").contentType(MediaType.APPLICATION_JSON).content(moveBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLOUD_VERSION_CONFLICT"))
        .andExpect(jsonPath("$.errors.path").value("/file.txt"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("2"))
        .andExpect(jsonPath("$.errors.actualVersion").value("5"))
        .andExpect(jsonPath("$.errors.content").doesNotExist())
        .andExpect(jsonPath("$.errors.pattern").doesNotExist());

    when(fileSystemService.writeText(any(), any(), anyLong()))
        .thenThrow(new CloudRevisionConflictException(CloudPath.of("/text.txt"), 8L, 3L));

    String writeBody =
        """
        {
          "path": "/text.txt",
          "content": "super-secret-content-that-must-never-echo",
          "expectedRevision": "3"
        }
        """;
    mockMvc
        .perform(put("/api/cloud/text").contentType(MediaType.APPLICATION_JSON).content(writeBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLOUD_REVISION_CONFLICT"))
        .andExpect(jsonPath("$.errors.expectedRevision").value("3"))
        .andExpect(jsonPath("$.errors.actualRevision").value("8"))
        .andExpect(jsonPath("$.errors.content").doesNotExist())
        .andExpect(jsonPath("$.message", containsString("/text.txt")));
  }

  @Test
  void errorAdviceDoesNotEchoEditPattern() throws Exception {
    // 意图：edit pattern 未找到时返回 400，且严禁回显 oldString 正文
    when(fileSystemService.editText(any(), any(), any(), anyLong(), anyBoolean()))
        .thenThrow(new CloudEditPatternNotFoundException(CloudPath.of("/config.yaml")));

    String editBody =
        """
        {
          "path": "/config.yaml",
          "oldString": "secret_api_key_12345",
          "newString": "replacement",
          "expectedRevision": "1"
        }
        """;
    mockMvc
        .perform(patch("/api/cloud/text").contentType(MediaType.APPLICATION_JSON).content(editBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CLOUD_EDIT_PATTERN_NOT_FOUND"))
        .andExpect(jsonPath("$.message", containsString("/config.yaml")))
        .andExpect(jsonPath("$.message", not(containsString("secret_api_key"))));
  }
}
