package fun.fengwk.kkstudio.platform.cloudfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.impl.CloudQueryServiceImpl;

import java.time.Duration;
import java.util.Optional;

/** {@link CloudQueryService} 单元测试。 */
class CloudQueryServiceTest {

  private CloudFileSystemService fileSystemService;
  private StorageBlobFileReader blobFileReader;
  private CloudQueryService queryService;

  @BeforeEach
  void setUp() {
    fileSystemService = mock(CloudFileSystemService.class);
    blobFileReader = mock(StorageBlobFileReader.class);
    queryService = new CloudQueryServiceImpl(fileSystemService, Optional.of(blobFileReader));
  }

  @Test
  void windowTextBasicAndBounds() {
    // 意图：验证文本行窗口的基本切片、行号结构、换行标记与 nextOffset
    String content = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n";
    CloudQueryService.TextWindow window = queryService.windowText(content, 2, 2, 50 * 1024, 2000);

    assertEquals(2, window.offset());
    assertEquals(2, window.limit());
    assertEquals(5, window.totalLines());
    assertEquals(4, window.nextOffset());
    assertTrue(window.endsWithNewline());
    assertFalse(window.hasTruncatedLine());
    assertEquals(2, window.lines().size());

    assertEquals(2, window.lines().get(0).lineNumber());
    assertEquals("Line 2", window.lines().get(0).content());
    assertFalse(window.lines().get(0).truncated());

    assertEquals(3, window.lines().get(1).lineNumber());
    assertEquals("Line 3", window.lines().get(1).content());
    assertFalse(window.lines().get(1).truncated());

    assertEquals("Line 2\nLine 3", window.content());
  }

  @Test
  void windowTextBeyondTotalLines() {
    // 意图：当 offset 超出总行数时返回空列表且 nextOffset 为 null
    String content = "Hello\nWorld\n";
    CloudQueryService.TextWindow window = queryService.windowText(content, 10, 5, 50 * 1024, 2000);

    assertEquals(10, window.offset());
    assertEquals(2, window.totalLines());
    assertNull(window.nextOffset());
    assertTrue(window.lines().isEmpty());
    assertEquals("", window.content());
  }

  @Test
  void windowTextLineTruncation() {
    // 意图：当单行超长时截断并标记 truncated
    String longLine = "A".repeat(2500);
    String content = longLine + "\n";
    CloudQueryService.TextWindow window = queryService.windowText(content, 1, 10, 50 * 1024, 2000);

    assertTrue(window.hasTruncatedLine());
    assertEquals(1, window.lines().size());
    assertTrue(window.lines().get(0).truncated());
    assertTrue(window.lines().get(0).content().contains("line truncated to 2000 chars"));
  }

  @Test
  void windowTextValidation() {
    // 意图：验证 offset 与 limit 的边界校验
    assertThrows(
        IllegalArgumentException.class, () -> queryService.windowText("abc", 0, 10, 1024, 2000));
    assertThrows(
        IllegalArgumentException.class, () -> queryService.windowText("abc", 1, 0, 1024, 2000));
    assertThrows(
        IllegalArgumentException.class, () -> queryService.windowText("abc", 1, 2001, 1024, 2000));
  }

  @Test
  void findValidatesLimitsAndArtifactExclusion() {
    // 意图：验证 find 参数范围限制及 /.artifacts 禁入规则
    assertThrows(
        IllegalArgumentException.class,
        () -> queryService.find(CloudPath.of("/"), "*", 0, Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> queryService.find(CloudPath.of("/"), "*", 100_001, Duration.ofSeconds(5)));
    assertThrows(
        CloudPathForbiddenException.class,
        () -> queryService.find(CloudPath.of("/.artifacts"), "*", 10, Duration.ofSeconds(5)));
  }

  @Test
  void grepValidatesLimitsAndArtifactSingleFile() {
    // 意图：验证 grep 对非规范 artifact 路径拒绝访问
    assertThrows(
        IllegalArgumentException.class,
        () ->
            queryService.grep(
                CloudPath.of("/"), "pattern", null, false, false, false, 0, Duration.ofSeconds(5)));

    assertThrows(
        CloudPathForbiddenException.class,
        () ->
            queryService.grep(
                CloudPath.of("/.artifacts"),
                "test",
                null,
                false,
                false,
                false,
                10,
                Duration.ofSeconds(5)));
  }
}
