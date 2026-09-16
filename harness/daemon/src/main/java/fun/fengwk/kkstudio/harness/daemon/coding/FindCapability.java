package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** 使用 Java NIO 查找遵守分层 {@code .gitignore} 的 environment 文件。 */
public final class FindCapability extends AbstractCodingCapability {

  static final int DEFAULT_TIMEOUT_SECONDS = 15;
  static final int MAX_TIMEOUT_SECONDS = 3600;

  public FindCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_FIND));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.existing(string(args, "path"), workdir);
    int limit = optionalPositiveInt(args, "limit", 1000, 100_000);
    Duration timeout = effectiveSearchTimeout(request.effectiveTimeout(), args);
    SearchControl control = SearchControl.start(timeout, execution, "find");
    String sourcePattern = string(args, "pattern");
    boolean pathPattern = sourcePattern.indexOf('/') >= 0;
    control.check();
    GlobPattern pattern = GlobPattern.compile(sourcePattern);
    control.check();

    List<String> matchedPaths = new ArrayList<>();
    SearchFiles.walk(
        workdir,
        path,
        control,
        file -> {
          control.check();
          String searchRelative = SearchFiles.toPosix(path.relativize(file));
          String basename = file.getFileName().toString();
          if (pattern.matches(pathPattern ? searchRelative : basename)) {
            matchedPaths.add(SearchFiles.toPosix(workdir.relativize(file)));
            if (matchedPaths.size() > limit) {
              return false;
            }
          }
          return true;
        });

    if (matchedPaths.isEmpty()) {
      return success(request.call().id(), "No files found matching pattern");
    }

    boolean limitReached = matchedPaths.size() > limit;
    int displayCount = Math.min(limit, matchedPaths.size());

    try (OutputSpool spool = new OutputSpool(config.textOutputStore(), request.call().id())) {
      for (int i = 0; i < displayCount; i++) {
        if (i > 0) {
          spool.write((int) '\n');
        }
        spool.write(matchedPaths.get(i).getBytes(StandardCharsets.UTF_8));
      }
      if (limitReached) {
        spool.write(
            ("\n\n[" + limit + " results limit reached. Refine the pattern or raise limit.]")
                .getBytes(StandardCharsets.UTF_8));
      }
      return spool.finish(false);
    }
  }

  static Duration effectiveSearchTimeout(Duration invocationTimeout, JsonNode args) {
    Objects.requireNonNull(invocationTimeout, "invocationTimeout");
    Duration outerTimeout = invocationTimeout.isZero() ? Duration.ofHours(1) : invocationTimeout;
    if (!args.has("timeout_seconds")) {
      return outerTimeout;
    }
    Duration requestedTimeout =
        Duration.ofSeconds(optionalPositiveInt(args, "timeout_seconds", 1, MAX_TIMEOUT_SECONDS));
    return outerTimeout.compareTo(requestedTimeout) > 0 ? requestedTimeout : outerTimeout;
  }
}
