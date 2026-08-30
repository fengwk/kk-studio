package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.daemon.EnvironmentDirectoryBrowser;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
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
    try {
      EnvironmentDirectoryListing listing = browser.list(path);
      String json = OBJECT_MAPPER.writeValueAsString(listing);
      return EnvironmentCapabilityResult.json(request.call().id(), json);
    } catch (NoSuchFileException error) {
      return EnvironmentCapabilityResult.error(
          request.call().id(), "directory does not exist: " + path);
    } catch (NotDirectoryException error) {
      return EnvironmentCapabilityResult.error(
          request.call().id(), "path is not a directory: " + path);
    } catch (IOException error) {
      return EnvironmentCapabilityResult.error(
          request.call().id(), "cannot list directory: " + path);
    }
  }
}
