package fun.fengwk.kkstudio.web.upgrade;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.harness.tool.BuiltinHarnessContributorConfiguration;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.OfflinePluginHost;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.project.tool.IssueTransitionService;
import fun.fengwk.kkstudio.platform.project.tool.IssueTransitionTool;
import fun.fengwk.kkstudio.platform.project.tool.ProjectHarnessContributor;
import fun.fengwk.kkstudio.platform.settings.SystemNetworkConfiguration;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.repo.impl.PostgresqlIssueRepository;
import fun.fengwk.kkstudio.project.repo.impl.PostgresqlIssueRunRepository;
import fun.fengwk.kkstudio.project.repo.impl.PostgresqlProjectChangeNotifier;
import fun.fengwk.kkstudio.project.repo.impl.PostgresqlProjectRepository;
import fun.fengwk.kkstudio.project.repo.impl.store.IssueJdbcStore;
import fun.fengwk.kkstudio.project.repo.impl.store.IssueRunJdbcStore;
import fun.fengwk.kkstudio.project.repo.impl.store.ProjectJdbcStore;
import fun.fengwk.kkstudio.web.StrictJacksonConfiguration;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 一次性离线升级工具专用的宿主装配：在不启动 Web、不启动 worker、不读凭据的前提下，把当前发行物真正会安装的 Plugin 与真正内置的两域贡献组装成一次 epoch 1 初始目录候选。
 *
 * <p>它只持有一个明确关闭的小型 {@link AnnotationConfigApplicationContext}，其中包含：
 *
 * <ul>
 *   <li>当前 classpath 上同一份 Boot {@code AutoConfiguration.imports} 声明的全部第一方 Plugin auto-configuration
 *       （只按命名空间筛选，绝不手写 pluginId→class 列表，也不引入插件编译期依赖）；
 *   <li>真实的 {@link SystemNetworkConfiguration}，因此装配期安装的就是线上同一个全局 HTTP 代理 selector，并且随上下文关闭恢复；
 *   <li>真实的 {@link BuiltinHarnessContributorConfiguration} 纯构造工厂（{@code read}/{@code task} 与内置
 *       Contributor）与真实的 Project 交接工具（三个真实 JDBC 仓储 + {@link ProjectWorkflowJsonCodec}，只构造不执行）。
 * </ul>
 *
 * <p>宿主侧能力全部由 Platform 的 {@link OfflinePluginHost} 明确拒绝：read/subagent、凭据、资源网关与两个 Prompt
 * 端口都只抛固定安全异常。当前第一方 Plugin 的 {@code configure} 只做静态构造，不探测网络、不解析凭据，因此装配与构造候选的整个过程不打开数据库连接、不发出
 * HTTP，也绝不用空 Optional 掩盖启用中的能力（否则会伪造出与线上不同的指纹）。
 *
 * <p>这不是 sandbox：Plugin 自己仍可创建客户端，本组件只保证宿主端口不被执行；因此「零 HTTP」是由宿主端口拒绝与当前第一方 configure
 * 的静态行为共同保证的事实，而不是对插件代码的强制隔离。
 *
 * <p>候选快照的所有权始终在调用方：{@link #prepareInitial} 只构造并返回现有 {@link
 * PluginCatalogBootstrap.PreparedInitial}，既不写库也不登记第二份生命周期；{@link #close()} 只释放装配自己创建的上下文资源。
 */
@Slf4j
final class OfflinePluginCatalog implements AutoCloseable {

  /** 当前发行物 Plugin 安装定义的唯一命名空间：只加载这一前缀下的 auto-configuration。 */
  private static final String PLUGIN_PACKAGE_PREFIX = "fun.fengwk.kkstudio.plugin.";

  private final AnnotationConfigApplicationContext context;

  private final StudioPluginRegistry registry;

  private final List<HarnessContributor> builtins;

  private final List<CanvasFunctionAdapter> builtinAdapters;

  private final BiFunction<String, PluginCredentialReference, PluginContext> contexts;

  private final PluginCatalogBootstrap bootstrap;

  private OfflinePluginCatalog(
      AnnotationConfigApplicationContext context,
      StudioPluginRegistry registry,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters,
      BiFunction<String, PluginCredentialReference, PluginContext> contexts,
      PluginCatalogBootstrap bootstrap) {
    this.context = context;
    this.registry = registry;
    this.builtins = builtins;
    this.builtinAdapters = builtinAdapters;
    this.contexts = contexts;
    this.bootstrap = bootstrap;
  }

  /**
   * 打开一次离线装配并 refresh 上下文。
   *
   * @param dataSource 目标库句柄：只用于构造真实事务管理器与 Project 仓储，装配与构造候选期间不打开连接
   * @param settings 已经物化的真实 system settings，注入唯一的 live 快照；网络段决定装配期安装的全局代理
   */
  static OfflinePluginCatalog open(DataSource dataSource, SystemSettings settings) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(settings, "settings");
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    try {
      OfflinePluginHost host = new OfflinePluginHost();
      JdbcClient jdbc = JdbcClient.create(dataSource);
      PluginConfigurationRepository repository =
          new PostgresqlPluginConfigurationRepository(
              new PluginConfigurationJdbcStore(jdbc),
              new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
      PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);

      // 离线宿主与真实 live 快照必须先成为 bean definition：内置装配用 @ConditionalOnMissingBean 决定是否
      // 换成真实执行端口，条件只认已经登记的 definition。离线宿主是一个实现全部六个端口的唯一 bean，
      // 因此按任一端口类型解析都只有它一个候选。
      context.registerBean(
          "offlineSystemSettingsSnapshot",
          SystemSettingsSnapshot.class,
          () -> new SystemSettingsSnapshot(settings));
      context.registerBean("offlineHost", OfflinePluginHost.class, () -> host);
      context.registerBean(
          "offlineProjectHarnessContributor",
          HarnessContributor.class,
          () -> projectContributor(jdbc));

      // Jackson 也复用真实配置：Boot4 的 JacksonAutoConfiguration 加生产 StrictJacksonConfiguration 一起产出线上同一个严格
      // mapper，绝不在这里复制 customizer 算法或注册自造 mapper。
      context.register(
          JacksonAutoConfiguration.class,
          StrictJacksonConfiguration.class,
          SystemNetworkConfiguration.class,
          BuiltinHarnessContributorConfiguration.class);
      context.register(pluginAutoConfigurations().toArray(Class<?>[]::new));
      context.refresh();

      StudioPluginRegistry registry =
          new StudioPluginRegistry(
              List.copyOf(context.getBeansOfType(StudioPlugin.class).values()));
      List<HarnessContributor> builtins =
          List.copyOf(context.getBeansOfType(HarnessContributor.class).values());
      List<CanvasFunctionAdapter> builtinAdapters =
          List.copyOf(context.getBeansOfType(CanvasFunctionAdapter.class).values());
      PluginContextFactory contextFactory =
          new PluginContextFactory(
              context.getBean(PluginCredentialStore.class),
              () -> context.getBeanProvider(PluginResourceGateway.class).getIfAvailable(),
              () -> context.getBeanProvider(PluginPromptTasks.class).getIfAvailable(),
              () -> context.getBeanProvider(PluginPromptResources.class).getIfAvailable());
      return new OfflinePluginCatalog(
          context,
          registry,
          builtins,
          builtinAdapters,
          contextFactory::create,
          new PluginCatalogBootstrap(registry, repository, transactionManager));
    } catch (RuntimeException | Error error) {
      closeWithoutMasking(context, error);
      throw error;
    }
  }

  /** 当前 classpath 上真正的安装目录：只收集容器中的 {@link StudioPlugin}，不扫描目录也不加载外部 classloader。 */
  StudioPluginRegistry registry() {
    return registry;
  }

  /** 容器真正内置的两域 Harness 贡献（内置工具 Contributor 与 Project 交接 Contributor）。 */
  List<HarnessContributor> builtins() {
    return builtins;
  }

  /** 与启动构建同形状的 scoped {@link PluginContext} 装配函数：离线端口在三处 Optional 中都是 Present。 */
  BiFunction<String, PluginCredentialReference, PluginContext> contexts() {
    return contexts;
  }

  /**
   * 在任意目标写事务之外纯构造一次 epoch 1 初始候选，绝不读权威 catalog、绝不写库、绝不解析凭据。
   *
   * <p>两个输入都必须由调用方显式给出并精确覆盖当前安装目录：普通值不在这里做默认值兜底，秘密字段名只接受字段名而非秘密值。
   *
   * @param valuesJsonByPluginId 每个已安装 id 的初始普通值 JSON，key 集必须精确等于安装集合
   * @param configuredSecretFieldsByPluginId 每个已安装 id 的「已录入」秘密字段名，key 集必须精确等于安装集合
   * @return 现场构造的候选；{@link PluginCatalogBootstrap.PreparedInitial#snapshot()} 的所有权交给调用方
   * @throws IllegalStateException 上下文已关闭、安装集合不符、普通值或秘密字段名非法时
   */
  PluginCatalogBootstrap.PreparedInitial prepareInitial(
      Map<String, String> valuesJsonByPluginId,
      Map<String, List<String>> configuredSecretFieldsByPluginId) {
    if (!context.isActive()) {
      throw new IllegalStateException("offline plugin catalog context is closed");
    }
    return bootstrap.prepareInitial(
        valuesJsonByPluginId,
        configuredSecretFieldsByPluginId,
        contexts,
        builtins,
        builtinAdapters);
  }

  /**
   * 用同一份真实装配按已初始化的权威 record 严格恢复目录：内部调用自身 bootstrap 的公开 restore 并传入实际 contexts / builtins /
   * builtinAdapters，绝不外部猜空 adapters 或新增 registry。
   *
   * @param catalog 事务内真实仓储回读出的已初始化 catalog record（epoch 必须 &gt; 0）
   * @return 现场恢复的快照；所有权交给调用方关闭
   * @throws IllegalStateException 上下文已关闭或 record 与真实装配不一致时
   */
  PluginCatalogSnapshot restore(PluginCatalogRecord catalog) {
    if (!context.isActive()) {
      throw new IllegalStateException("offline plugin catalog context is closed");
    }
    return bootstrap.restore(catalog, contexts, builtins, builtinAdapters);
  }

  /** 只关闭装配自己创建的上下文资源（含全局代理 selector 恢复）；候选快照始终由调用方关闭。 */
  @Override
  public void close() {
    context.close();
  }

  /** 从 classpath 上同一份 Boot {@code AutoConfiguration.imports} 读取全部第一方 Plugin 装配入口。 */
  private static List<Class<?>> pluginAutoConfigurations() {
    ClassLoader classLoader = OfflinePluginCatalog.class.getClassLoader();
    List<Class<?>> configurations = new ArrayList<>();
    for (String candidate :
        ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates()) {
      if (candidate.startsWith(PLUGIN_PACKAGE_PREFIX)) {
        configurations.add(loadPluginConfiguration(candidate, classLoader));
      }
    }
    return configurations;
  }

  private static Class<?> loadPluginConfiguration(String className, ClassLoader classLoader) {
    try {
      return Class.forName(className, true, classLoader);
    } catch (ClassNotFoundException error) {
      throw new IllegalStateException(
          "plugin auto-configuration declared by the classpath is missing: " + className);
    }
  }

  /** 只用真实 Project 领域类冻结交接工具：三个仓储都只被构造，完全不执行也不连接数据库。 */
  private static ProjectHarnessContributor projectContributor(JdbcClient jdbc) {
    PostgresqlProjectChangeNotifier notifier = new PostgresqlProjectChangeNotifier(jdbc);
    ProjectRepository projects =
        new PostgresqlProjectRepository(new ProjectJdbcStore(jdbc), notifier);
    IssueRepository issues = new PostgresqlIssueRepository(new IssueJdbcStore(jdbc), notifier);
    IssueRunRepository runs =
        new PostgresqlIssueRunRepository(new IssueRunJdbcStore(jdbc), notifier);
    IssueTransitionService service =
        new IssueTransitionService(projects, issues, runs, new ProjectWorkflowJsonCodec());
    return new ProjectHarnessContributor(new IssueTransitionTool(service));
  }

  /** 部分构造失败时收回已创建资源且绝不掩盖 primary：回收期 Error 只在 primary 不是 Error 时取代它。 */
  private static void closeWithoutMasking(
      AnnotationConfigApplicationContext context, Throwable primary) {
    try {
      context.close();
    } catch (RuntimeException cleanup) {
      log.warn("offline plugin catalog cleanup failed: {}", cleanup.getClass().getName());
    } catch (Error cleanup) {
      if (!(primary instanceof Error)) {
        throw cleanup;
      }
    }
  }
}
