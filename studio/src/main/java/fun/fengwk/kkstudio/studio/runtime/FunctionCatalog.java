package fun.fengwk.kkstudio.studio.runtime;

import fun.fengwk.kkstudio.studio.model.FunctionDefinition;
import fun.fengwk.kkstudio.studio.model.FunctionRef;

import java.util.List;
import java.util.Optional;

public interface FunctionCatalog {

  Optional<FunctionDefinition> find(FunctionRef ref);

  List<FunctionDefinition> listVisible(long workspaceId);
}
