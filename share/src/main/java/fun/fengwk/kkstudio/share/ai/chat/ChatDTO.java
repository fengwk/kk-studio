package fun.fengwk.kkstudio.share.ai.chat;

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

  /** 可见的发送权限模式。 */
  private boolean yoloEnabled;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant，单调不减）。 */
  private Instant updateTime;
}
