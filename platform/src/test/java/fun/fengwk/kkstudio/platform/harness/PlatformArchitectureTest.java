package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 守护 platform 与 framework-free Harness runtime API 的组合/应用边界：platform 提供应用能力与 Port 适配，不依赖
 * infra/web/daemon 下游模块；web 是唯一组合根，platform 不能成为 Spring Boot 启动入口。
 */
class PlatformArchitectureTest {

  /** platform main 源码禁止直接 import 的下游模块包前缀。 */
  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.infra.",
          "fun.fengwk.kkstudio.canvas.infra.",
          "fun.fengwk.kkstudio.web.",
          "fun.fengwk.kkstudio.harness.daemon.");

  /** platform/pom.xml 禁止声明的下游模块 artifactId。 */
  private static final List<String> FORBIDDEN_POM_ARTIFACTS =
      List.of("kk-studio-harness-infra", "kk-studio-web", "kk-studio-harness-daemon");

  /** trusted JAR 发现和 classloader 生命周期只属于 web 组合根。 */
  private static final List<String> FORBIDDEN_TRUSTED_CONTRIBUTOR_REFERENCES =
      List.of(
          "TrustedJarContributorLoader",
          "URLClassLoader",
          "ServiceLoader",
          "kk-studio.harness.contributors.directory",
          "KK_STUDIO_TRUSTED_CONTRIBUTOR_DIRECTORY");

  /** platform 不是组合根：main 源码禁止 import framework 基础设施与 web/daemon；pom 不得声明对应的下游模块 artifactId。 */
  @Test
  void platformNeverDependsOnInfraOrWebOrDaemon() throws IOException {
    Path main = locatePlatformMainJava();
    List<String> violations = new ArrayList<>();
    List<String> bootMainViolations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      List<Path> javaFiles = paths.filter(c -> c.toString().endsWith(".java")).toList();
      for (Path path : javaFiles) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("@SpringBootApplication")
              || trimmed.startsWith("SpringApplication.run(")) {
            bootMainViolations.add(relative(main, path) + ": " + trimmed);
          }
          if (!trimmed.startsWith("import ")) {
            for (String forbidden : FORBIDDEN_TRUSTED_CONTRIBUTOR_REFERENCES) {
              if (line.contains(forbidden)) {
                violations.add(
                    relative(main, path) + ": forbidden trusted contributor reference " + line);
              }
            }
            continue;
          }
          String imported = normalizeImport(trimmed);
          for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
            if (imported.startsWith(prefix)) {
              violations.add(relative(main, path) + ": " + trimmed);
            }
          }
          for (String forbidden : FORBIDDEN_TRUSTED_CONTRIBUTOR_REFERENCES) {
            if (line.contains(forbidden)) {
              violations.add(
                  relative(main, path) + ": forbidden trusted contributor reference " + line);
            }
          }
        }
      }
    }

    Path pom = locatePlatformPom(main);
    String pomText = Files.readString(pom, StandardCharsets.UTF_8);
    for (String artifactId : FORBIDDEN_POM_ARTIFACTS) {
      assertFalse(
          pomText.contains("<artifactId>" + artifactId + "</artifactId>"),
          "platform/pom.xml must not declare " + artifactId);
    }
    assertTrue(
        pomText.contains("<artifactId>kk-studio-harness-common</artifactId>"),
        "platform/pom.xml must declare kk-studio-harness-common");
    assertTrue(
        pomText.contains("<artifactId>kk-studio-harness-provider</artifactId>"),
        "platform/pom.xml must declare kk-studio-harness-provider");
    assertCanvasInfraIsTestScoped(pomText);

    assertTrue(
        violations.isEmpty(),
        () -> "Platform boundary violations:\n" + String.join("\n", violations));
    assertTrue(
        bootMainViolations.isEmpty(),
        () ->
            "Platform must not become a Spring Boot main:\n"
                + String.join("\n", bootMainViolations));
  }

  /**
   * Environment gateway 只实现 Environment Capability transport，不得回引 harness.tool（ToolExecutionGateway
   * 是跨 Environment/Tool 域结果的唯一适配点）。
   */
  @Test
  void environmentGatewayUsesCapabilityTransportOnly() throws IOException {
    Path gateway =
        locatePlatformMainJava()
            .resolve(
                "fun/fengwk/kkstudio/platform/environment/gateway/"
                    + "EnvironmentDaemonGateway.java");
    assertTrue(Files.isRegularFile(gateway), "EnvironmentDaemonGateway must exist");
    String source = Files.readString(gateway, StandardCharsets.UTF_8);
    assertFalse(
        source.contains("fun.fengwk.kkstudio.harness.tool."),
        "EnvironmentDaemonGateway must not depend on harness tool package");
    assertFalse(
        source.contains("Remote" + "Tool"),
        "EnvironmentDaemonGateway must use Environment Capability terminology");
  }

  /**
   * path pattern 的 gitignore 语义只在 harness-runtime 的 {@code PermissionPathPattern} 内部持有；platform
   * 永远不直接 import JGit。
   */
  @Test
  void platformNeverImportsJGitDirectly() throws IOException {
    Path main = locatePlatformMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      List<Path> javaFiles = paths.filter(c -> c.toString().endsWith(".java")).toList();
      for (Path path : javaFiles) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("import ")
              && normalizeImport(trimmed).startsWith("org.eclipse.jgit")) {
            violations.add(relative(main, path) + ": " + trimmed);
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "Platform JGit imports must not exist:\n" + String.join("\n", violations));
  }

  private static void assertCanvasInfraIsTestScoped(String pomText) {
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pomText);
    int declarations = 0;
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if (!dependency.contains("<artifactId>kk-studio-canvas-infra</artifactId>")) {
        continue;
      }
      declarations++;
      assertTrue(
          dependency.contains("<scope>test</scope>"),
          "platform may depend on kk-studio-canvas-infra only in test scope");
    }
    assertTrue(declarations == 1, "platform must declare one test-scoped canvas infra dependency");
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
  }

  private static String relative(Path main, Path path) {
    return main.relativize(path).toString();
  }

  /**
   * 当 Maven 在模块根或 reactor 根运行时解析 {@code src/main/java}，不会解析到 {@code target/} 下；优先匹配模块内的 main 源码，再回退
   * reactor 根的相对路径。
   */
  private static Path locatePlatformMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("platform/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !normalized.toString().contains("/target/")) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate platform main sources from " + cwd);
  }

  private static Path locatePlatformPom(Path mainJava) {
    Path moduleRoot = mainJava.getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      return pom;
    }
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path reactorPom = cwd.resolve("platform/pom.xml");
    if (Files.isRegularFile(reactorPom)) {
      return reactorPom;
    }
    throw new IllegalStateException(
        "cannot locate platform/pom.xml from " + mainJava + " or " + cwd);
  }

  private static final Set<String> REQUIRED_LANGCHAIN_DECLARATIONS =
      Set.of(
          "pom.xml|dependencyManagement|dev.langchain4j|langchain4j-bom",
          "platform/pom.xml|dependencies|dev.langchain4j|langchain4j-core",
          "platform/pom.xml|dependencies|dev.langchain4j|langchain4j-mcp");

  /**
   * 全仓 POM 架构守卫：LangChain4j 依赖禁止重新进入 reactor，除 platform 的 MCP 适配器（langchain4j-core、langchain4j-mcp）
   * 与根 POM 的版本管理（langchain4j-bom）外，任何其他 artifact 或模块声明都会被拦截。
   */
  @Test
  void reactorDeclaresNoDisallowedLangChainDependencies() {
    Path root = locateReactorRoot();
    List<Path> poms = enumerateReactorPoms(root);
    List<DeclaredDependency> allDependencies = new ArrayList<>();
    for (Path pom : poms) {
      allDependencies.addAll(collectDeclaredDependencies(pom, root));
    }

    List<String> violations = findDisallowedLangChainDependencies(allDependencies);
    List<DeclaredDependency> allowedFound =
        allDependencies.stream()
            .filter(PlatformArchitectureTest::isLangChainDependency)
            .filter(PlatformArchitectureTest::isAllowedLangChainDependency)
            .toList();

    Set<String> allowedIdentitySet = new LinkedHashSet<>();
    List<String> duplicateDeclarations = new ArrayList<>();
    for (DeclaredDependency dep : allowedFound) {
      if (!allowedIdentitySet.add(dep.identity())) {
        duplicateDeclarations.add(
            String.format(
                "duplicate allowed LangChain4j declaration in %s (%s): %s:%s",
                dep.modulePath(), dep.context(), dep.groupId(), dep.artifactId()));
      }
    }

    Set<String> missing = new LinkedHashSet<>(REQUIRED_LANGCHAIN_DECLARATIONS);
    missing.removeAll(allowedIdentitySet);

    assertTrue(
        duplicateDeclarations.isEmpty(),
        () ->
            "Duplicate LangChain4j declarations detected across reactor POMs:\n"
                + String.join("\n", duplicateDeclarations));
    assertEquals(
        REQUIRED_LANGCHAIN_DECLARATIONS,
        allowedIdentitySet,
        () ->
            "Expected exact allowed LangChain4j declaration set mismatch. Missing: "
                + missing
                + ", actual found: "
                + allowedIdentitySet);
    assertEquals(
        allowedIdentitySet.size(),
        allowedFound.size(),
        () ->
            "Found "
                + allowedFound.size()
                + " allowed declarations but unique identity set has size "
                + allowedIdentitySet.size());
    assertTrue(
        violations.isEmpty(),
        () ->
            "Disallowed LangChain4j dependencies detected across reactor POMs:\n"
                + String.join("\n", violations));
  }

  /** 验证守卫逻辑能够精确识别重复声明与缺失声明，并提供明确诊断。 */
  @Test
  void langChainDependencyGuardDetectsDuplicatesAndMissingDeclarations() {
    List<DeclaredDependency> duplicated =
        List.of(
            new DeclaredDependency(
                "platform/pom.xml", "dependencies", "dev.langchain4j", "langchain4j-mcp", "1.19.0"),
            new DeclaredDependency(
                "platform/pom.xml", "dependencies", "dev.langchain4j", "langchain4j-mcp", "1.19.0"),
            new DeclaredDependency(
                "platform/pom.xml",
                "dependencies",
                "dev.langchain4j",
                "langchain4j-mcp",
                "1.19.0"));

    assertEquals(3, duplicated.size());
    Set<String> identitySet = new LinkedHashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (DeclaredDependency dep : duplicated) {
      if (!identitySet.add(dep.identity())) {
        duplicates.add(dep.identity());
      }
    }
    assertEquals(2, duplicates.size());
    assertEquals(1, identitySet.size());

    Set<String> missing = new LinkedHashSet<>(REQUIRED_LANGCHAIN_DECLARATIONS);
    missing.removeAll(identitySet);
    assertEquals(2, missing.size());
    assertTrue(missing.contains("pom.xml|dependencyManagement|dev.langchain4j|langchain4j-bom"));
    assertTrue(missing.contains("platform/pom.xml|dependencies|dev.langchain4j|langchain4j-core"));
  }

  /** 验证守卫逻辑对非法坐标、非法模块与非法上下文具备明确的违规拦截与诊断能力。 */
  @Test
  void langChainDependencyGuardRejectsForbiddenDeclarationsWithDiagnostics() {
    List<DeclaredDependency> simulated =
        List.of(
            // 试图重新引入 OpenAI model provider
            new DeclaredDependency(
                "platform/pom.xml",
                "dependencies",
                "dev.langchain4j",
                "langchain4j-open-ai",
                "1.19.0"),
            // 在非 platform 模块中引入 MCP
            new DeclaredDependency(
                "harness/daemon/pom.xml",
                "dependencies",
                "dev.langchain4j",
                "langchain4j-mcp",
                "1.19.0"),
            // 使用 io.github.langchain4j 坐标
            new DeclaredDependency(
                "share/pom.xml",
                "dependencies",
                "io.github.langchain4j",
                "langchain4j-core",
                "1.19.0"),
            // 子模块私自声明 BOM
            new DeclaredDependency(
                "web/pom.xml",
                "dependencyManagement",
                "dev.langchain4j",
                "langchain4j-bom",
                "1.19.0"));

    List<String> violations = findDisallowedLangChainDependencies(simulated);
    assertEquals(4, violations.size());
    assertTrue(violations.get(0).contains("platform/pom.xml"));
    assertTrue(violations.get(0).contains("langchain4j-open-ai"));
    assertTrue(violations.get(1).contains("harness/daemon/pom.xml"));
    assertTrue(violations.get(1).contains("langchain4j-mcp"));
    assertTrue(violations.get(2).contains("share/pom.xml"));
    assertTrue(violations.get(2).contains("io.github.langchain4j"));
    assertTrue(violations.get(3).contains("web/pom.xml"));
    assertTrue(violations.get(3).contains("dependencyManagement"));
  }

  /** 验证 profile、pluginManagement 等隐蔽上下文中的 LangChain 依赖均会被精确拦截。 */
  @Test
  void langChainDependencyGuardRejectsProfileAndPluginManagementDependencies() {
    List<DeclaredDependency> simulated =
        List.of(
            new DeclaredDependency(
                "platform/pom.xml",
                "profile[ci]/dependencies",
                "dev.langchain4j",
                "langchain4j-mcp",
                "1.19.0"),
            new DeclaredDependency(
                "pom.xml",
                "profile[release]/dependencyManagement",
                "dev.langchain4j",
                "langchain4j-bom",
                "1.19.0"),
            new DeclaredDependency(
                "platform/pom.xml",
                "buildPluginManagement",
                "dev.langchain4j",
                "langchain4j-core",
                "1.19.0"),
            new DeclaredDependency(
                "share/pom.xml",
                "profile[coverage]/buildPluginManagement",
                "dev.langchain4j",
                "langchain4j-core",
                "1.19.0"));

    List<String> violations = findDisallowedLangChainDependencies(simulated);
    assertEquals(4, violations.size());
    assertTrue(violations.get(0).contains("profile[ci]/dependencies"));
    assertTrue(violations.get(1).contains("profile[release]/dependencyManagement"));
    assertTrue(violations.get(2).contains("buildPluginManagement"));
    assertTrue(violations.get(3).contains("profile[coverage]/buildPluginManagement"));
  }

  /** 验证 DOM 提取能完整识别 profiles、pluginManagement 中的 dependency，且不会将 exclusion 误识别为 dependency。 */
  @Test
  void collectDeclaredDependenciesCapturesProfilesAndPluginManagementWithoutConfusingExclusions(
      @TempDir Path tempDir) throws IOException {
    Path dummyPom = tempDir.resolve("pom.xml");
    String xml =
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>fun.fengwk.test</groupId>
          <artifactId>dummy</artifactId>
          <version>1.0.0</version>
          <dependencies>
            <dependency>
              <groupId>com.google.guava</groupId>
              <artifactId>guava</artifactId>
              <version>33.0.0-jre</version>
              <exclusions>
                <exclusion>
                  <groupId>dev.langchain4j</groupId>
                  <artifactId>langchain4j-core</artifactId>
                </exclusion>
              </exclusions>
            </dependency>
          </dependencies>
          <build>
            <pluginManagement>
              <plugins>
                <plugin>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>dev.langchain4j</groupId>
                      <artifactId>langchain4j-core</artifactId>
                      <version>1.19.0</version>
                    </dependency>
                  </dependencies>
                </plugin>
              </plugins>
            </pluginManagement>
          </build>
          <profiles>
            <profile>
              <id>test-profile</id>
              <dependencies>
                <dependency>
                  <groupId>dev.langchain4j</groupId>
                  <artifactId>langchain4j-mcp</artifactId>
                  <version>1.19.0</version>
                </dependency>
              </dependencies>
            </profile>
          </profiles>
        </project>
        """;
    Files.writeString(dummyPom, xml, StandardCharsets.UTF_8);

    List<DeclaredDependency> dependencies = collectDeclaredDependencies(dummyPom, tempDir);
    assertEquals(3, dependencies.size());

    // 1. 普通依赖
    assertEquals("com.google.guava:guava", dependencies.get(0).coordinate());
    assertEquals("dependencies", dependencies.get(0).context());

    // 2. buildPluginManagement 依赖
    assertEquals("dev.langchain4j:langchain4j-core", dependencies.get(1).coordinate());
    assertEquals("buildPluginManagement", dependencies.get(1).context());

    // 3. profile 依赖
    assertEquals("dev.langchain4j:langchain4j-mcp", dependencies.get(2).coordinate());
    assertEquals("profile[test-profile]/dependencies", dependencies.get(2).context());

    // 确保 exclusion 未被误识别为 dependency
    assertFalse(
        dependencies.stream()
            .anyMatch(
                d ->
                    d.coordinate().equals("dev.langchain4j:langchain4j-core")
                        && "dependencies".equals(d.context())));

    // 违规检测：pluginManagement 与 profile 中的 langchain 依赖均被驳回
    List<String> violations = findDisallowedLangChainDependencies(dependencies);
    assertEquals(2, violations.size());
    assertTrue(violations.stream().anyMatch(v -> v.contains("buildPluginManagement")));
    assertTrue(violations.stream().anyMatch(v -> v.contains("profile[test-profile]/dependencies")));
  }

  /**
   * 生产代码源码 import 守卫：全仓所有模块的 main 源码中，{@code dev.langchain4j.*} 只允许出现在 platform 的 MCP
   * 客户端适配器包（{@code platform/.../catalog/mcp/client/}）中。
   */
  @Test
  void productionSourcesImportLangChainOnlyInPlatformMcpClient() throws IOException {
    Path root = locateReactorRoot();
    List<Path> poms = enumerateReactorPoms(root);
    List<String> violations = new ArrayList<>();

    String allowedPrefix =
        "platform/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client/";

    for (Path pom : poms) {
      Path moduleDir = pom.getParent();
      Path srcMainJava = moduleDir.resolve("src/main/java");
      if (!Files.isDirectory(srcMainJava)) {
        continue;
      }
      try (Stream<Path> javaPaths = Files.walk(srcMainJava)) {
        List<Path> javaFiles = javaPaths.filter(p -> p.toString().endsWith(".java")).toList();
        for (Path javaFile : javaFiles) {
          String relativePath = root.relativize(javaFile).toString().replace('\\', '/');
          List<String> lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
          for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
              continue;
            }
            String imported = normalizeImport(trimmed);
            if (imported.startsWith("dev.langchain4j.")
                || imported.startsWith("io.github.langchain4j.")) {
              if (!relativePath.startsWith(allowedPrefix)) {
                violations.add(relativePath + ": " + trimmed);
              }
            }
          }
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        () ->
            "LangChain4j imports found outside allowed platform MCP client boundary ("
                + allowedPrefix
                + "):\n"
                + String.join("\n", violations));
  }

  record DeclaredDependency(
      String modulePath, String context, String groupId, String artifactId, String version) {
    String coordinate() {
      return groupId + ":" + artifactId;
    }

    String identity() {
      return modulePath + "|" + context + "|" + groupId + "|" + artifactId;
    }
  }

  static List<String> findDisallowedLangChainDependencies(List<DeclaredDependency> dependencies) {
    List<String> violations = new ArrayList<>();
    for (DeclaredDependency dep : dependencies) {
      if (!isLangChainDependency(dep)) {
        continue;
      }
      if (!isAllowedLangChainDependency(dep)) {
        violations.add(
            String.format(
                "disallowed LangChain4j dependency in %s (%s): %s:%s (LangChain4j is restricted to"
                    + " platform MCP adapter 'dev.langchain4j:langchain4j-mcp' /"
                    + " 'dev.langchain4j:langchain4j-core' and root 'dev.langchain4j:langchain4j-bom'"
                    + " version management)",
                dep.modulePath(), dep.context(), dep.groupId(), dep.artifactId()));
      }
    }
    return violations;
  }

  private static boolean isLangChainDependency(DeclaredDependency dep) {
    String groupId = dep.groupId();
    String artifactId = dep.artifactId();
    return "dev.langchain4j".equals(groupId)
        || "io.github.langchain4j".equals(groupId)
        || (artifactId != null && artifactId.startsWith("langchain4j-"));
  }

  private static boolean isAllowedLangChainDependency(DeclaredDependency dep) {
    if ("pom.xml".equals(dep.modulePath())
        && "dependencyManagement".equals(dep.context())
        && "dev.langchain4j".equals(dep.groupId())
        && "langchain4j-bom".equals(dep.artifactId())) {
      return true;
    }
    if ("platform/pom.xml".equals(dep.modulePath())
        && "dependencies".equals(dep.context())
        && "dev.langchain4j".equals(dep.groupId())
        && ("langchain4j-core".equals(dep.artifactId())
            || "langchain4j-mcp".equals(dep.artifactId()))) {
      return true;
    }
    return false;
  }

  private static Path locateReactorRoot() {
    Path start;
    try {
      var codeSource = PlatformArchitectureTest.class.getProtectionDomain().getCodeSource();
      if (codeSource != null && codeSource.getLocation() != null) {
        start = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
      } else {
        start = Path.of("").toAbsolutePath().normalize();
      }
    } catch (Exception ignored) {
      start = Path.of("").toAbsolutePath().normalize();
    }
    Path current = start;
    while (current != null) {
      Path pom = current.resolve("pom.xml");
      if (Files.isRegularFile(pom) && isRootReactorPom(pom)) {
        return current;
      }
      current = current.getParent();
    }
    current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path pom = current.resolve("pom.xml");
      if (Files.isRegularFile(pom) && isRootReactorPom(pom)) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("cannot locate reactor root from " + start);
  }

  private static boolean isRootReactorPom(Path pom) {
    try {
      String text = Files.readString(pom, StandardCharsets.UTF_8);
      return text.contains("<artifactId>kk-studio-parent</artifactId>")
          && text.contains("<modules>")
          && !text.contains("<parent>");
    } catch (IOException ignored) {
      return false;
    }
  }

  private static List<Path> enumerateReactorPoms(Path reactorRoot) {
    List<Path> poms = new ArrayList<>();
    Path rootPom = reactorRoot.resolve("pom.xml");
    if (!Files.isRegularFile(rootPom)) {
      throw new IllegalStateException("root pom.xml not found at " + reactorRoot);
    }
    poms.add(rootPom);
    collectSubmodulePoms(rootPom, reactorRoot, poms);
    return poms;
  }

  private static void collectSubmodulePoms(Path pomPath, Path moduleDir, List<Path> collected) {
    List<String> modules = extractModules(pomPath);
    for (String module : modules) {
      Path submoduleDir = moduleDir.resolve(module).normalize();
      Path submodulePom = submoduleDir.resolve("pom.xml").normalize();
      if (Files.isRegularFile(submodulePom)) {
        collected.add(submodulePom);
        collectSubmodulePoms(submodulePom, submoduleDir, collected);
      }
    }
  }

  private static List<String> extractModules(Path pomPath) {
    List<String> modules = new ArrayList<>();
    try (InputStream is = Files.newInputStream(pomPath)) {
      Document doc = parseXml(is);
      Element project = doc.getDocumentElement();
      List<Element> modulesElements = childElements(project, "modules");
      for (Element modulesEl : modulesElements) {
        List<Element> moduleList = childElements(modulesEl, "module");
        for (Element moduleEl : moduleList) {
          String moduleName = moduleEl.getTextContent().trim();
          if (!moduleName.isEmpty()) {
            modules.add(moduleName);
          }
        }
      }
    } catch (Exception error) {
      throw new IllegalStateException("cannot parse modules from " + pomPath, error);
    }
    return modules;
  }

  private static List<DeclaredDependency> collectDeclaredDependencies(
      Path pomPath, Path reactorRoot) {
    List<DeclaredDependency> result = new ArrayList<>();
    String relativePom = reactorRoot.relativize(pomPath).toString().replace('\\', '/');
    try (InputStream is = Files.newInputStream(pomPath)) {
      Document doc = parseXml(is);
      NodeList dependencyNodes = doc.getElementsByTagName("dependency");
      for (int i = 0; i < dependencyNodes.getLength(); i++) {
        Node node = dependencyNodes.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
          Element dep = (Element) node;
          String context = classifyContext(dep);
          String groupId = childText(dep, "groupId");
          String artifactId = childText(dep, "artifactId");
          String version = childText(dep, "version");
          if (groupId != null && artifactId != null) {
            result.add(new DeclaredDependency(relativePom, context, groupId, artifactId, version));
          }
        }
      }
    } catch (Exception error) {
      throw new IllegalStateException("cannot parse dependencies from " + pomPath, error);
    }
    return result;
  }

  private static String classifyContext(Element depElement) {
    Node current = depElement.getParentNode();
    List<String> tags = new ArrayList<>();
    String profileId = null;
    while (current != null && current.getNodeType() == Node.ELEMENT_NODE) {
      Element el = (Element) current;
      String tag = el.getTagName();
      if ("profile".equals(tag)) {
        String id = childText(el, "id");
        profileId = (id != null && !id.isBlank()) ? id : "anonymous";
      }
      tags.add(tag);
      current = current.getParentNode();
    }

    boolean inProfile = tags.contains("profile");
    boolean inDepManagement = tags.contains("dependencyManagement");
    boolean inPluginManagement = tags.contains("pluginManagement");
    boolean inPlugin = tags.contains("plugin");

    String baseContext;
    if (inPluginManagement) {
      baseContext = "buildPluginManagement";
    } else if (inPlugin) {
      baseContext = "buildPlugins";
    } else if (inDepManagement) {
      baseContext = "dependencyManagement";
    } else if (tags.contains("dependencies")) {
      baseContext = "dependencies";
    } else {
      baseContext = "other";
    }

    if (inProfile) {
      return "profile[" + profileId + "]/" + baseContext;
    }
    return baseContext;
  }

  private static Document parseXml(InputStream is) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    factory.setValidating(false);
    setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
    setFeatureIfSupported(
        factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
    setFeatureIfSupported(
        factory, "http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(is);
  }

  private static void setFeatureIfSupported(
      DocumentBuilderFactory factory, String feature, boolean value) {
    try {
      factory.setFeature(feature, value);
    } catch (Exception ignored) {
      // 当前 XML 解析器不支持该特性时安全忽略
    }
  }

  private static List<Element> childElements(Element parent, String tagName) {
    List<Element> result = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        Element el = (Element) node;
        if (tagName.equals(el.getTagName())) {
          result.add(el);
        }
      }
    }
    return result;
  }

  private static String childText(Element parent, String tagName) {
    List<Element> children = childElements(parent, tagName);
    if (children.isEmpty()) {
      return null;
    }
    return children.getFirst().getTextContent().trim();
  }
}
