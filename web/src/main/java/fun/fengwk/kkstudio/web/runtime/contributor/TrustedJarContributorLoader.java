package fun.fengwk.kkstudio.web.runtime.contributor;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动期加载本地受信任贡献者 JAR。
 *
 * <p>贡献者 JAR 只通过显式目录加入 child {@link URLClassLoader}，并以宿主的 {@link HarnessContributor} classloader 作为
 * parent。默认的 URLClassLoader 委派顺序是 parent-first；本类不修改线程 context classloader，也不注册贡献者类型为 Spring bean。
 *
 * <p>实例构造完成后贡献者列表即冻结，不提供刷新或运行时安装入口；{@link #close()} 关闭 child classloader。
 */
public final class TrustedJarContributorLoader implements AutoCloseable {

  public static final String DIRECTORY_PROPERTY = "kk-studio.harness.contributors.directory";

  private final Optional<URLClassLoader> classLoader;
  private final List<HarnessContributor> contributors;
  private final AtomicBoolean closed = new AtomicBoolean();

  public TrustedJarContributorLoader(Path directory) {
    this(directory, contributorParent());
  }

  TrustedJarContributorLoader(Path directory, ClassLoader parent) {
    Path canonicalDirectory = canonicalDirectory(Objects.requireNonNull(directory, "directory"));
    URLClassLoader createdClassLoader = createClassLoader(jarUrls(canonicalDirectory), parent);
    try {
      this.contributors = loadContributors(canonicalDirectory, createdClassLoader);
      this.classLoader = Optional.of(createdClassLoader);
    } catch (RuntimeException error) {
      closeQuietly(createdClassLoader);
      throw error;
    }
  }

  private TrustedJarContributorLoader() {
    this.classLoader = Optional.empty();
    this.contributors = List.of();
  }

  public static TrustedJarContributorLoader empty() {
    return new TrustedJarContributorLoader();
  }

  public static TrustedJarContributorLoader fromConfiguredDirectory(String directory) {
    if (directory == null || directory.isBlank()) {
      return empty();
    }
    return new TrustedJarContributorLoader(Path.of(directory));
  }

  /** 返回启动时加载的不可变贡献者快照。 */
  public List<HarnessContributor> contributors() {
    return contributors;
  }

  /** 返回当前实例是否已经关闭。 */
  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    classLoader.ifPresent(
        loader -> {
          try {
            loader.close();
          } catch (IOException error) {
            throw new IllegalStateException(
                "failed to close trusted contributor classloader", error);
          }
        });
  }

  Optional<URLClassLoader> classLoader() {
    return classLoader;
  }

  private static ClassLoader contributorParent() {
    return Objects.requireNonNull(HarnessContributor.class.getClassLoader(), "contributor parent");
  }

  private static Path canonicalDirectory(Path directory) {
    try {
      Path canonical = directory.toRealPath();
      if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            "trusted contributor directory must be an existing directory: " + canonical);
      }
      return canonical;
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "trusted contributor directory must be an existing directory: " + directory, error);
    }
  }

  private static URLClassLoader createClassLoader(List<URL> urls, ClassLoader parent) {
    return new URLClassLoader(
        urls.toArray(URL[]::new), Objects.requireNonNull(parent, "contributor parent"));
  }

  private static List<URL> jarUrls(Path directory) {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(TrustedJarContributorLoader::isJar)
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .map(TrustedJarContributorLoader::toUrl)
          .toList();
    } catch (IOException error) {
      throw new IllegalStateException(
          "failed to scan trusted contributor directory: " + directory, error);
    }
  }

  private static boolean isJar(Path path) {
    return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
  }

  private static URL toUrl(Path path) {
    try {
      return path.toUri().toURL();
    } catch (IOException error) {
      throw new IllegalStateException("failed to resolve trusted contributor JAR: " + path, error);
    }
  }

  private static List<HarnessContributor> loadContributors(
      Path directory, ClassLoader classLoader) {
    try {
      ServiceLoader<HarnessContributor> serviceLoader =
          ServiceLoader.load(HarnessContributor.class, classLoader);
      List<HarnessContributor> loaded = new ArrayList<>();
      for (HarnessContributor contributor : serviceLoader) {
        HarnessContributor nonNullContributor =
            Objects.requireNonNull(contributor, "ServiceLoader returned null contributor");
        if (nonNullContributor.getClass().getClassLoader() != classLoader) {
          throw new IllegalStateException(
              "trusted contributor provider must be loaded by the trusted child classloader: "
                  + nonNullContributor.getClass().getName());
        }
        loaded.add(nonNullContributor);
      }
      return List.copyOf(loaded);
    } catch (ServiceConfigurationError error) {
      throw new IllegalStateException(
          "failed to load trusted HarnessContributor services from: " + directory, error);
    }
  }

  private static void closeQuietly(URLClassLoader classLoader) {
    try {
      classLoader.close();
    } catch (IOException ignored) {
      // Preserve the original startup failure.
    }
  }
}
