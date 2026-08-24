package fun.fengwk.kkstudio.platform.harness.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginRegistrar;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** 动态 trusted JAR 集成测试：用 JDK JavaCompiler 和 JarOutputStream 生成真实 ServiceLoader 资产。 */
class TrustedJarPluginLoaderTest {

  private static final String PLUGIN_CLASS = "dynamic.DynamicHarnessPlugin";
  private static final String PLUGIN_ID = "dynamic";

  @Test
  void loadsServiceFromJarWithParentFirstLoaderWithoutChangingTccl(@TempDir Path tempDirectory)
      throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    writePluginJar(pluginDirectory.resolve("dynamic.jar"));
    ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
    ClassLoader markerTccl = new ClassLoader(null) {};

    try {
      Thread.currentThread().setContextClassLoader(markerTccl);
      TrustedJarPluginLoader loader = new TrustedJarPluginLoader(pluginDirectory);

      assertSame(markerTccl, Thread.currentThread().getContextClassLoader());
      assertSame(HarnessPlugin.class.getClassLoader(), loader.classLoader().getParent());
      assertEquals(1, loader.plugins().size());
      assertEquals(PLUGIN_ID, loader.plugins().get(0).descriptor().id().value());
      assertNotSame(
          HarnessPlugin.class.getClassLoader(),
          loader.plugins().get(0).getClass().getClassLoader());
    } finally {
      Thread.currentThread().setContextClassLoader(originalTccl);
    }
  }

  @Test
  void pluginCatalogConfigurationFreezesTrustedJarPluginAtStartup(@TempDir Path tempDirectory)
      throws Exception {
    Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
    writePluginJar(pluginDirectory.resolve("dynamic.jar"));
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "trusted-plugin-test",
                  Map.of(TrustedJarPluginLoader.DIRECTORY_PROPERTY, pluginDirectory.toString())));
      context.register(PluginCatalogConfiguration.class);
      context.refresh();

      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertEquals(1, catalog.descriptors().size());
      assertTrue(context.getBeansOfType(HarnessPlugin.class).isEmpty());
      assertTrue(catalog.findCustomEntryType(new PluginId(PLUGIN_ID), "dynamic.type").isPresent());
    }
  }

  @Test
  void blankDirectoryProducesAnEmptyFrozenSnapshot() {
    assertTrue(TrustedJarPluginLoader.fromConfiguredDirectory(" ").plugins().isEmpty());
    assertTrue(TrustedJarPluginLoader.empty().plugins().isEmpty());
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

  private static void writePluginJar(Path jar) throws Exception {
    Path sourceDirectory = Files.createTempDirectory(jar.getParent(), "source-");
    Path classesDirectory = Files.createTempDirectory(jar.getParent(), "classes-");
    Path sourceFile =
        sourceDirectory.resolve(PLUGIN_CLASS.replace('.', File.separatorChar) + ".java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(sourceFile, pluginSource(), StandardCharsets.UTF_8);

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
          classesDirectory.resolve(PLUGIN_CLASS.replace('.', File.separatorChar) + ".class");
      output.putNextEntry(new JarEntry(PLUGIN_CLASS.replace('.', '/') + ".class"));
      Files.copy(classFile, output);
      output.closeEntry();
      output.putNextEntry(new JarEntry("META-INF/services/" + HarnessPlugin.class.getName()));
      output.write((PLUGIN_CLASS + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  private static String pluginApiClasspath() throws Exception {
    return Stream.of(
            HarnessPlugin.class,
            PluginDescriptor.class,
            PluginId.class,
            PluginRegistrar.class,
            ToolVisibility.class)
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

  private static String pluginSource() {
    return """
        package dynamic;

        import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
        import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
        import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
        import fun.fengwk.kkstudio.harness.plugin.api.PluginRegistrar;
        import java.util.Set;

        public final class DynamicHarnessPlugin implements HarnessPlugin {
          @Override
          public PluginDescriptor descriptor() {
            return new PluginDescriptor(new PluginId("dynamic"), "dynamic", "1", Set.of());
          }

          @Override
          public void contribute(PluginRegistrar registrar) {
            registrar.registerCustomEntryType("entry", "dynamic.type");
          }
        }
        """;
  }
}
