package fun.fengwk.kkstudio.web.runtime.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginRegistrar;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** 动态 trusted JAR 集成测试：用 JDK JavaCompiler 和 JarOutputStream 生成真实 ServiceLoader 资产。 */
class TrustedJarPluginLoaderTest {

  private static final String PLUGIN_CLASS = "dynamic.DynamicHarnessPlugin";
  private static final String PLUGIN_ID = "dynamic";

  @Test
  void loadsSortedServicesAndMarkerWithoutChangingTccl(@TempDir Path tempDirectory)
      throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    writePluginJar(pluginDirectory.resolve("b.jar"), "dynamic.BPlugin", "dynamic.b", "marker-b");
    writePluginJar(pluginDirectory.resolve("a.jar"), "dynamic.APlugin", "dynamic.a", "marker-a");
    ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
    ClassLoader markerTccl = new ClassLoader(null) {};

    try {
      Thread.currentThread().setContextClassLoader(markerTccl);
      try (TrustedJarPluginLoader loader = new TrustedJarPluginLoader(pluginDirectory)) {
        URLClassLoader classLoader = loader.classLoader().orElseThrow();
        HarnessPlugin first = loader.plugins().get(0);

        assertSame(markerTccl, Thread.currentThread().getContextClassLoader());
        assertSame(HarnessPlugin.class.getClassLoader(), classLoader.getParent());
        assertEquals(List.of("dynamic.a", "dynamic.b"), pluginIds(loader.plugins()));
        assertEquals(classLoader, first.getClass().getClassLoader());
        assertNotSame(HarnessPlugin.class.getClassLoader(), first.getClass().getClassLoader());
        try (InputStream marker = first.getClass().getResourceAsStream("/marker-a")) {
          assertEquals("marker-a", new String(marker.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    } finally {
      Thread.currentThread().setContextClassLoader(originalTccl);
    }
  }

  @Test
  void pluginCatalogConfigurationFreezesTrustedJarPluginAtStartup(@TempDir Path tempDirectory)
      throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    writePluginJar(pluginDirectory.resolve("dynamic.jar"), PLUGIN_CLASS, PLUGIN_ID, "marker");
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    TrustedJarPluginLoader loader = null;
    try {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "trusted-plugin-test",
                  Map.of(TrustedJarPluginLoader.DIRECTORY_PROPERTY, pluginDirectory.toString())));
      context.register(PluginCatalogConfiguration.class);
      context.refresh();

      loader = context.getBean(TrustedJarPluginLoader.class);
      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertEquals(1, catalog.descriptors().size());
      assertTrue(context.getBeansOfType(HarnessPlugin.class).isEmpty());
      assertTrue(catalog.findCustomEntryType(new PluginId(PLUGIN_ID), "dynamic.type").isPresent());
      assertFalse(loader.isClosed());
    } finally {
      context.close();
    }
    assertTrue(loader != null && loader.isClosed());
  }

