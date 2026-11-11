package fun.fengwk.kkstudio.web.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.RemoteExitReconciler;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.ide.IdeLeaseCoordinator;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Environment 会话组合：把 dispatcher 唤醒与 IDE 换代收口到同一个会话监听器。
 *
 * <p>依赖 {@code EnvironmentCapabilityTransport} 的组件只在这里装配：Platform 的自动配置不参与 Harness 组合，任何只装配
 * Platform 的上下文都不会因为缺少该传输而启动失败。
 *
 * <p>会话监听器的每个动作都立刻返回且各自隔离异常：会话状态推进会先完成，任何宿主回调失败都不得影响会话本身。协作者都用延迟解析，避免经 capability
 * 传输间接依赖本监听器而在启动期成环。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class HarnessEnvironmentConfiguration {

  /** 跨实例远端退出对账专用 virtual thread executor。 */
  @Bean(name = "remoteExitReconcileExecutor", destroyMethod = "shutdown")
  public ExecutorService remoteExitReconcileExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("remote-exit-reconcile-", 0L).factory());
  }

  /** 跨实例远端退出对账器。 */
  @Bean
  public RemoteExitReconciler remoteExitReconciler(
      HarnessStore store,
      EnvironmentCapabilityTransport capabilityTransport,
      ExecutionExitConfirmer executionExitConfirmer,
      @Qualifier("remoteExitReconcileExecutor") ExecutorService remoteExitReconcileExecutor) {
    return new RemoteExitReconciler(
        store, capabilityTransport, executionExitConfirmer, remoteExitReconcileExecutor);
  }

  /**
   * 会话事件的组合宿主：READY 唤醒可选的 HarnessWorkDispatcher，并触发远端退出事实对账。
   *
   * <p>每个动作都立刻返回且各自隔离异常：会话状态推进会先完成，任何宿主回调失败都不得向调用方泄露。
   *
   * <p>协作者都用延迟解析，避免经 capability 传输间接依赖本监听器而在启动期成环。
   */
  @Bean
  public EnvironmentSessionListener compositeEnvironmentSessionListener(
      ObjectProvider<HarnessWorkDispatcher> dispatcherProvider,
      ObjectProvider<IdeLeaseCoordinator> ideLeaseCoordinatorProvider,
      ObjectProvider<RemoteExitReconciler> remoteExitReconcilerProvider) {
    return new EnvironmentSessionListener() {
      @Override
      public void onEnvironmentReady(EnvironmentId environmentId) {
        try {
          dispatcherProvider.ifAvailable(HarnessWorkDispatcher::wake);
        } catch (RuntimeException error) {
          log.warn(
              "environment session callback dispatcher.wake failed for {}: {}",
              environmentId,
              error.getClass().getSimpleName());
        }
        try {
          remoteExitReconcilerProvider.ifAvailable(
              reconciler -> reconciler.onEnvironmentReady(environmentId));
        } catch (RuntimeException error) {
          log.warn(
              "environment session callback remoteExitReconcile.onEnvironmentReady failed for {}: {}",
              environmentId,
              error.getClass().getSimpleName());
        }
      }

      @Override
      public void onHeartbeat(
          EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {
        // 心跳本身只负责投递轮次；使用证明必须由协调器回查事件节点后才可续租。
        try {
          ideLeaseCoordinatorProvider.ifAvailable(
              coordinator -> coordinator.onHeartbeat(environmentId, identity, ideLeaseRound));
        } catch (RuntimeException error) {
          log.warn(
              "environment session callback ideLease.onHeartbeat failed for {}: {}",
              environmentId,
              error.getClass().getSimpleName());
        }
      }
    };
  }
}
