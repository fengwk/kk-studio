package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/** Chat 集合的公开读表示。 */
@Data
public class ChatDTO {

  /** Chat 业务主键：正十进制字符串（底层 bigint，由数据库序列分配）。 */
  private String id;

  /** 聊天标题：非空白且 ≤256 字符。 */
  private String title;

  /** 必填可见 Agent 身份；Agent 删除后该值可能过期。 */
  private String agentName;

  /**
   * 可空的默认分支 Environment 逻辑路由名称（canonical bounded 小写名称）；null 表示无默认环境（{@code @JsonInclude(ALWAYS)} 保证
   * null 显式序列化，前端/契约可区分缺省与显式 null）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  /** 可见的发送权限模式。 */
  private boolean yoloEnabled;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant，单调不减）。 */
  private Instant updateTime;
}
