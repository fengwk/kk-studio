package fun.fengwk.kkstudio.web.runtime.contributor;

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

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;

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
class TrustedJarContributorLoaderTest {

  private static final String CONTRIBUTOR_CLASS = "dynamic.DynamicHarnessContributor";
  private static final String CONTRIBUTOR_ID = "dynamic";

  @Test
  void loadsSortedContributorsAndMarkerWithoutChangingTccl(@TempDir Path tempDirectory)
      throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    writeContributorJar(
        contributorDirectory.resolve("b.jar"), "dynamic.BContributor", "dynamic.b", "marker-b");
    writeContributorJar(
        contributorDirectory.resolve("a.jar"), "dynamic.AContributor", "dynamic.a", "marker-a");
    ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
    ClassLoader markerTccl = new ClassLoader(null) {};

    try {
      Thread.currentThread().setContextClassLoader(markerTccl);
      try (TrustedJarContributorLoader loader =
          new TrustedJarContributorLoader(contributorDirectory)) {
        URLClassLoader classLoader = loader.classLoader().orElseThrow();
        HarnessContributor first = loader.contributors().get(0);

        assertSame(markerTccl, Thread.currentThread().getContextClassLoader());
        assertSame(HarnessContributor.class.getClassLoader(), classLoader.getParent());
        assertEquals(List.of("dynamic.a", "dynamic.b"), contributorIds(loader.contributors()));
        assertEquals(classLoader, first.getClass().getClassLoader());
        assertNotSame(HarnessContributor.class.getClassLoader(), first.getClass().getClassLoader());
        try (InputStream marker = first.getClass().getResourceAsStream("/marker-a")) {
          assertEquals("marker-a", new String(marker.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    } finally {
      Thread.currentThread().setContextClassLoader(originalTccl);
    }
  }

  @Test
  void contributorCatalogConfigurationFreezesTrustedJarContributorAtStartup(
      @TempDir Path tempDirectory) throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    writeContributorJar(
        contributorDirectory.resolve("dynamic.jar"), CONTRIBUTOR_CLASS, CONTRIBUTOR_ID, "marker");
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    TrustedJarContributorLoader loader = null;
    try {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "trusted-contributor-test",
                  Map.of(
                      TrustedJarContributorLoader.DIRECTORY_PROPERTY,
                      contributorDirectory.toString())));
      context.register(ContributorCatalogConfiguration.class);
      context.refresh();

      loader = context.getBean(TrustedJarContributorLoader.class);
      HarnessCatalog catalog = context.getBean(HarnessCatalog.class);
      assertEquals(1, catalog.descriptors().size());
      assertTrue(context.getBeansOfType(HarnessContributor.class).isEmpty());
      assertTrue(
          catalog
              .findCustomEntryType(new ContributorId(CONTRIBUTOR_ID), "dynamic.type")
              .isPresent());
      assertFalse(loader.isClosed());
    } finally {
      context.close();
    }
    assertTrue(loader != null && loader.isClosed());
  }

