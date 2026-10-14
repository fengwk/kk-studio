package fun.fengwk.kkstudio.platform.plugin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Plugin 控制面的部署级配置。
 *
 * <p>主密钥文件是部署 secret 边界：它是 owner-only 的绝对路径，内容恰好 32 bytes 原始 AES-256
 * 密钥，不含编码、绝不进入数据库、SystemSettings、 DTO 或日志，多节点必须挂载同一份内容。刷新节奏属于调度参数，不是 SystemSettings。
 */
@ConfigurationProperties(prefix = "kk-studio.plugins")
@Getter
@Setter
public class PluginProperties {

  /** 单次暂存的默认字节上限：256 MiB。 */
  public static final long DEFAULT_MAX_BYTES = 256L * 1024L * 1024L;

  /** 单次暂存的硬上限：1 GiB。超过它的配置被拒绝，避免把部署变成无界磁盘写入。 */
  public static final long MAX_BYTES_LIMIT = 1024L * 1024L * 1024L;

  /** Plugin 凭据 AES-256-GCM 主密钥的 owner-only 绝对文件；留空表示本部署不提供凭据能力，读写 fail closed。 */
  private String credentialKeyFile;

  /** 凭据刷新扫描与互斥 lease。 */
  private Refresh refresh = new Refresh();

  /** Plugin 资源端口（会话 Resource 下载与远端媒体暂存）的部署边界。 */
  private Resource resource = new Resource();

  /**
   * Plugin 资源端口的部署边界：所有上限都是硬边界，任何越界输入都以确定性失败终结，绝不静默截断或降级。
   *
   * <p>它同时决定「本部署能暂存多大的媒体」与「一次远端下载最多花多久」，因此必须显式配置而不是散落在代码里的常量。
   */
  @Getter
  @Setter
  public static class Resource {

    /** 单次远端连接的建立超时。 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 单次远端下载的响应与整体期限（含 body 读取），超时即失败并删除临时文件。 */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** 单次预签名 PUT 的上传期限；大媒体直传需要比下载更宽的窗口。 */
    private Duration uploadTimeout = Duration.ofMinutes(5);

    /** 单次暂存的字节上限（同时约束远端 Content-Length 与实际写入量）。 */
    private long maxBytes = DEFAULT_MAX_BYTES;

    /** 临时文件目录；留空表示 `java.io.tmpdir`。非空时必须是绝对路径。 */
    private String tempDirectory = "";

    public void setConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = requirePositive(connectTimeout, "resource.connect-timeout");
    }

    public void setRequestTimeout(Duration requestTimeout) {
      this.requestTimeout = requirePositive(requestTimeout, "resource.request-timeout");
    }

    public void setUploadTimeout(Duration uploadTimeout) {
      this.uploadTimeout = requirePositive(uploadTimeout, "resource.upload-timeout");
    }

    public void setMaxBytes(long maxBytes) {
      if (maxBytes <= 0L || maxBytes > MAX_BYTES_LIMIT) {
        throw new IllegalArgumentException(
            "kk-studio.plugins.resource.max-bytes must be within (0, " + MAX_BYTES_LIMIT + "]");
      }
      this.maxBytes = maxBytes;
    }

    public void setTempDirectory(String tempDirectory) {
      String normalized = tempDirectory == null ? "" : tempDirectory.strip();
      if (!normalized.isEmpty() && !Path.of(normalized).isAbsolute()) {
        throw new IllegalArgumentException(
            "kk-studio.plugins.resource.temp-directory must be an absolute path");
      }
      this.tempDirectory = normalized;
    }
  }

  /** 刷新调度参数。 */
  @Getter
  @Setter
  public static class Refresh {

    /** 刷新 dispatcher 的兜底轮询间隔；启动时仍会立即扫描一次。 */
    private Duration pollDelay = Duration.ofHours(1);

    /** 单行刷新在跨节点间的互斥 lease；过期即视为结果未知。 */
    private Duration leaseDuration = Duration.ofMinutes(2);

    public void setPollDelay(Duration pollDelay) {
      this.pollDelay = requirePositive(pollDelay, "refresh.poll-delay");
    }

    public void setLeaseDuration(Duration leaseDuration) {
      this.leaseDuration = requirePositive(leaseDuration, "refresh.lease-duration");
    }
  }

  private static Duration requirePositive(Duration value, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "kk-studio.plugins." + field + " must be a positive duration");
    }
    return value;
  }
}
