package fun.fengwk.kkstudio.web.runtime.plugin;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.platform.harness.plugin.HarnessPluginSource;

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
 * 启动期加载本地受信任插件 JAR。
 *
 * <p>插件 JAR 只通过显式目录加入 child {@link URLClassLoader}，并以宿主的 {@link HarnessPlugin} classloader 作为
 * parent。默认的 URLClassLoader 委派顺序是 parent-first；本类不修改线程 context classloader，也不注册插件类型为 Spring bean。
 *
 * <p>实例构造完成后插件列表即冻结，不提供刷新或运行时安装入口；{@link #close()} 关闭 child classloader。
 */
public final class TrustedJarPluginLoader implements HarnessPluginSource, AutoCloseable {

  public static final String DIRECTORY_PROPERTY = "kk-studio.harness.plugins.directory";
  public static final String DIRECTORY_ENVIRONMENT_VARIABLE = "KK_STUDIO_TRUSTED_PLUGIN_DIRECTORY";

  private final Optional<URLClassLoader> classLoader;
  private final List<HarnessPlugin> plugins;
  private final AtomicBoolean closed = new AtomicBoolean();

  public TrustedJarPluginLoader(Path directory) {
    this(directory, pluginParent());
  }

  TrustedJarPluginLoader(Path directory, ClassLoader parent) {
    Path canonicalDirectory = canonicalDirectory(Objects.requireNonNull(directory, "directory"));
    URLClassLoader createdClassLoader = createClassLoader(jarUrls(canonicalDirectory), parent);
    try {
      this.plugins = loadPlugins(canonicalDirectory, createdClassLoader);
      this.classLoader = Optional.of(createdClassLoader);
    } catch (RuntimeException error) {
      closeQuietly(createdClassLoader);
      throw error;
    }
  }

  private TrustedJarPluginLoader() {
    this.classLoader = Optional.empty();
    this.plugins = List.of();
  }

  public static TrustedJarPluginLoader empty() {
    return new TrustedJarPluginLoader();
  }

  public static TrustedJarPluginLoader fromConfiguredDirectory(String directory) {
    if (directory == null || directory.isBlank()) {
      return empty();
    }
    return new TrustedJarPluginLoader(Path.of(directory));
  }

  /** 返回启动时加载的不可变插件快照。 */
  @Override
  public List<HarnessPlugin> plugins() {
    return plugins;
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
            throw new IllegalStateException("failed to close trusted plugin classloader", error);
          }
        });
  }

  Optional<URLClassLoader> classLoader() {
    return classLoader;
  }

  private static ClassLoader pluginParent() {
    return Objects.requireNonNull(HarnessPlugin.class.getClassLoader(), "plugin parent");
  }

  private static Path canonicalDirectory(Path directory) {
    try {
      Path canonical = directory.toRealPath();
      if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            "trusted plugin directory must be an existing directory: " + canonical);
      }
      return canonical;
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "trusted plugin directory must be an existing directory: " + directory, error);
    }
  }

  private static URLClassLoader createClassLoader(List<URL> urls, ClassLoader parent) {
    return new URLClassLoader(
        urls.toArray(URL[]::new), Objects.requireNonNull(parent, "plugin parent"));
  }

  private static List<URL> jarUrls(Path directory) {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(TrustedJarPluginLoader::isJar)
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .map(TrustedJarPluginLoader::toUrl)
          .toList();
    } catch (IOException error) {
      throw new IllegalStateException(
          "failed to scan trusted plugin directory: " + directory, error);
    }
  }

  private static boolean isJar(Path path) {
    return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
  }

  private static URL toUrl(Path path) {
    try {
      return path.toUri().toURL();
    } catch (IOException error) {
      throw new IllegalStateException("failed to resolve trusted plugin JAR: " + path, error);
    }
  }

  private static List<HarnessPlugin> loadPlugins(Path directory, ClassLoader classLoader) {
    try {
      ServiceLoader<HarnessPlugin> serviceLoader =
          ServiceLoader.load(HarnessPlugin.class, classLoader);
      List<HarnessPlugin> loaded = new ArrayList<>();
      for (HarnessPlugin plugin : serviceLoader) {
        HarnessPlugin nonNullPlugin =
            Objects.requireNonNull(plugin, "ServiceLoader returned null plugin");
        if (nonNullPlugin.getClass().getClassLoader() != classLoader) {
          throw new IllegalStateException(
              "trusted plugin provider must be loaded by the trusted child classloader: "
                  + nonNullPlugin.getClass().getName());
        }
        loaded.add(nonNullPlugin);
      }
      return List.copyOf(loaded);
    } catch (ServiceConfigurationError error) {
      throw new IllegalStateException(
          "failed to load trusted HarnessPlugin services from: " + directory, error);
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
