package fun.fengwk.kkstudio.share.ai.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 待处理 Interaction 的产品来源与其展示用名称。
 *
 * <p>{@code type} 为 {@code CHAT} 时 {@code chatId} 与 {@code chatTitle} 非空；为 {@code ISSUE_AGENT} 时
 * {@code issueId}、{@code issueTitle} 与 {@code agentName} 非空；为 {@code
 * AGENT_TASK}（控制器或插件发起、没有产品页面的独立任务）时 {@code agentName} 非空。{@code rootThreadName} 是该来源真实执行根的 Thread
 * 名称，各类型都可能有，用于区分不同执行树/委派。
 *
 * <p>名称一律取自归属事实本身（Chat 标题、Issue 标题、Thread 名称、Agent 名），由服务端按请求缓存补齐，不逐条回读、不由客户端猜测；空字段显式序列化为
 * null，避免客户端猜字段存在性。
 */
@Data
public class InteractionOwnerDTO {

  private String type;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String chatId;

  /** Chat 标题；非 Chat 来源为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String chatTitle;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String issueId;

  /** Issue 标题；非 Issue+Agent 来源为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String issueTitle;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String agentName;

  /** 真实执行根的 Thread 名称；根无名称时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String rootThreadName;
}
