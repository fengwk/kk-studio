package fun.fengwk.kkstudio.project;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.reconcile.IssueReconcileDispatcher;
import fun.fengwk.kkstudio.project.reconcile.IssueReconcileProperties;
import fun.fengwk.kkstudio.work.DurableWorkStore;

/**
 * Project 模块的自动装配入口：把 Project 领域 Bean、领域编解码器与 Spring JDBC 持久化组件注册到宿主应用。
 *
 * <p>Project 是建立在平台之上的应用：经平台的公开服务使用运行时、全局 Blob 与 Agent 目录（窄适配见 {@code
 * fun.fengwk.kkstudio.project.platform}），并实现平台定义的 {@code IssueAgentOwnership}。Issue Reconcile
 * 的执行器与生命周期由 web 组合根装配。
 */
@ComponentScan(basePackageClasses = ProjectAutoConfiguration.class)
@EnableConfigurationProperties(IssueReconcileProperties.class)
@Configuration
public class ProjectAutoConfiguration {

  /** workflow 配置的严格 JSON 编解码器：无状态纯函数，Project 与消费它的宿主组件共享同一份实现，避免复制校验规则。 */
  @Bean
  public ProjectWorkflowJsonCodec projectWorkflowJsonCodec() {
    return new ProjectWorkflowJsonCodec();
  }

  /** Issue 调度邮箱：{@code durable_work} 中 target type 为 ISSUE 的行，Worker 领取后重新检查当前 Issue。 */
  @Bean
  public DurableWorkStore issueWorkQueue(
      JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
    return new DurableWorkStore(
        IssueReconcileDispatcher.TARGET_TYPE, jdbcClient, transactionManager);
  }
}
