package fun.fengwk.kkstudio.platform.harness.plugin;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * 启动期加载本地受信任插件 JAR。
 *
 * <p>插件 JAR 只通过显式目录加入 child {@link URLClassLoader}，并以宿主的 {@link HarnessPlugin} classloader 作为
 * parent。默认的 URLClassLoader 委派顺序是 parent-first；本类不修改线程 context classloader，也不注册插件类型为 Spring bean。
 *
 * <p>实例构造完成后插件列表即冻结，不提供刷新、卸载或运行时安装入口。
 */
public final class TrustedJarPluginLoader {

  public static final String DIRECTORY_PROPERTY = "kk-studio.harness.plugins.directory";
  public static final String DIRECTORY_ENVIRONMENT_VARIABLE = "KK_STUDIO_TRUSTED_PLUGIN_DIRECTORY";

  private final URLClassLoader classLoader;
  private final List<HarnessPlugin> plugins;

  public TrustedJarPluginLoader(Path directory) {
    Objects.requireNonNull(directory, "directory");
    Path normalizedDirectory = directory.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalizedDirectory)) {
      throw new IllegalArgumentException(
          "trusted plugin directory must be an existing directory: " + normalizedDirectory);
    }
    this.classLoader = createClassLoader(jarUrls(normalizedDirectory));
    this.plugins = loadPlugins(normalizedDirectory, classLoader);
  }

  private TrustedJarPluginLoader() {
    this.classLoader = createClassLoader(List.of());
    this.plugins = List.of();
  }

  public static TrustedJarPluginLoader empty() {
    return new TrustedJarPluginLoader();
  }

  public static TrustedJarPluginLoader fromConfiguredDirectory(String directory) {
    if (directory == null || directory.isBlank()) {
      return empty();
    }
    return new TrustedJarPluginLoader(Path.of(directory.trim()));
  }

  /** 返回启动时加载的不可变插件快照。 */
  public List<HarnessPlugin> plugins() {
    return plugins;
  }

  URLClassLoader classLoader() {
    return classLoader;
  }

  private static URLClassLoader createClassLoader(List<URL> urls) {
    ClassLoader parent =
        Objects.requireNonNull(HarnessPlugin.class.getClassLoader(), "plugin parent");
    return new URLClassLoader(urls.toArray(URL[]::new), parent);
  }

  private static List<URL> jarUrls(Path directory) {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(Files::isRegularFile)
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
        loaded.add(Objects.requireNonNull(plugin, "ServiceLoader returned null plugin"));
      }
      return List.copyOf(loaded);
    } catch (ServiceConfigurationError error) {
      throw new IllegalStateException(
          "failed to load trusted HarnessPlugin services from: " + directory, error);
    }
  }
}