  @Test
  void catalogFailureStillClosesLoaderDuringContextDestruction(@TempDir Path tempDirectory)
      throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    writeContributorJar(
        contributorDirectory.resolve("dynamic.jar"), CONTRIBUTOR_CLASS, CONTRIBUTOR_ID, "marker");
    AtomicReference<TrustedJarContributorLoader> observedLoader = new AtomicReference<>();
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.addBeanFactoryPostProcessor(
        beanFactory ->
            beanFactory.addBeanPostProcessor(
                new BeanPostProcessor() {
                  @Override
                  public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof TrustedJarContributorLoader loader) {
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
                  "trusted-contributor-failure-test",
                  Map.of(
                      TrustedJarContributorLoader.DIRECTORY_PROPERTY,
                      contributorDirectory.toString())));
      context.register(ContributorCatalogConfiguration.class);
      context.registerBean(
          "duplicateContributor",
          HarnessContributor.class,
          () ->
              HarnessContributor.of(
                  new ContributorDescriptor(
                      new ContributorId(CONTRIBUTOR_ID), "duplicate", "1", Set.of()),
                  registrar -> {}));

      assertThrows(RuntimeException.class, context::refresh);
      assertTrue(observedLoader.get() != null && observedLoader.get().isClosed());
    } finally {
      context.close();
    }
  }

  @Test
  void blankDirectoryProducesAnEmptyFrozenSnapshot() {
    TrustedJarContributorLoader loader = TrustedJarContributorLoader.fromConfiguredDirectory(" \t");
    assertTrue(loader.contributors().isEmpty());
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
            () -> new TrustedJarContributorLoader(tempDirectory.resolve("missing")));
    assertTrue(error.getMessage().contains("existing directory"));
  }

  @Test
  void rejectsRegularFileAsDirectory(@TempDir Path tempDirectory) throws Exception {
    Path file = tempDirectory.resolve("contributors-file");
    Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new TrustedJarContributorLoader(file));
    assertTrue(error.getMessage().contains("existing directory"));
  }

  @Test
  void preservesConfiguredPathWhitespace(@TempDir Path tempDirectory) throws Exception {
    Path directory = Files.createDirectory(tempDirectory.resolve("contributors "));
    try (TrustedJarContributorLoader loader =
        TrustedJarContributorLoader.fromConfiguredDirectory(directory.toString())) {
      assertTrue(loader.classLoader().isPresent());
    }
  }

  @Test
  void closeIsIdempotentAndClosesChildClassLoader(@TempDir Path tempDirectory) throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    writeContributorJar(
        contributorDirectory.resolve("dynamic.jar"), CONTRIBUTOR_CLASS, CONTRIBUTOR_ID, "marker");
    TrustedJarContributorLoader loader = new TrustedJarContributorLoader(contributorDirectory);
    URLClassLoader classLoader = loader.classLoader().orElseThrow();

    loader.close();
    assertTrue(loader.isClosed());
    assertDoesNotThrow(loader::close);
    assertNull(classLoader.getResource("marker"));
  }

  @Test
  void ignoresSymlinkJarWhenPlatformSupportsSymlinks(@TempDir Path tempDirectory) throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    Path realJar = tempDirectory.resolve("real.jar");
    writeContributorJar(realJar, CONTRIBUTOR_CLASS, CONTRIBUTOR_ID, "marker");
    try {
      Files.createSymbolicLink(contributorDirectory.resolve("linked.jar"), realJar);
    } catch (UnsupportedOperationException | IOException | SecurityException error) {
      Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error);
    }

    try (TrustedJarContributorLoader loader =
        new TrustedJarContributorLoader(contributorDirectory)) {
      assertTrue(loader.contributors().isEmpty());
    }
  }

  @Test
  void rejectsProviderLoadedByParentClassLoader(@TempDir Path tempDirectory) throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    Path parentResources = Files.createDirectories(tempDirectory.resolve("parent-resources"));
    Path serviceFile =
        parentResources.resolve("META-INF/services/" + HarnessContributor.class.getName());
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
            HarnessContributor.class.getClassLoader())) {
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () -> new TrustedJarContributorLoader(contributorDirectory, parent));
      assertTrue(error.getMessage().contains("child classloader"));
    }
  }

  @Test
  void wrapsInvalidServiceConfiguration(@TempDir Path tempDirectory) throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    Path jar = contributorDirectory.resolve("invalid.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("META-INF/services/" + HarnessContributor.class.getName()));
      output.write("dynamic.MissingContributor\n".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new TrustedJarContributorLoader(contributorDirectory));
    assertTrue(error.getMessage().contains("failed to load trusted HarnessContributor services"));
  }

  static void writeContributorJar(
      Path jar, String contributorClass, String contributorId, String marker) throws Exception {
    Path sourceDirectory = Files.createTempDirectory(jar.getParent(), "source-");
    Path classesDirectory = Files.createTempDirectory(jar.getParent(), "classes-");
    Path sourceFile =
        sourceDirectory.resolve(contributorClass.replace('.', File.separatorChar) + ".java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(
        sourceFile, contributorSource(contributorClass, contributorId), StandardCharsets.UTF_8);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      fail("dynamic contributor test requires a JDK JavaCompiler");
    }
    int exitCode =
        compiler.run(
            null,
            null,
            null,
            "-classpath",
            contributorApiClasspath(),
            "-d",
            classesDirectory.toString(),
            sourceFile.toString());
    assertEquals(0, exitCode, "dynamic contributor source must compile");

    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      Path classFile =
          classesDirectory.resolve(contributorClass.replace('.', File.separatorChar) + ".class");
      output.putNextEntry(new JarEntry(contributorClass.replace('.', '/') + ".class"));
      Files.copy(classFile, output);
      output.closeEntry();
      output.putNextEntry(new JarEntry("META-INF/services/" + HarnessContributor.class.getName()));
      output.write((contributorClass + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
      output.putNextEntry(new JarEntry(marker));
      output.write(marker.getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  private static String contributorApiClasspath() throws Exception {
    return Stream.of(
            HarnessContributor.class,
            ContributorDescriptor.class,
            ContributorId.class,
            HarnessRegistrar.class)
        .map(TrustedJarContributorLoaderTest::codeSource)
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

  private static String contributorSource(String contributorClass, String contributorId) {
    String className = contributorClass.substring(contributorClass.lastIndexOf('.') + 1);
    return """
        package dynamic;

        import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
        import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
        import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
        import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
        import java.util.Set;

        public final class %s implements HarnessContributor {
          @Override
          public ContributorDescriptor descriptor() {
            return new ContributorDescriptor(new ContributorId("%s"), "%s", "1", Set.of());
          }

          @Override
          public void contribute(HarnessRegistrar registrar) {
            registrar.registerCustomEntryType("entry", "dynamic.type");
          }
        }
        """
        .formatted(className, contributorId, contributorId);
  }

  private static List<String> contributorIds(List<HarnessContributor> contributors) {
    return contributors.stream().map(contributor -> contributor.descriptor().id().value()).toList();
  }

  public static final class ParentProvider implements HarnessContributor {

    @Override
    public ContributorDescriptor descriptor() {
      return new ContributorDescriptor(new ContributorId("parent"), "parent", "1", Set.of());
    }

    @Override
    public void contribute(HarnessRegistrar registrar) {}
  }
}
