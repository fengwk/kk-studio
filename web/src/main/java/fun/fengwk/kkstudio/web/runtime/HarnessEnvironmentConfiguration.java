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
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillStatePackage;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestratorFactory;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillTransport;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Environment 会话组合：Skill 同步执行器与编排器，以及把 dispatcher 唤醒、Skill 同步和 IDE 换代收口到同一个会话监听器。
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

  /** 每个 skill 同步并发槽位可排队的任务数：容量有界，突发只排队不无界建线程。 */
  private static final int SKILL_SYNC_QUEUE_PER_SLOT = 8;

  /**
   * Skill 期望集合对账专用 executor：固定并发 + 有界队列 + AbortPolicy。
   *
   * <p>每个任务只做一次目录读取、预签名与一次控制消息下发，容量受部署级配置约束；容量用尽时显式拒绝，绝不无界建线程。被拒绝的对账不会重放：下一次 READY 或通知重连对账会重新收敛。
   */
  @Bean(name = "environmentSkillSyncExecutor", destroyMethod = "shutdown")
  public ExecutorService environmentSkillSyncExecutor(
      HarnessExecutionAdmissionProperties admissionProperties) {
    int concurrency = admissionProperties.getSkillSync();
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(concurrency * SKILL_SYNC_QUEUE_PER_SLOT),
        Thread.ofVirtual().name("environment-skill-sync-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  /** 技能期望集合的下发端口：直接由 Environment 会话核心实现。 */
  @Bean
  public EnvironmentSkillTransport environmentSkillTransport(EnvironmentDaemonServer daemonServer) {
    return daemonServer::sendSkillSync;
  }

  /**
   * Environment 技能期望集合编排器。
   *
   * <p>它依赖 Environment 会话核心的下发端口，因此只在组合根装配；任何只装配 Platform 的上下文都不会因为缺少该端口而启动失败。
   */
  @Bean
  public EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator(
      EnvironmentSkillSyncOrchestratorFactory orchestratorFactory,
      EnvironmentSkillTransport environmentSkillTransport,
      @Qualifier("environmentSkillSyncExecutor") ExecutorService environmentSkillSyncExecutor) {
    return orchestratorFactory.create(environmentSkillTransport, environmentSkillSyncExecutor);
  }

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
   * 会话事件的组合宿主：READY 唤醒可选的 HarnessWorkDispatcher，触发该 Environment 的 Skill 全量同步，以及远端退出事实对账。
   *
   * <p>每个动作都立刻返回且各自隔离异常：会话状态推进会先完成，任何宿主回调失败都不得向调用方泄露。
   *
   * <p>协作者都用延迟解析：dispatcher、Skill 同步编排器和退出对账器都经由 capability 传输间接依赖本监听器（daemon server → listener →
   * 协作者），启动期直接注入会形成环。
   */
  @Bean
  public EnvironmentSessionListener compositeEnvironmentSessionListener(
      ObjectProvider<HarnessWorkDispatcher> dispatcherProvider,
      ObjectProvider<EnvironmentSkillSyncOrchestrator> orchestratorProvider,
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
          orchestratorProvider.ifAvailable(
              orchestrator -> orchestrator.onEnvironmentReady(environmentId));
        } catch (RuntimeException error) {
          log.warn(
              "environment session callback skillSync.onEnvironmentReady failed for {}: {}",
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

      @Override
      public void onSkillState(
          EnvironmentId environmentId, List<DaemonSkillStatePackage> packages) {
        try {
          orchestratorProvider.ifAvailable(
              orchestrator -> orchestrator.onSkillState(environmentId, packages));
        } catch (RuntimeException error) {
          log.warn(
              "environment session callback skillSync.onSkillState failed for {}: {}",
              environmentId,
              error.getClass().getSimpleName());
        }
      }

      @Override
      public void onDaemonInstanceReplaced(DaemonConnectionIdentity previous) {}
    };
  }
}
