package fun.fengwk.kkstudio.core.studio.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

class FfmpegCanvasMediaProcessorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @TempDir Path tempDir;

  /** 真实 ffprobe/ffmpeg fixture 覆盖三种媒体 metadata 与 IMAGE/VIDEO preview。 */
  @Test
  void probesFixturesAndCreatesOnlyRequiredPreviews() throws Exception {
    assertMedia(CanvasResourceKind.IMAGE, "tiny.png", "image/png", true, "width");
    assertMedia(CanvasResourceKind.VIDEO, "tiny.mp4", "video/mp4", true, "durationMs");
    assertMedia(CanvasResourceKind.AUDIO, "tiny.mp3", "audio/mpeg", false, "durationMs");
  }

  /** 非零 ffprobe 必须 fail closed，且失败后临时工作目录为空。 */
  @Test
  void rejectsProbeFailureAndCleansTempFiles() throws Exception {
    Path failingProbe = writeScript("ffprobe-fail", "echo broken >&2\nexit 7\n");
    CanvasMediaProperties properties = properties();
    properties.setFfprobeBinary(failingProbe.toString());
    FfmpegCanvasMediaProcessor processor = new FfmpegCanvasMediaProcessor(properties, objectMapper);

    Path fixture = fixture("tiny.png");
    try (InputStream input = Files.newInputStream(fixture)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> processor.process(CanvasResourceKind.IMAGE, input, Files.size(fixture)));
    }
    assertTempDirEmpty();
  }

  /** ffprobe 超时也必须清理已落盘 original 与进程输出文件。 */
  @Test
  void cleansTempFilesAfterProbeTimeout() throws Exception {
    Path slowProbe = writeScript("ffprobe-slow", "sleep 5\n");
    CanvasMediaProperties properties = properties();
    properties.setFfprobeBinary(slowProbe.toString());
    properties.setProcessTimeout(Duration.ofMillis(100));
    FfmpegCanvasMediaProcessor processor = new FfmpegCanvasMediaProcessor(properties, objectMapper);
    Path fixture = fixture("tiny.png");

    try (InputStream input = Files.newInputStream(fixture)) {
      assertThrows(
          IllegalStateException.class,
          () -> processor.process(CanvasResourceKind.IMAGE, input, Files.size(fixture)));
    }
    assertTempDirEmpty();
  }

  /** ffprobe 伪造 NaN fps 时验证 typed JSON 解析后的有限正值校验。 */
  @Test
  void rejectsNonFiniteProbeMetadata() throws Exception {
    Path probe =
        writeScript(
            "ffprobe-nan",
            """
            cat <<'JSON'
            {"streams":[{"codec_type":"video","codec_name":"h264","width":64,"height":48,
            "duration":"1","avg_frame_rate":"NaN"}],
            "format":{"format_name":"mov,mp4","duration":"1","tags":{"major_brand":"isom"}}}
            JSON
            """);
    CanvasMediaProperties properties = properties();
    properties.setFfprobeBinary(probe.toString());
    FfmpegCanvasMediaProcessor processor = new FfmpegCanvasMediaProcessor(properties, objectMapper);
    Path fixture = fixture("tiny.mp4");

    try (InputStream input = Files.newInputStream(fixture)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> processor.process(CanvasResourceKind.VIDEO, input, Files.size(fixture)));
    }
    assertTempDirEmpty();
  }

  /** ffmpeg 成功退出但不产出 preview 仍必须拒绝，覆盖输出缺失 fail closed。 */
  @Test
  void rejectsMissingPreviewOutput() throws Exception {
    Path emptyFfmpeg = writeScript("ffmpeg-empty", "exit 0\n");
    CanvasMediaProperties properties = properties();
    properties.setFfmpegBinary(emptyFfmpeg.toString());
    FfmpegCanvasMediaProcessor processor = new FfmpegCanvasMediaProcessor(properties, objectMapper);
    Path fixture = fixture("tiny.png");

    try (InputStream input = Files.newInputStream(fixture)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> processor.process(CanvasResourceKind.IMAGE, input, Files.size(fixture)));
    }
    assertTempDirEmpty();
  }

  private void assertMedia(
      CanvasResourceKind kind,
      String fixtureName,
      String expectedMediaType,
      boolean previewExpected,
      String metadataField)
      throws Exception {
    CanvasMediaProperties properties = properties();
    FfmpegCanvasMediaProcessor processor = new FfmpegCanvasMediaProcessor(properties, objectMapper);
    Path fixture = fixture(fixtureName);
    Path originalPath;
    try (InputStream input = Files.newInputStream(fixture);
        CanvasProcessedMedia media = processor.process(kind, input, Files.size(fixture))) {
      originalPath = media.originalPath();
      assertEquals(expectedMediaType, media.mediaType());
      assertEquals(Files.size(fixture), media.size());
      JsonNode metadata = objectMapper.readTree(media.metadataJson());
      assertNotNull(metadata.get("container"));
      assertTrue(metadata.get(metadataField).asDouble() > 0D);
      if (kind == CanvasResourceKind.VIDEO) {
        assertEquals("h264", metadata.get("videoCodec").asText());
        assertEquals("aac", metadata.get("audioCodec").asText());
        assertTrue(metadata.get("fps").asDouble() > 0D);
      } else {
        assertFalse(metadata.get("codec").asText().isBlank());
      }
      if (previewExpected) {
        assertTrue(Files.size(media.previewPath()) > 0L);
      } else {
        assertEquals(null, media.previewPath());
      }
    }
    assertFalse(Files.exists(originalPath));
    assertTempDirEmpty();
  }

  private CanvasMediaProperties properties() {
    CanvasMediaProperties properties = new CanvasMediaProperties();
    properties.setTempDir(tempDir);
    properties.setProcessTimeout(Duration.ofSeconds(10));
    return properties;
  }

  private Path fixture(String name) throws Exception {
    Path target = tempDir.resolve("fixture-" + name);
    try (InputStream input =
        getClass().getResourceAsStream("/fun/fengwk/kkstudio/core/studio/resource/" + name)) {
      assertNotNull(input);
      Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  private Path writeScript(String name, String body) throws Exception {
    Path script = tempDir.resolve(name);
    Files.writeString(script, "#!/bin/sh\n" + body);
    assertTrue(script.toFile().setExecutable(true));
    return script;
  }

  private void assertTempDirEmpty() throws Exception {
    try (var paths = Files.list(tempDir)) {
      List<Path> leftovers =
          paths
              .filter(path -> path.getFileName().toString().startsWith("canvas-resource-"))
              .toList();
      assertTrue(leftovers.isEmpty(), "leftover work dirs: " + leftovers);
    }
  }
}
