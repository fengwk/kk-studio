package fun.fengwk.kkstudio.platform.canvas.resource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Canvas 媒体处理的部署级二进制/临时目录配置。
 *
 * <p>超时、缩略图维度/质量与上传有效期等非敏感运行参数由 SystemSettings.storageMedia 提供。
 */
@ConfigurationProperties(prefix = "kk-studio.canvas.resource")
@Data
public class CanvasMediaProperties {

  /** ffprobe 可执行文件（可含绝对路径），默认取 PATH 中的 {@code ffprobe}。 */
  private String ffprobeBinary = "ffprobe";

  /** ffmpeg 可执行文件（可含绝对路径），默认取 PATH 中的 {@code ffmpeg}。 */
  private String ffmpegBinary = "ffmpeg";

  /** 媒体处理临时目录。 */
  private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
}
