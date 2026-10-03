package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** 从 Surefire 的实际 JAR 清单读取构件元数据，不以 POM 文本代替 resolved 版本证据。 */
class ResolvedSecurityDependenciesTest {

  /** 测试意图：安全修订必须真正进入 classpath，整族一致，不能只更新未生效的 BOM 属性。 */
  @Test
  void resolvedSecurityFamiliesUseCentralRevisions() throws Exception {
    Map<String, Set<String>> versions = readArtifactVersions();
    assertFamily(versions, "org.apache.tomcat.embed:", "11.0.26");
    assertFamily(versions, "io.netty:", "4.2.18.Final");
    assertFamily(versions, "org.apache.httpcomponents.core5:", "5.4.4");
    assertFamily(versions, "org.apache.httpcomponents.client5:", "5.6.4");
    assertFamily(versions, "org.apache.opennlp:", "2.5.12");
    assertFamily(versions, "tools.jackson.", "3.1.7");
    for (String coordinate : versions.keySet()) {
      if (coordinate.startsWith("com.fasterxml.jackson.")) {
        // Jackson 2/3 BOM 共享 annotations；该构件从 2.20 起使用无 patch 的发布版本。
        assertEquals(
            Set.of(
                coordinate.equals("com.fasterxml.jackson.core:jackson-annotations")
                    ? "2.22"
                    : "2.22.3"),
            versions.get(coordinate),
            coordinate);
      }
    }
    for (String required :
        Set.of(
            "org.apache.tomcat.embed:tomcat-embed-core",
            "org.apache.tomcat.embed:tomcat-embed-el",
            "org.apache.tomcat.embed:tomcat-embed-websocket",
            "org.apache.httpcomponents.core5:httpcore5",
            "org.apache.httpcomponents.core5:httpcore5-h2",
            "org.apache.httpcomponents.client5:httpclient5",
            "org.apache.opennlp:opennlp-tools",
            "com.fasterxml.jackson.core:jackson-core",
            "com.fasterxml.jackson.core:jackson-databind",
            "tools.jackson.core:jackson-core",
            "tools.jackson.core:jackson-databind")) {
      assertTrue(versions.containsKey(required), "missing resolved dependency: " + required);
    }
  }

  /** 测试意图：BOM 必须收敛实际 Kotlin runtime，stdlib 已包含 common metadata，不能再引入 legacy JAR。 */
  @Test
  void kotlinRuntimeHasNoMixedVersions() throws Exception {
    Map<String, Set<String>> versions = readArtifactVersions();
    assertFalse(
        versions.containsKey("org.jetbrains.kotlin:kotlin-stdlib-common"),
        "legacy common metadata must not enter the runtime classpath");
    assertFamily(versions, "org.jetbrains.kotlin:", "2.4.20");
  }

  private static void assertFamily(
      Map<String, Set<String>> versions, String prefix, String expected) {
    Map<String, Set<String>> family = new TreeMap<>();
    versions.forEach(
        (coordinate, resolved) -> {
          if (coordinate.startsWith(prefix)) {
            family.put(coordinate, resolved);
          }
        });
    assertFalse(family.isEmpty(), "missing resolved family: " + prefix);
    family.forEach((coordinate, resolved) -> assertEquals(Set.of(expected), resolved, coordinate));
  }

  private static Map<String, Set<String>> readArtifactVersions() throws Exception {
    Map<String, Set<String>> versions = new TreeMap<>();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
      if (!entry.endsWith(".jar")) {
        continue;
      }
      try (JarFile jar = new JarFile(entry)) {
        String name = Path.of(entry).getFileName().toString();
        for (var metadata :
            jar.stream().filter(e -> e.getName().endsWith("/pom.properties")).toList()) {
          Properties properties = new Properties();
          try (InputStream in = jar.getInputStream(metadata)) {
            properties.load(in);
          }
          String artifact = properties.getProperty("artifactId");
          String version = properties.getProperty("version");
          // 只读取构件自身元数据；例如 docker-java zerodep 保留了已 relocation 的 httpcore 元数据。
          if (name.startsWith(artifact + "-" + version)) {
            addVersion(versions, properties.getProperty("groupId") + ":" + artifact, version);
          }
        }
        // Tomcat / Kotlin 不带 Maven 元数据；读取各实际 JAR 的发布 manifest，而非文件名猜版本。
        if (name.startsWith("tomcat-embed-") || name.startsWith("kotlin-stdlib")) {
          Attributes attributes = jar.getManifest().getMainAttributes();
          String artifact = name.replaceFirst("-\\d.*\\.jar$", "");
          addVersion(
              versions,
              (name.startsWith("tomcat-embed-")
                      ? "org.apache.tomcat.embed:"
                      : "org.jetbrains.kotlin:")
                  + artifact,
              attributes.getValue("Implementation-Version"));
        }
      }
    }
    return versions;
  }

  private static void addVersion(
      Map<String, Set<String>> versions, String coordinate, String version) {
    assertTrue(version != null && !version.isBlank(), "missing JAR version for " + coordinate);
    versions.computeIfAbsent(coordinate, ignored -> new TreeSet<>()).add(version);
  }
}
