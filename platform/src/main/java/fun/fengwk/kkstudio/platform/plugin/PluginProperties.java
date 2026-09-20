package fun.fengwk.kkstudio.platform.plugin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

  /** Plugin 凭据 AES-256-GCM 主密钥的 owner-only 绝对文件；留空表示本部署不提供凭据能力，读写 fail closed。 */
  private String credentialKeyFile;

  /** 凭据刷新扫描与互斥 lease。 */
  private Refresh refresh = new Refresh();

  /** 刷新调度参数。 */
  @Getter
  @Setter
  public static class Refresh {

    /** 刷新 dispatcher 的兜底轮询间隔；启动时仍会立即扫描一次。 */
    private Duration pollDelay = Duration.ofHours(1);

    /** 单行刷新在跨节点间的互斥 lease；过期即视为结果未知。 */
    private Duration leaseDuration = Duration.ofMinutes(2);

    public void setPollDelay(Duration pollDelay) {
      this.pollDelay = requirePositive(pollDelay, "poll-delay");
    }

    public void setLeaseDuration(Duration leaseDuration) {
      this.leaseDuration = requirePositive(leaseDuration, "lease-duration");
    }

    private static Duration requirePositive(Duration value, String field) {
      if (value == null || value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(
            "kk-studio.plugins.refresh." + field + " must be a positive duration");
      }
      return value;
    }
  }
}
