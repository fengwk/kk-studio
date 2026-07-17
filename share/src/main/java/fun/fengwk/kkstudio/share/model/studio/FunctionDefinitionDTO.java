package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FunctionDefinitionDTO {
  private String functionId;
  private String version;
  private String scope;
  private String workspaceId;
  private String displayName;
  private String description;
  private List<String> inputKeys = new ArrayList<>();
  private List<String> outputChannelKeys = new ArrayList<>();
  private String configSchemaJson;
  private boolean cacheable;
}
