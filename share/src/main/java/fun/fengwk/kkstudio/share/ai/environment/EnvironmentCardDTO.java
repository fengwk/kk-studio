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

  /** 仅在 create 响应与 rotate-token 响应中返回刚生成的新值；正常列表和详情查询始终为 null， 按需读取当前值走只读 token 端点。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String registrationToken;

  /** 当前连接状态：CONNECTING / READY / OFFLINE。 */
  private String status;

  /** 是否就绪。 */
  private boolean ready;

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

  /** 支持的原子能力列表。 */
  private List<LiveEnvironmentCapabilityDTO> capabilities;

  /** 最近一条 WARN/ERROR 运维事件；没有任何此类事件时为 null。完整 200 条走 events 端点。 */
  private EnvironmentEventDTO lastEvent;

  /** CAS 版本。 */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
