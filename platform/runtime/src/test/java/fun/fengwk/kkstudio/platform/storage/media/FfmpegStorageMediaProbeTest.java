package fun.fengwk.kkstudio.platform.storage.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;
import fun.fengwk.kkstudio.platform.storage.s3.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.s3.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.s3.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageMediaFacts;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

class FfmpegStorageMediaProbeTest {

  @TempDir Path tempDir;

  /** 测试意图：媒体临时目录缺失是部署错配，探针必须回退 HEAD 元数据而不是让整条上传确认失败；预览服务已先建目录， 探针缺少同一步。 */
  @Test
  void missingTempDirectoryFallsBackToHeadMetadata() {
    StorageMediaProperties properties = new StorageMediaProperties();
    properties.setTempDir(tempDir.resolve("absent-media-temp"));
    // 不存在的二进制：即使临时目录可用，探测也会在内层 try 内失败并走回退分支。
    properties.setFfprobeBinary("kk-studio-absent-ffprobe");
    byte[] content = "plain-text-payload".getBytes(StandardCharsets.UTF_8);
    S3ObjectMetadata head = new S3ObjectMetadata(content.length, "text/plain", "etag");
    S3StorageService storageService = mock(S3StorageService.class);
    when(storageService.readObject("blobs/probe.txt"))
        .thenReturn(new S3ObjectStream(new ByteArrayInputStream(content), head));
    FfmpegStorageMediaProbe probe =
        new FfmpegStorageMediaProbe(
            properties,
            new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT),
            storageService,
            JsonMapper.builder().build());

    StorageMediaFacts facts = probe.probe("blobs/probe.txt", head);

    assertEquals(
        "text/plain", facts.mediaType(), "probe failure must fall back to HEAD media type");
    assertNull(facts.width(), "HEAD fallback carries no pixel width");
    assertNull(facts.height(), "HEAD fallback carries no pixel height");
    assertNull(facts.durationMs(), "HEAD fallback carries no duration");
  }
}
