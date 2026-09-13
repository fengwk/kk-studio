package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.BlobReadResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 验证 {@link CloudReadTool} 对目录、文本、BLOB 以及 Tool Artifact 的读取与分页行为。 */
class CloudReadToolTest {

  private CloudFileSystemService mockService;
  private StorageBlobFileReader mockBlobReader;
  private CloudReadTool readTool;

  @BeforeEach
  void setUp() {
    mockService = mock(CloudFileSystemService.class);
    mockBlobReader = mock(StorageBlobFileReader.class);
    readTool = new CloudReadTool(mockService, mockBlobReader);
  }

  @Test
  void readDirectorySortedWithTrailingSlash() {
    // 意图：读取目录应按名称升序排列直接子节点，子目录名称后追加 '/'
    CloudPath dirPath = CloudPath.of("/knowledge");
    CloudNode dirNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "knowledge",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode childDir =
        new CloudNode(
            UUID.randomUUID(),
            dirNode.getId(),
            "projects",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode childText =
        new CloudNode(
            UUID.randomUUID(),
            dirNode.getId(),
            "readme.md",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(dirPath)).thenReturn(dirNode);
    when(mockService.listChildren(dirPath)).thenReturn(List.of(childDir, childText));

    ToolResult result = execute(readTool, "{\"path\":\"/knowledge\",\"offset\":1,\"limit\":200}");

    assertFalse(result.error());
    String content = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(content.contains("path: /knowledge"));
    assertTrue(content.contains("kind: directory"));
    assertTrue(content.contains("projects/"));
    assertTrue(content.contains("readme.md"));
  }

  @Test
  void readTextFileWithLineNumbersAndTruncation() {
    // 意图：读取文本文件应返回包含 path、kind: text、revision、ends_with_newline 与带行号窗口
    CloudPath textPath = CloudPath.of("/knowledge/app.py");
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "app.py",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    String veryLongLine = "x = " + "a".repeat(2100);
    String fileContent = "line 1\n" + veryLongLine + "\nline 3\n";

    CloudTextRevision rev =
        new CloudTextRevision(textNode.getId(), 5L, fileContent, 100L, "hash", true, Instant.now());
    when(mockService.getNode(textPath)).thenReturn(textNode);
    when(mockService.readCurrentText(textPath)).thenReturn(rev);

    ToolResult result =
        execute(readTool, "{\"path\":\"/knowledge/app.py\",\"offset\":1,\"limit\":200}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("path: /knowledge/app.py"));
    assertTrue(text.contains("kind: text"));
    assertTrue(text.contains("revision: 5"));
    assertTrue(text.contains("ends_with_newline: yes"));
    assertTrue(text.contains("1|line 1"));
    assertTrue(text.contains("2|x = "));
    assertTrue(text.contains("(line truncated to 2000 chars)"));
    assertTrue(text.contains("3|line 3"));
  }

  @Test
  void readBlobImageReturnsMetadataAndBinaryContent() {
    // 意图：读取图片 BLOB 时应返回稳定元数据文本与 BinaryResultContent，以便网关外部化为 Resource
    CloudPath imagePath = CloudPath.of("/uploads/picture.png");
    UUID blobId = UUID.randomUUID();
    CloudNode blobNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "picture.png",
            CloudNodeKind.BLOB,
            1L,
            blobId,
            Instant.now(),
            Instant.now());
    byte[] imageBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G'};

    when(mockService.getNode(imagePath)).thenReturn(blobNode);
    when(mockBlobReader.read(blobId))
        .thenReturn(new BlobReadResult.Binary(imageBytes, "image/png", imageBytes.length));

    ToolResult result = execute(readTool, "{\"path\":\"/uploads/picture.png\"}");

    assertFalse(result.error());
    assertEquals(2, result.contents().size());
    TextResultContent metadata = (TextResultContent) result.contents().get(0);
    assertTrue(metadata.text().contains("path: /uploads/picture.png"));
    assertTrue(metadata.text().contains("media_type: image/png"));

    BinaryResultContent binary =
        assertInstanceOf(BinaryResultContent.class, result.contents().get(1));
    assertEquals("image/png", binary.mediaType());
  }

  @Test
  void readExactToolArtifactSuccessfully() {
    // 意图：通过精确 canonical Tool Artifact 路径读取大工具结果，应展示为规范文本窗口
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath artifactPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    UUID blobId = UUID.randomUUID();
    CloudNode artifactNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            invocationId + ".txt",
            CloudNodeKind.BLOB,
            1L,
            blobId,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(artifactPath)).thenReturn(artifactNode);
    when(mockBlobReader.read(blobId))
        .thenReturn(new BlobReadResult.Text("artifact content\n", "text/plain", 17L));

    ToolResult result = execute(readTool, "{\"path\":\"" + artifactPath.value() + "\"}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("path: " + artifactPath.value()));
    assertTrue(text.contains("1|artifact content"));
  }

  @Test
  void readArtifactDirectoryOrMalformedArtifactPathIsForbidden() {
    // 意图：对 /.artifacts 目录或非精确合法文件的读取请求应被明确拒绝
    ToolResult result = execute(readTool, "{\"path\":\"/.artifacts\"}");
    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(error.contains("forbidden") || error.contains("Artifact"));
  }

  @Test
  void readBlobTextSuccessfully() {
    // 意图：读取用户树下的文本型 BLOB 节点
    CloudPath blobPath = CloudPath.of("/data/blob.txt");
    UUID blobId = UUID.randomUUID();
    CloudNode blobNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "blob.txt",
            CloudNodeKind.BLOB,
            1L,
            blobId,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(blobPath)).thenReturn(blobNode);
    when(mockBlobReader.read(blobId))
        .thenReturn(new BlobReadResult.Text("line 1\nline 2", "text/plain", 13L));

    ToolResult result = execute(readTool, "{\"path\":\"/data/blob.txt\"}");
    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("kind: blob"));
    assertTrue(text.contains("1|line 1"));
  }

  @Test
  void readDirectoryPagination() {
    // 意图：验证目录读取分页提示以及 offset > total 提示
    CloudPath dirPath = CloudPath.of("/paginated");
    CloudNode dirNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "paginated",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode c1 =
        new CloudNode(
            UUID.randomUUID(),
            dirNode.getId(),
            "file1.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode c2 =
        new CloudNode(
            UUID.randomUUID(),
            dirNode.getId(),
            "file2.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(dirPath)).thenReturn(dirNode);
    when(mockService.listChildren(dirPath)).thenReturn(List.of(c1, c2));

    ToolResult pagedResult =
        execute(readTool, "{\"path\":\"/paginated\",\"offset\":1,\"limit\":1}");
    assertFalse(pagedResult.error());
    String pagedText = ((TextResultContent) pagedResult.contents().get(0)).text();
    assertTrue(
        pagedText.contains(
            "Showing entries 1-1 of 2. Re-run cloud_read with offset=2 to continue."));

    ToolResult outOfBoundResult =
        execute(readTool, "{\"path\":\"/paginated\",\"offset\":10,\"limit\":10}");
    assertFalse(outOfBoundResult.error());
    String outOfBoundText = ((TextResultContent) outOfBoundResult.contents().get(0)).text();
    assertTrue(outOfBoundText.contains("Showing entries 0-0 of 2"));
  }

  @Test
  void readTextPaginationAndOffsetOutOfBound() {
    // 意图：验证文本读取分页提示以及 offset > total 提示
    CloudPath textPath = CloudPath.of("/paged.txt");
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "paged.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudTextRevision rev =
        new CloudTextRevision(
            textNode.getId(), 1L, "line 1\nline 2\nline 3", 15L, "h", false, Instant.now());
    when(mockService.getNode(textPath)).thenReturn(textNode);
    when(mockService.readCurrentText(textPath)).thenReturn(rev);

    ToolResult pagedResult =
        execute(readTool, "{\"path\":\"/paged.txt\",\"offset\":1,\"limit\":2}");
    assertFalse(pagedResult.error());
    String pagedText = ((TextResultContent) pagedResult.contents().get(0)).text();
    assertTrue(
        pagedText.contains("Showing lines 1-2 of 3. Re-run cloud_read with offset=3 to continue."));

    ToolResult outOfBoundResult =
        execute(readTool, "{\"path\":\"/paged.txt\",\"offset\":10,\"limit\":10}");
    assertFalse(outOfBoundResult.error());
    String outOfBoundText = ((TextResultContent) outOfBoundResult.contents().get(0)).text();
    assertTrue(outOfBoundText.contains("Showing lines 0-0 of 3"));
  }

  @Test
  void unexpectedExceptionTriggersOnError() {
    CaptureListener listener = new CaptureListener();
    ToolCall call = new ToolCall("call-err", readTool.descriptor().name(), "{\"path\":\"/crash\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(readTool.descriptor(), call, Duration.ofSeconds(10));
    when(mockService.getNode(CloudPath.of("/crash"))).thenThrow(new RuntimeException("disk error"));

    readTool.execute(request, listener);
    assertNotNull(listener.error);
    assertTrue(listener.error.getMessage().contains("disk error"));
  }

  @Test
  void readWithInvalidOffsetOrLimitReturnsError() {
    // 意图：非法 offset 或 limit 返回语义错误
    ToolResult badOffset = execute(readTool, "{\"path\":\"/knowledge/doc.txt\",\"offset\":0}");
    assertTrue(badOffset.error());

    ToolResult badLimit = execute(readTool, "{\"path\":\"/knowledge/doc.txt\",\"limit\":3000}");
    assertTrue(badLimit.error());
  }

  @Test
  void strictJsonValidationRejectsMalformedJsonAndNonIntegerTypes() {
    // 意图：格式错误 JSON 或类型不符（字符串伪装整数、浮点数）应返回正常 Tool 错误而非非受控崩溃
    ToolResult malformed = executeWithRawArgs(readTool, "{malformed_json");
    assertTrue(malformed.error());

    ToolResult stringOffset =
        executeWithRawArgs(readTool, "{\"path\":\"/doc.txt\",\"offset\":\"1\"}");
    assertTrue(stringOffset.error());

    ToolResult floatLimit = executeWithRawArgs(readTool, "{\"path\":\"/doc.txt\",\"limit\":5.5}");
    assertTrue(floatLimit.error());

    ToolResult nonStringPath = executeWithRawArgs(readTool, "{\"path\":12345}");
    assertTrue(nonStringPath.error());
  }

  @Test
  void readStopsEarlyOnByteBudgetEvenWhenRequestedLimitIsHigher() {
    // 意图：当行内容较长累积达到内联字节预算时，即使 limit 未达，也在完整行边界提前停止并输出下一 offset
    CloudPath textPath = CloudPath.of("/large_lines.txt");
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "large_lines.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 200; i++) {
      sb.append("line ").append(i).append(": ").append("x".repeat(400)).append("\n");
    }
    CloudTextRevision rev =
        new CloudTextRevision(
            textNode.getId(), 1L, sb.toString(), (long) sb.length(), "hash", true, Instant.now());
    when(mockService.getNode(textPath)).thenReturn(textNode);
    when(mockService.readCurrentText(textPath)).thenReturn(rev);

    ToolResult result =
        execute(readTool, "{\"path\":\"/large_lines.txt\",\"offset\":1,\"limit\":200}");
    assertFalse(result.error());
    String output = ((TextResultContent) result.contents().get(0)).text();
    // 验证在 40 KiB 预算下提前终止，输出字节严格受控且带有下一 offset 指示
    assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= CloudReadTool.INLINE_BYTE_BUDGET);
    assertTrue(output.contains("Re-run cloud_read with offset="));
    assertFalse(output.contains("200|line 200:"));
  }

  @Test
  void directoryListingTruncatesWhenEntrySizesExceedBudget() {
    // 意图：当目录项过多或名字过长导致输出超出 40 KiB 预算时，保持头部 intact，在完整 entry 边界分页截断
    CloudPath dirPath = CloudPath.of("/huge_dir");
    CloudNode dirNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "huge_dir",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    List<CloudNode> children = new ArrayList<>();
    for (int i = 1; i <= 500; i++) {
      children.add(
          new CloudNode(
              UUID.randomUUID(),
              dirNode.getId(),
              "entry_item_" + i + "_" + "x".repeat(230) + ".txt",
              CloudNodeKind.TEXT,
              1L,
              null,
              Instant.now(),
              Instant.now()));
    }
    when(mockService.getNode(dirPath)).thenReturn(dirNode);
    when(mockService.listChildren(dirPath)).thenReturn(children);

    ToolResult result = execute(readTool, "{\"path\":\"/huge_dir\",\"offset\":1,\"limit\":500}");
    assertFalse(result.error());
    String output = ((TextResultContent) result.contents().get(0)).text();

    assertTrue(output.startsWith("path: /huge_dir\nkind: directory\n\n"));
    assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= CloudReadTool.INLINE_BYTE_BUDGET);
    assertTrue(output.contains("Showing entries 1-"));
    assertTrue(output.contains("Re-run cloud_read with offset="));
    assertFalse(output.contains("entry_item_500_"));

    // 验证真实准确的下一 offset
    Matcher m =
        Pattern.compile(
                "Showing entries 1-(\\d+) of 500\\. Re-run cloud_read with offset=(\\d+) to continue\\.")
            .matcher(output);
    assertTrue(m.find(), "Must contain valid pagination note");
    int endEntry = Integer.parseInt(m.group(1));
    int nextOffset = Integer.parseInt(m.group(2));
    assertEquals(endEntry + 1, nextOffset, "Next offset must be endEntry + 1");
  }

  @Test
  void maxPathAndAstralFirstLineStrictlyUnderByteBudgetWithTruthfulOffset() {
    // 意图：超长路径（如 500 字符）+ 首行含 2000 个 4 字节 Astral Emoji 字符 + 后续多行，
    // 验证完整输出严格 <= 40 KiB，UTF-8 合法，且下一 offset 真实准确
    String maxPathStr = "/long_root/" + "sub_segment/".repeat(30) + "astral_doc.txt";
    CloudPath textPath = CloudPath.of(maxPathStr);
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "astral_doc.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    StringBuilder content = new StringBuilder();
    // 首行：2000 个 🚀 (每个 4 字节 UTF-8，单行超 8000 字节)
    content.append("🚀".repeat(2000)).append("\n");
    // 后续 100 行普通内容
    for (int i = 2; i <= 100; i++) {
      content.append("line ").append(i).append(": ").append("data_".repeat(100)).append("\n");
    }

    CloudTextRevision rev =
        new CloudTextRevision(
            textNode.getId(),
            1L,
            content.toString(),
            (long) content.length(),
            "hash",
            true,
            Instant.now());
    when(mockService.getNode(textPath)).thenReturn(textNode);
    when(mockService.readCurrentText(textPath)).thenReturn(rev);

    ToolResult result =
        execute(readTool, "{\"path\":\"" + maxPathStr + "\",\"offset\":1,\"limit\":100}");
    assertFalse(result.error());
    String output = ((TextResultContent) result.contents().get(0)).text();

    assertTrue(
        output.getBytes(StandardCharsets.UTF_8).length <= CloudReadTool.INLINE_BYTE_BUDGET,
        "Total UTF-8 bytes must not exceed 40 KiB inline budget");

    // 验证截断后是合法的 UTF-8，没有拆碎 surrogate pair
    assertDoesNotThrow(
        () ->
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.getBytes(StandardCharsets.UTF_8))));

    // 验证行号与下一 offset 真实准确
    Matcher mLine =
        Pattern.compile(
                "Showing lines 1-(\\d+) of (\\d+)\\. Re-run cloud_read with offset=(\\d+) to continue\\.")
            .matcher(output);
    assertTrue(mLine.find(), "Must contain truthful pagination footnote");
    int endLine = Integer.parseInt(mLine.group(1));
    int totalLines = Integer.parseInt(mLine.group(2));
    int nextOffset = Integer.parseInt(mLine.group(3));
    assertEquals(100, totalLines, "Total lines must be 100");
    assertEquals(endLine + 1, nextOffset, "Truthful next offset must be endLine + 1");
    assertTrue(endLine < 100, "Must have stopped early before line 100 due to byte budget");
  }

  @Test
  void singleLinePushingPastBudgetTruncatesSafelyWithoutSplittingSurrogates() {
    // 意图：哪怕单行超长超过 40 KiB 预算，也要安全截断该行，保留头部且绝不拆散 Unicode 代理对
    CloudPath textPath = CloudPath.of("/single_huge_line.txt");
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "single_huge_line.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    // 50 KiB 的复杂 Unicode 行（含中文字符与 emoji）
    String line = "🎉你好KK" + "🚀数据科学".repeat(4000) + "\n";
    CloudTextRevision rev =
        new CloudTextRevision(
            textNode.getId(), 1L, line, (long) line.length(), "hash", true, Instant.now());
    when(mockService.getNode(textPath)).thenReturn(textNode);
    when(mockService.readCurrentText(textPath)).thenReturn(rev);

    ToolResult result =
        execute(readTool, "{\"path\":\"/single_huge_line.txt\",\"offset\":1,\"limit\":10}");
    assertFalse(result.error());
    String output = ((TextResultContent) result.contents().get(0)).text();

    assertTrue(output.startsWith("path: /single_huge_line.txt\nkind: text\nrevision: 1\n"));
    assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= CloudReadTool.INLINE_BYTE_BUDGET);
    assertTrue(output.contains("one or more lines were truncated"));
    // 验证截断后是合法的 UTF-8，没有拆碎 surrogate pair
    assertDoesNotThrow(
        () ->
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void readArtifactWhenNodeIsNotBlobThrowsException() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath artPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            artPath.name(),
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(artPath)).thenReturn(textNode);
    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-art", readTool.descriptor().name(), "{\"path\":\"" + artPath.value() + "\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(readTool.descriptor(), call, Duration.ofSeconds(5));
    readTool.execute(request, listener);
    assertNotNull(listener.error);
    assertTrue(listener.error.getMessage().contains("not a valid blob"));
  }

  @Test
  void readArtifactWhenReaderIsNullThrowsException() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath artPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    CloudNode blobNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            artPath.name(),
            CloudNodeKind.BLOB,
            1L,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now());
    CloudReadTool toolWithoutReader = new CloudReadTool(mockService, null);
    when(mockService.getNode(artPath)).thenReturn(blobNode);
    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-art-null",
            toolWithoutReader.descriptor().name(),
            "{\"path\":\"" + artPath.value() + "\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(toolWithoutReader.descriptor(), call, Duration.ofSeconds(5));
    toolWithoutReader.execute(request, listener);
    assertNotNull(listener.error);
    assertTrue(listener.error.getMessage().contains("Blob content reader is not available"));
  }

  @Test
  void readArtifactWhenBinaryReturnsError() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath artPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    UUID blobId = UUID.randomUUID();
    CloudNode blobNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            artPath.name(),
            CloudNodeKind.BLOB,
            1L,
            blobId,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(artPath)).thenReturn(blobNode);
    when(mockBlobReader.read(blobId))
        .thenReturn(new BlobReadResult.Binary(new byte[] {1, 2}, "application/octet-stream", 2L));
    ToolResult result = execute(readTool, "{\"path\":\"" + artPath.value() + "\"}");
    assertTrue(result.error());
    assertTrue(((TextResultContent) result.contents().get(0)).text().contains("not valid UTF-8"));
  }

  @Test
  void readBlobWhenReaderIsNullThrowsException() {
    CloudPath blobPath = CloudPath.of("/file.png");
    CloudNode blobNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "file.png",
            CloudNodeKind.BLOB,
            1L,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now());
    CloudReadTool toolWithoutReader = new CloudReadTool(mockService, null);
    when(mockService.getNode(blobPath)).thenReturn(blobNode);
    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-blob-null", toolWithoutReader.descriptor().name(), "{\"path\":\"/file.png\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(toolWithoutReader.descriptor(), call, Duration.ofSeconds(5));
    toolWithoutReader.execute(request, listener);
    assertNotNull(listener.error);
    assertTrue(listener.error.getMessage().contains("Blob content reader is not available"));
  }

  @Test
  void readEmptyDirectory() {
    CloudPath dirPath = CloudPath.of("/empty");
    CloudNode dirNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "empty",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(dirPath)).thenReturn(dirNode);
    when(mockService.listChildren(dirPath)).thenReturn(List.of());
    ToolResult result = execute(readTool, "{\"path\":\"/empty\"}");
    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("path: /empty"));
    assertTrue(text.contains("kind: directory"));
  }

  private static ToolResult executeWithRawArgs(CloudReadTool tool, String argsJson) {
    CaptureListener listener = new CaptureListener();
    ToolCall call = mock(ToolCall.class);
    when(call.id()).thenReturn("call-raw");
    when(call.argumentsJson()).thenReturn(argsJson);
    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    when(request.call()).thenReturn(call);
    tool.execute(request, listener);
    assertNotNull(listener.outcome, "listener outcome must be called");
    return listener.outcome.result();
  }

  private static ToolResult execute(CloudReadTool tool, String argsJson) {
    CaptureListener listener = new CaptureListener();
    ToolCall call = new ToolCall("call-test", tool.descriptor().name(), argsJson);
    ToolExecutionRequest request =
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(10));
    tool.execute(request, listener);
    assertNotNull(listener.outcome, "listener outcome must be called");
    return listener.outcome.result();
  }

  private static class CaptureListener implements ToolExecutionListener {
    ToolOutcome outcome;
    Throwable error;

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolOutcome outcome) {
      this.outcome = outcome;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
    }
  }
}
