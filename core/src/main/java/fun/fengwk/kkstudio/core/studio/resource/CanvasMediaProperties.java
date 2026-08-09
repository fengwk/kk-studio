package fun.fengwk.kkstudio.core.studio.resource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Canvas 媒体 probe/preview 与 upload 生命周期配置。 */
@ConfigurationProperties(prefix = "kk-studio.canvas.resource")
@Data
public class CanvasMediaProperties {

  private String ffprobeBinary = "ffprobe";
  private String ffmpegBinary = "ffmpeg";
  private Duration processTimeout = Duration.ofSeconds(30);
  private int thumbnailMaxDimension = 512;
  private int thumbnailQuality = 80;
  private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
  private Duration uploadExpiry = Duration.ofMinutes(15);
}