  @Test
  void catalogFailureStillClosesLoaderDuringContextDestruction(@TempDir Path tempDirectory)
      throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    writePluginJar(pluginDirectory.resolve("dynamic.jar"), PLUGIN_CLASS, PLUGIN_ID, "marker");
    AtomicReference<TrustedJarPluginLoader> observedLoader = new AtomicReference<>();
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.addBeanFactoryPostProcessor(
        beanFactory ->
            beanFactory.addBeanPostProcessor(
                new BeanPostProcessor() {
                  @Override
                  public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof TrustedJarPluginLoader loader) {
                      observedLoader.set(loader);
                    }
                    return bean;
                  }
                }));
    try {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "trusted-plugin-failure-test",
                  Map.of(TrustedJarPluginLoader.DIRECTORY_PROPERTY, pluginDirectory.toString())));
      context.register(PluginCatalogConfiguration.class);
      context.registerBean(
          "duplicatePlugin",
          HarnessPlugin.class,
          () ->
              HarnessPlugin.of(
                  new PluginDescriptor(new PluginId(PLUGIN_ID), "duplicate", "1", Set.of()),
                  registrar -> {}));

      assertThrows(RuntimeException.class, context::refresh);
      assertTrue(observedLoader.get() != null && observedLoader.get().isClosed());
    } finally {
      context.close();
    }
  }

  @Test
  void blankDirectoryProducesAnEmptyFrozenSnapshot() {
    TrustedJarPluginLoader loader = TrustedJarPluginLoader.fromConfiguredDirectory(" \t");
    assertTrue(loader.plugins().isEmpty());
    assertTrue(loader.classLoader().isEmpty());
    assertFalse(loader.isClosed());
    loader.close();
    assertTrue(loader.isClosed());
  }

  @Test
  void rejectsMissingDirectory(@TempDir Path tempDirectory) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new TrustedJarPluginLoader(tempDirectory.resolve("missing")));
    assertTrue(error.getMessage().contains("existing directory"));
  }

  @Test
  void rejectsRegularFileAsDirectory(@TempDir Path tempDirectory) throws Exception {
    Path file = tempDirectory.resolve("plugins-file");
    Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new TrustedJarPluginLoader(file));
    assertTrue(error.getMessage().contains("existing directory"));
  }

  @Test
  void preservesConfiguredPathWhitespace(@TempDir Path tempDirectory) throws Exception {
    Path directory = Files.createDirectory(tempDirectory.resolve("plugins "));
    try (TrustedJarPluginLoader loader =
        TrustedJarPluginLoader.fromConfiguredDirectory(directory.toString())) {
      assertTrue(loader.classLoader().isPresent());
    }
  }

  @Test
  void closeIsIdempotentAndClosesChildClassLoader(@TempDir Path tempDirectory) throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    writePluginJar(pluginDirectory.resolve("dynamic.jar"), PLUGIN_CLASS, PLUGIN_ID, "marker");
    TrustedJarPluginLoader loader = new TrustedJarPluginLoader(pluginDirectory);
    URLClassLoader classLoader = loader.classLoader().orElseThrow();

    loader.close();
    assertTrue(loader.isClosed());
    assertDoesNotThrow(loader::close);
    assertNull(classLoader.getResource("marker"));
  }

  @Test
  void ignoresSymlinkJarWhenPlatformSupportsSymlinks(@TempDir Path tempDirectory) throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    Path realJar = tempDirectory.resolve("real.jar");
    writePluginJar(realJar, PLUGIN_CLASS, PLUGIN_ID, "marker");
    try {
      Files.createSymbolicLink(pluginDirectory.resolve("linked.jar"), realJar);
    } catch (UnsupportedOperationException | IOException | SecurityException error) {
      Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error);
    }

    try (TrustedJarPluginLoader loader = new TrustedJarPluginLoader(pluginDirectory)) {
      assertTrue(loader.plugins().isEmpty());
    }
  }

  @Test
  void rejectsProviderLoadedByParentClassLoader(@TempDir Path tempDirectory) throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    Path parentResources = Files.createDirectories(tempDirectory.resolve("parent-resources"));
    Path serviceFile =
        parentResources.resolve("META-INF/services/" + HarnessPlugin.class.getName());
    Files.createDirectories(serviceFile.getParent());
    Files.writeString(
        serviceFile,
        ParentProvider.class.getName() + System.lineSeparator(),
        StandardCharsets.UTF_8);
    try (URLClassLoader parent =
        new URLClassLoader(
            new URL[] {
              parentResources.toUri().toURL(), codeSource(ParentProvider.class).toUri().toURL()
            },
            HarnessPlugin.class.getClassLoader())) {
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () -> new TrustedJarPluginLoader(pluginDirectory, parent));
      assertTrue(error.getMessage().contains("child classloader"));
    }
  }

  @Test
  void wrapsInvalidServiceConfiguration(@TempDir Path tempDirectory) throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    Path jar = pluginDirectory.resolve("invalid.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("META-INF/services/" + HarnessPlugin.class.getName()));
      output.write("dynamic.MissingPlugin\n".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> new TrustedJarPluginLoader(pluginDirectory));
    assertTrue(error.getMessage().contains("failed to load trusted HarnessPlugin services"));
  }

  private static void writePluginJar(Path jar, String pluginClass, String pluginId, String marker)
      throws Exception {
    Path sourceDirectory = Files.createTempDirectory(jar.getParent(), "source-");
    Path classesDirectory = Files.createTempDirectory(jar.getParent(), "classes-");
    Path sourceFile =
        sourceDirectory.resolve(pluginClass.replace('.', File.separatorChar) + ".java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(sourceFile, pluginSource(pluginClass, pluginId), StandardCharsets.UTF_8);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      fail("dynamic plugin test requires a JDK JavaCompiler");
    }
    int exitCode =
        compiler.run(
            null,
            null,
            null,
            "-classpath",
            pluginApiClasspath(),
            "-d",
            classesDirectory.toString(),
            sourceFile.toString());
    assertEquals(0, exitCode, "dynamic plugin source must compile");

    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      Path classFile =
          classesDirectory.resolve(pluginClass.replace('.', File.separatorChar) + ".class");
      output.putNextEntry(new JarEntry(pluginClass.replace('.', '/') + ".class"));
      Files.copy(classFile, output);
      output.closeEntry();
      output.putNextEntry(new JarEntry("META-INF/services/" + HarnessPlugin.class.getName()));
      output.write((pluginClass + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
      output.putNextEntry(new JarEntry(marker));
      output.write(marker.getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  private static String pluginApiClasspath() throws Exception {
    return Stream.of(
            HarnessPlugin.class, PluginDescriptor.class, PluginId.class, PluginRegistrar.class)
        .map(TrustedJarPluginLoaderTest::codeSource)
        .distinct()
        .map(Path::toString)
        .reduce((left, right) -> left + File.pathSeparator + right)
        .orElseThrow();
  }

  private static Path codeSource(Class<?> type) {
    try {
      URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
      return Path.of(location);
    } catch (Exception error) {
      throw new IllegalStateException("failed to resolve test classpath for " + type, error);
    }
  }

  private static String pluginSource(String pluginClass, String pluginId) {
    String className = pluginClass.substring(pluginClass.lastIndexOf('.') + 1);
    return """
        package dynamic;

        import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
        import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
        import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
        import fun.fengwk.kkstudio.harness.plugin.api.PluginRegistrar;
        import java.util.Set;

        public final class %s implements HarnessPlugin {
          @Override
          public PluginDescriptor descriptor() {
            return new PluginDescriptor(new PluginId("%s"), "%s", "1", Set.of());
          }

          @Override
          public void contribute(PluginRegistrar registrar) {
            registrar.registerCustomEntryType("entry", "dynamic.type");
          }
        }
        """
        .formatted(className, pluginId, pluginId);
  }

  private static List<String> pluginIds(List<HarnessPlugin> plugins) {
    return plugins.stream().map(plugin -> plugin.descriptor().id().value()).toList();
  }

  public static final class ParentProvider implements HarnessPlugin {

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(new PluginId("parent"), "parent", "1", Set.of());
    }

    @Override
    public void contribute(PluginRegistrar registrar) {}
  }
}
