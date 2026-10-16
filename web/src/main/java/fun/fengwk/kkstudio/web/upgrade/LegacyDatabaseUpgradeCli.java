package fun.fengwk.kkstudio.web.upgrade;

import org.postgresql.Driver;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.logging.LogManager;

/**
 * 受保护离线升级入口：{@code prepare|dry-run|apply --config /absolute/path.json}（或 {@code
 * --help}）以独占一次性进程运行，成功只输出固定事实，失败只输出固定阶段与安全建议。
 *
 * <p>由 Web Fat JAR 的 {@code WebApplication} 在首参 {@code database-upgrade} 时调用，不启动 Spring
 * Web/Worker。进程在读 secret 前禁用 SLF4J 全部现存 logger、根日志与 JUL（即使 {@code
 * org.springframework.boot.logging.LoggingSystem=none} 也按真实 classpath 后端关闭）；不替换全局 {@code
 * System.out}/{@code System.err}，并保证任何 {@code Throwable}（含 {@code Error}）都收敛为非零退出码而不是 JVM 默认打印。
 *
 * <p>dry-run/apply 只调用现有 {@link LegacyDatabaseUpgrade#convert}，不隐式 prepare、不覆盖/增量/重试。
 */
public final class LegacyDatabaseUpgradeCli {

  static final int EXIT_OK = 0;

  static final int EXIT_FAILURE = 1;

  static final int EXIT_USAGE = 2;

  private static final String HELP = "--help";

  private static final String CONFIG = "--config";

  private static final String MODE_PREPARE = "prepare";

  private static final String MODE_DRY_RUN = "dry-run";

  private static final String MODE_APPLY = "apply";

  private LegacyDatabaseUpgradeCli() {}

  /** 命令行模式；wire 名与运维使用的命令一致。 */
  enum Mode {
    PREPARE(MODE_PREPARE),
    DRY_RUN(MODE_DRY_RUN),
    APPLY(MODE_APPLY);

    private final String wire;

    Mode(String wire) {
      this.wire = wire;
    }

    String wire() {
      return wire;
    }
  }

