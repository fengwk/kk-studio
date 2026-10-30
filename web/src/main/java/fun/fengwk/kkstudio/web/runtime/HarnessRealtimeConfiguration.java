package fun.fengwk.kkstudio.web.runtime;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeBus;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimePayloadBuffer;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodePresence;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterRealtimePayloadClient;
import fun.fengwk.kkstudio.platform.environment.cluster.RealtimeSubscriptionService;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupNotificationCodec;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupService;

import javax.sql.DataSource;

import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Realtime 组合：通知 codec、超限 payload 缓冲、本地优先总线，以及流式追帧 codec 与应用服务。
 *
 * <p>总线同时作为 Runtime 的 sink 与浏览器订阅的 source；REFERENCE 回拉在总线自有的虚拟线程上执行，随总线关闭。追帧服务不创建任何 durable 写入。
 */
@Configuration(proxyBeanMethods = false)
public class HarnessRealtimeConfiguration {

  @Bean
  public RealtimeNotificationCodec realtimeNotificationCodec() {
    return new RealtimeNotificationCodec();
  }

  /** Oversize realtime payload 的 owner 短期内存；sink 写入、内部 controller 按 payloadId 读取，bus close 时释放。 */
  @Bean(destroyMethod = "close")
  public RealtimePayloadBuffer realtimePayloadBuffer() {
    return new RealtimePayloadBuffer();
  }

  /** 本地优先总线同时作为 Runtime 的 sink 与浏览器订阅的 source；REFERENCE 回拉在总线自有的虚拟线程上执行，随总线关闭。 */
  @Bean(destroyMethod = "close")
  public RealtimeBus realtimeBus(
      DataSource dataSource,
      RealtimeNotificationCodec notificationCodec,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      RealtimeSubscriptionService realtimeSubscriptions,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication clusterInternalAuthentication,
      @Qualifier("clusterInternalHttpClient") HttpClient clusterInternalHttpClient,
      RealtimePayloadBuffer realtimePayloadBuffer,
      ContextSnapshotFactory contextSnapshotFactory) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    ClusterRealtimePayloadClient payloadClient =
        new ClusterRealtimePayloadClient(
            clusterInternalHttpClient, clusterInternalAuthentication, clusterNodeRegistry);
    return new RealtimeBus(
        nodeInstanceId,
        notificationCodec,
        new PostgresqlRealtimeEventSource(notificationCodec),
        new PostgresqlRealtimeEventSink(
            jdbcTemplate, notificationCodec, nodeInstanceId, realtimePayloadBuffer),
        realtimeSubscriptions::hasRemoteSubscription,
        payloadClient::fetch,
        realtimePayloadBuffer,
        ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), contextSnapshotFactory));
  }

  /** 流式追帧通知的严格 envelope codec；仅供 realtime 专用 LISTEN 连接与跨节点发布使用。 */
  @Bean
  public HarnessStreamCatchupNotificationCodec harnessStreamCatchupNotificationCodec() {
    return new HarnessStreamCatchupNotificationCodec();
  }

  /**
   * 流式追帧应用服务：本地 active owner 优先，只有存在其它活跃节点时才向专用 channel 发布一次提示。
   *
   * <p>本服务不创建任何 durable 写入；checkpoint 仍由 {@link ModelProcessor} 的 active execution 在提交后发布既有 {@code
   * MODEL_CHECKPOINT}。
   */
  @Bean
  public HarnessStreamCatchupService harnessStreamCatchupService(
      HarnessRuntime runtime,
      ModelProcessor modelProcessor,
      ClusterNodePresence clusterNodePresence,
      DataSource dataSource,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      HarnessStreamCatchupNotificationCodec notificationCodec) {
    return new HarnessStreamCatchupService(
        runtime,
        modelProcessor,
        clusterNodePresence,
        new JdbcTemplate(dataSource),
        nodeInstanceId,
        notificationCodec);
  }
}
