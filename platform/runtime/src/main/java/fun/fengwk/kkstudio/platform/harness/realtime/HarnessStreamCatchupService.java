package fun.fengwk.kkstudio.platform.harness.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationStatus;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodePresence;

import java.util.Objects;
import java.util.UUID;

/**
 * 流式追帧的应用服务：先按目标 Thread 的 durable 快照确认 invocation/attempt，再让本地 active owner 立即提交 checkpoint。
 *
 * <p>本节点没有 active owner 时，只有 {@link ClusterNodePresence} 确认存在其它活跃节点才发布一次 {@value #CHANNEL}
 * 通知；单节点部署不产生任何 NOTIFY，也不会轮询等待未来 owner。终态、已接管或与目标 Thread 不匹配的请求都是 best-effort
 * no-op：接口成功只表示请求已被接受，不承诺已有持久化保证。
 *
 * <p>通知只让接收节点对自身 active {@link ModelProcessor} 再次执行 attempt fence，不在节点间广播，也不读取当前 head。 提交后的 {@code
 * MODEL_CHECKPOINT} 仍由 Runtime 既有 checkpoint 路径发布。
 */
@Slf4j
public final class HarnessStreamCatchupService {

  /** 跨节点流式追帧的专用 PostgreSQL notification channel。 */
  public static final String CHANNEL = "harness_stream_catchup";

  static final String NOTIFY_SQL = "select pg_notify(?, ?)";

  private final HarnessRuntime runtime;
  private final ModelProcessor modelProcessor;
  private final ClusterNodePresence clusterNodePresence;
  private final JdbcTemplate jdbcTemplate;
  private final UUID originNodeId;
  private final HarnessStreamCatchupNotificationCodec notificationCodec;

  public HarnessStreamCatchupService(
      HarnessRuntime runtime,
      ModelProcessor modelProcessor,
      ClusterNodePresence clusterNodePresence,
      JdbcTemplate jdbcTemplate,
      UUID originNodeId,
      HarnessStreamCatchupNotificationCodec notificationCodec) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.modelProcessor = Objects.requireNonNull(modelProcessor, "modelProcessor");
    this.clusterNodePresence = Objects.requireNonNull(clusterNodePresence, "clusterNodePresence");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.originNodeId = Objects.requireNonNull(originNodeId, "originNodeId");
    this.notificationCodec = Objects.requireNonNull(notificationCodec, "notificationCodec");
  }

  /**
   * 请求追帧；事前只信任目标 Thread 的 durable 快照，不信任客户端 UUID 或 head 事实。
   *
   * <p>只有 durable 状态仍为 {@code RUNNING} 且 attempt 精确匹配时才请求 checkpoint；终态、过期 attempt 或不匹配关联都是
   * no-op，不创建 Entry / Command / Work，也不推进 Thread version。Thread 不存在时仍由 Runtime typed 异常向上翻译为
   * 404；数据库查询或跨节点发布失败直接向上传播，不伪造成功。
   */
  public void catchUp(UUID threadId, UUID invocationId, int attempt) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(invocationId, "invocationId");
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    GenerationProjection generation = runtime.getThreadSnapshot(threadId).generation();
    if (generation == null
        || !invocationId.equals(generation.generationId())
        || attempt != generation.attempt()
        || generation.status() != GenerationStatus.RUNNING) {
      // 非 RUNNING（含终态）、过期 attempt、其它 Thread 的 invocation 或当前并非 Model 上下文：不触碰任何执行状态。
      return;
    }
    if (modelProcessor.requestCheckpoint(invocationId, attempt)) {
      return;
    }
    if (!clusterNodePresence.hasOtherActiveNodes()) {
      return;
    }
    publishToOtherNodes(threadId, invocationId, attempt);
  }

  /**
   * 专用 LISTEN 连接收到其它节点的追帧提示。
   *
   * <p>只做一次 attempt-fenced 本地 checkpoint 请求：本进程没有 active owner 或 attempt 已切换时不重试、不发布、不回查
   * snapshot。畸形 payload 只记录并丢弃，绝不触发重放。
   */
  public void onNotification(String payload) {
    HarnessStreamCatchupNotificationCodec.Envelope envelope;
    try {
      envelope = notificationCodec.decode(payload);
    } catch (RuntimeException error) {
      // 只记录异常类型：Jackson cause 的消息可能携带原始 JSON，不得写入日志。
      log.warn(
          "cannot decode stream catchup notification; ignoring; errorType={}",
          error.getClass().getName());
      return;
    }
    if (originNodeId.equals(envelope.originNodeId())) {
      return;
    }
    modelProcessor.requestCheckpoint(envelope.invocationId(), envelope.attempt());
  }

  private void publishToOtherNodes(UUID threadId, UUID invocationId, int attempt) {
    String payload =
        notificationCodec.encode(
            new HarnessStreamCatchupNotificationCodec.Envelope(
                originNodeId, threadId, invocationId, attempt));
    PreparedStatementCallback<Void> notify =
        statement -> {
          statement.setString(1, CHANNEL);
          statement.setString(2, payload);
          statement.execute();
          return null;
        };
    jdbcTemplate.execute(NOTIFY_SQL, notify);
  }
}
