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

  /** 支持的原子能力列表。 */
  private List<LiveEnvironmentCapabilityDTO> capabilities;

  /** daemon 实际 root display path。 */
  private String rootPath;

  /** CAS 版本。 */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
