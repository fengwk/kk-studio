package fun.fengwk.kkstudio.ide.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Data
public class EnvironmentIdeDirectoryListingDTO {
  private String path;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String parentPath;

  private List<Entry> children;
  private boolean truncated;

  @Data
  public static class Entry {
    private String name;
    private String path;
  }
}