  /**
   * 执行一次离线升级命令。
   *
   * @param args 首参之后的参数（不含 {@code database-upgrade}）
   * @param out 结果输出流；绝不关闭
   * @param err 错误输出流；绝不关闭
   * @return 进程退出码：{@link #EXIT_OK} / {@link #EXIT_FAILURE} / {@link #EXIT_USAGE}
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      disableDiagnosticLogging();
      if (args.length == 1 && HELP.equals(args[0])) {
        out.print(usage());
        out.flush();
        return EXIT_OK;
      }
      Mode mode = parseMode(args);
      Path configFile = parseConfigPath(args);
      LegacyUpgradeConfig config = LegacyUpgradeConfig.read(mode == Mode.PREPARE, configFile);
      if (mode == Mode.PREPARE) {
        LegacyDatabasePrepare.prepare(dataSource(config.target()));
        printPrepared(out);
      } else {
        LegacyDatabaseUpgradeResult result =
            LegacyDatabaseUpgrade.convert(
                dataSource(config.source()),
                dataSource(config.target()),
                config.sourceKey(),
                config.targetKey(),
                config.staticSecrets(),
                mode == Mode.DRY_RUN,
                Clock.systemUTC());
        printResult(out, mode, result);
      }
      out.flush();
      return EXIT_OK;
    } catch (LegacyUpgradeCliException error) {
      report(
          err,
          error.stage().name(),
          typeOf(error.stage()),
          error.getMessage(),
          error.outcomeUncertain());
      return exitCodeOf(error.stage());
    } catch (LegacyDatabaseUpgradeException error) {
      report(
          err,
          error.stage().name(),
          "LegacyDatabaseUpgradeException",
          error.getMessage(),
          error.outcomeUncertain());
      return EXIT_FAILURE;
    } catch (Throwable error) {
      // 进程最外层：未知失败按可能有写入保守处理，绝不回显任何 Throwable/cause 或让 JVM 默认打印。
      report(err, "UNEXPECTED", "UnexpectedFailure", "an unexpected failure occurred", true);
      return EXIT_FAILURE;
    }
  }

  /**
   * 在读取任何 secret 前禁用进程的诊断日志：从 {@code spring.factories} 直接选真实 classpath 后端（不经过 {@code
   * LoggingSystem=none} 的 no-op 分支），关闭该后端当前已知的全部命名 logger 与根日志，再 reset JUL。
   *
   * <p>覆盖全部现存 logger 是必需的：只关根日志时，任何被显式配置为 DEBUG 的子 logger 仍会输出。选不到后端或任一步失败都 fail closed。
   */
  private static void disableDiagnosticLogging() {
    try {
      LoggingSystem loggingSystem =
          LoggingSystemFactory.fromSpringFactories()
              .getLoggingSystem(LegacyDatabaseUpgradeCli.class.getClassLoader());
      if (loggingSystem == null) {
        throw new IllegalStateException("no logging system is available to disable");
      }
      for (LoggerConfiguration configuration : loggingSystem.getLoggerConfigurations()) {
        loggingSystem.setLogLevel(configuration.getName(), LogLevel.OFF);
      }
      loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.OFF);
      LogManager.getLogManager().reset();
    } catch (RuntimeException error) {
      throw new LegacyUpgradeCliException(
          LegacyUpgradeCliException.Stage.ENVIRONMENT,
          "diagnostic logging could not be disabled before reading secrets");
    }
  }

  private static Mode parseMode(String[] args) {
    if (args.length == 0) {
      throw usage("a command is required");
    }
    String command = args[0];
    if (MODE_PREPARE.equals(command)) {
      return Mode.PREPARE;
    }
    if (MODE_DRY_RUN.equals(command)) {
      return Mode.DRY_RUN;
    }
    if (MODE_APPLY.equals(command)) {
      return Mode.APPLY;
    }
    throw usage("the command must be prepare, dry-run, apply or --help");
  }

  private static Path parseConfigPath(String[] args) {
    if (args.length != 3) {
      throw usage("the command requires exactly --config and an absolute configuration path");
    }
    if (!CONFIG.equals(args[1])) {
      throw usage("the command requires the --config option");
    }
    Path path;
    try {
      path = Path.of(args[2]);
    } catch (InvalidPathException error) {
      throw usage("the configuration path is not a valid absolute path");
    }
    if (!path.isAbsolute()) {
      throw usage("the configuration path must be absolute");
    }
    return path;
  }

  private static DataSource dataSource(LegacyUpgradeConfig.Endpoint endpoint) {
    return new SimpleDriverDataSource(
        new Driver(), endpoint.url(), endpoint.username(), endpoint.password());
  }

  private static void printPrepared(PrintStream out) {
    out.println("mode=" + MODE_PREPARE);
    out.println("applied=true");
  }

  private static void printResult(PrintStream out, Mode mode, LegacyDatabaseUpgradeResult result) {
    out.println("mode=" + mode.wire());
    out.println("profile=" + result.sourceProfile());
    out.println("applied=" + result.applied());
    for (Map.Entry<String, Long> row : result.rowCounts().entrySet()) {
      out.println("table." + row.getKey() + "=" + row.getValue());
    }
    out.println("retiredClusterNodeRows=" + result.retiredClusterNodeRows());
    out.println("rewrittenCredentials=" + result.rewrittenCredentials());
    out.println("installedStaticCredentials=" + result.installedStaticCredentials());
    out.println("rewrittenSkillTokens=" + result.rewrittenSkillTokens());
  }

  private static void report(
      PrintStream err, String stage, String type, String reason, boolean outcomeUncertain) {
    err.println("error.stage=" + stage);
    err.println("error.type=" + type);
    err.println("error.reason=" + reason);
    err.println("outcomeUncertain=" + outcomeUncertain);
    err.println("recommendation=" + recommendation(stage, outcomeUncertain));
    err.flush();
  }

  private static String typeOf(LegacyUpgradeCliException.Stage stage) {
    return switch (stage) {
      case USAGE -> "UsageError";
      case INPUT -> "InputError";
      case ENVIRONMENT -> "EnvironmentError";
      case PREPARE -> "PrepareError";
    };
  }

  private static int exitCodeOf(LegacyUpgradeCliException.Stage stage) {
    return switch (stage) {
      case USAGE, INPUT -> EXIT_USAGE;
      case ENVIRONMENT, PREPARE -> EXIT_FAILURE;
    };
  }

  private static String recommendation(String stage, boolean outcomeUncertain) {
    if (outcomeUncertain) {
      return "do not retry blindly; verify the target database state manually";
    }
    if ("USAGE".equals(stage) || "INPUT".equals(stage)) {
      return "fix the command line or the protected input file, then run the command again";
    }
    if ("ENVIRONMENT".equals(stage)) {
      return "no secret was read; make the process environment able to disable diagnostic logging";
    }
    return "no target change was confirmed; fix the reported stage and run the command again";
  }

  private static String usage() {
    return """
        Usage: java -jar kk-studio-web.jar database-upgrade <command> --config /absolute/path.json

        Commands:
          prepare   Initialize a freshly created, empty PostgreSQL 17 database with the current baseline
          dry-run   Convert the legacy source into the target and roll back
          apply     Convert the legacy source into the target and commit
          --help    Print this usage information

        Rules:
          - The configuration file and every key file must be absolute, owner-only regular files.
          - prepare takes a target-only configuration; dry-run and apply take source and target.
          - Passwords, keys and static secrets are only ever read from the protected files above.
        """;
  }

  private static LegacyUpgradeCliException usage(String reason) {
    return new LegacyUpgradeCliException(LegacyUpgradeCliException.Stage.USAGE, reason);
  }
}
