package fun.fengwk.kkstudio.platform.ai.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;

/** {@link ManagedResourceDownloadService} 的内容身份重建与结果隔离测试。 */
class ManagedResourceDownloadServiceTest {

  private static final String SHA =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  private final ResourceStore resourceStore = mock(ResourceStore.class);
  private final ManagedResourceDownloadService service =
      new ManagedResourceDownloadService(resourceStore);

  /** 验证 Platform 只按内容身份读取 store 自己重建的 canonical 引用。 */
  @Test
  void downloadsStoreOwnedCanonicalResource() {
    byte[] stored = "abc".getBytes();
    ResourceRef reference =
        new ResourceRef("file:///tmp/resources/" + SHA, "text/plain", "result.txt", 3L, SHA);
    when(resourceStore.reference("text/plain", "result.txt", 3L, SHA)).thenReturn(reference);
    when(resourceStore.read(reference)).thenReturn(stored);

    ManagedResourceDownload result = service.download(SHA, "text/plain", 3L, "result.txt");

    assertEquals("result.txt", result.getFilename());
    assertEquals("text/plain", result.getMediaType());
    assertEquals(SHA, result.getSha256());
    assertArrayEquals(stored, result.getContent());
    verify(resourceStore).reference("text/plain", "result.txt", 3L, SHA);
    verify(resourceStore).read(reference);
  }

  /** 验证无展示名时使用摘要作为文件名，且返回字节不会暴露内部数组。 */
  @Test
  void fallsBackToShaFilenameAndDefensivelyCopiesContent() {
    byte[] stored = "abc".getBytes();
    ResourceRef reference =
        new ResourceRef("file:///tmp/resources/" + SHA, "text/plain", null, 3L, SHA);
    when(resourceStore.reference("text/plain", null, 3L, SHA)).thenReturn(reference);
    when(resourceStore.read(reference)).thenReturn(stored);

    ManagedResourceDownload result = service.download(SHA, "text/plain", 3L, null);
    stored[0] = 'z';
    byte[] firstRead = result.getContent();
    firstRead[1] = 'z';

    assertEquals(SHA, result.getFilename());
    assertArrayEquals("abc".getBytes(), result.getContent());
  }
}
