package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.daemon.EnvironmentDirectoryBrowser;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Environment Root 下的单层目录浏览 capability。 */
public final class ListDirectoryCapability extends AbstractCodingCapability {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final EnvironmentDirectoryBrowser browser;

  public ListDirectoryCapability(CodingToolsConfig config, ExecutorService executor) {
    this(config, executor, new EnvironmentDirectoryBrowser(config.environmentRoot()));
  }

  public ListDirectoryCapability(
      CodingToolsConfig config, ExecutorService executor, EnvironmentDirectoryBrowser browser) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_LIST_DIRECTORY));
    this.browser = Objects.requireNonNull(browser, "browser");
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String path = string(args, "path");
    EnvironmentDirectoryListing listing = browser.list(path);
    String json = OBJECT_MAPPER.writeValueAsString(listing);
    return EnvironmentCapabilityResult.json(request.call().id(), json);
  }
}
