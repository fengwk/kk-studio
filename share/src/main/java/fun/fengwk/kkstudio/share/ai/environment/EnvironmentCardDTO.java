package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Environment Card DTO（包含稳定 Card 属性与当前 live 连接投影）。 */
@Data
public class EnvironmentCardDTO {
  private String id;
  private String name;
  private EnvironmentInstallConfigDTO installConfig;

  /** 仅在 create 响应与 rotate-token 响应中返回刚生成的新值；正常列表和详情查询始终为 null， 按需读取当前值走只读 token 端点。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String registrationToken;

  /** 当前连接状态：CONNECTING / READY / OFFLINE（租约或心跳窗口已过期的连接派生为 OFFLINE）。 */
  private String status;

  /** 是否就绪。 */
  private boolean ready;

  /**
   * 当前状态仍然成立的截止时间：有效连接取 {@code min(leaseUntil, lastSeen + heartbeatTimeout)}；无连接或已失效时为 null。
   *
   * <p>它是同一连接行事实的只读时间投影（不是被持久化的过期状态）：服务端读取时用同一时钟派生，浏览器只按它安排一次回读。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Instant statusExpiresAt;

  /** 最近活跃时间。 */
  private Instant lastSeen;

  /** 最近一次被接受的 READY 宿主 OS wire 值：windows/wsl/linux/macos；从未 READY 时为 null。 */
  private String operatingSystem;

  /** 最近一次被接受的 READY 宿主 IANA 时区 ID；从未 READY 时为 null。 */
  private String timeZone;

  /** 最近一次被接受的 READY 宿主 Daemon 进程用户；从未 READY 时为 null。仅用于展示，不是 cwd 或默认 workdir。 */
  private String userName;

  /** 最近一次被接受的 READY 宿主进程用户 canonical HOME；从未 READY 时为 null。仅用于展示，不是默认 workdir。 */
  private String homeDirectory;

  /** 最近一次被接受的 READY 宿主备注；从未 READY 时为 null。 */
  private String note;

  /**
   * 最近一次被接受的 READY 上报的实际 Daemon 构建版本（JAR manifest {@code Implementation-Version}，未打包时为 {@code
   * development}）；从未 READY 时为 null。它与下方 CAS {@code version} 是两个不同事实：前者是运行中的二进制版本，后者是配置行的乐观锁版本。
   */
  private String daemonVersion;

  /**
   * 该 Environment 最近一次受管 Daemon 更新操作（含终态）；从未发起更新时为 null。
   *
   * <p>它是持久事实投影：卡片只在有更新历史时携带，避免为每个 Environment 引入额外端点轮询。
   */
  private EnvironmentUpdateDTO update;

  /** 支持的原子能力列表。 */
  private List<LiveEnvironmentCapabilityDTO> capabilities;

  /** CAS 版本。 */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
