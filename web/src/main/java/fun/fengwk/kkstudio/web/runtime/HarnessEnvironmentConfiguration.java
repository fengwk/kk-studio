package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.ide.IdeLeaseCoordinator;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestratorFactory;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.web.environment.ide.IdeGenerationCloseListener;

import java.util.concurrent.ExecutorService;
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
@Configuration(proxyBeanMethods = false)
public class HarnessEnvironmentConfiguration {

  /** 每个 skill 同步并发槽位可排队的任务数：容量有界，突发只排队不无界建线程。 */
  private static final int SKILL_SYNC_QUEUE_PER_SLOT = 8;

  /**
   * Skill 同步专用 executor：固定并发 + 有界队列 + AbortPolicy。
   *
   * <p>每个任务在等待 capability 终态时阻塞至多一个 Package 的调用超时，因此并发必须受部署级容量约束；容量用尽时显式拒绝， 绝不无界建线程。被拒绝的同步不会重放：下一次
   * READY 或通知重连对账会重新收敛。
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

  /**
   * Environment Skill Package 同步编排器。
   *
   * <p>它依赖 {@code EnvironmentCapabilityTransport}（即 Environment 会话核心），因此只在组合根装配：Platform 的 自动配置不参与
   * Harness 组合，任何只装配 Platform 的上下文都不会因为缺少该传输而启动失败。
   */
  @Bean
  public EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator(
      EnvironmentSkillSyncOrchestratorFactory orchestratorFactory,
      EnvironmentCapabilityTransport capabilityTransport,
      @Qualifier("environmentSkillSyncExecutor") ExecutorService environmentSkillSyncExecutor) {
    return orchestratorFactory.create(capabilityTransport, environmentSkillSyncExecutor);
  }

  /**
   * 会话事件的组合宿主：READY 唤醒可选的 HarnessWorkDispatcher，并触发该 Environment 的 Skill 全量同步； Daemon 实例接管时关闭旧代际的
   * IDE 数据面。
   *
   * <p>每个动作都立刻返回且各自隔离异常：会话状态推进会先完成，任何宿主回调失败都不得向调用方泄露。
   *
   * <p>协作者都用延迟解析：dispatcher、Skill 同步编排器与 IDE 换代监听器都经由 capability 传输间接依赖本监听器（daemon server →
   * listener → 协作者），启动期直接注入会形成环。
   */
  @Bean
  public EnvironmentSessionListener compositeEnvironmentSessionListener(
      ObjectProvider<HarnessWorkDispatcher> dispatcherProvider,
      ObjectProvider<EnvironmentSkillSyncOrchestrator> orchestratorProvider,
      ObjectProvider<IdeGenerationCloseListener> ideGenerationCloseProvider,
      ObjectProvider<IdeLeaseCoordinator> ideLeaseCoordinatorProvider) {
    return new EnvironmentSessionListener() {
      @Override
      public void onEnvironmentReady(EnvironmentId environmentId) {
        try {
          dispatcherProvider.ifAvailable(HarnessWorkDispatcher::wake);
        } catch (RuntimeException ignored) {
        }
        try {
          orchestratorProvider.ifAvailable(
              orchestrator -> orchestrator.onEnvironmentReady(environmentId));
        } catch (RuntimeException ignored) {
        }
      }

      @Override
      public void onHeartbeat(
          EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {
        // 心跳本身只负责投递轮次；使用证明必须由协调器回查事件节点后才可续租。
        try {
          ideLeaseCoordinatorProvider.ifAvailable(
              coordinator -> coordinator.onHeartbeat(environmentId, identity, ideLeaseRound));
        } catch (RuntimeException ignored) {
        }
      }

      @Override
      public void onDaemonInstanceReplaced(DaemonConnectionIdentity previous) {
        // 换进程接管：关闭旧代际的 IDE 反向流并撤销入口凭据，而不是只撤销票据。
        try {
          ideGenerationCloseProvider.ifAvailable(listener -> listener.onGenerationClosed(previous));
        } catch (RuntimeException ignored) {
        }
      }
    };
  }
}
