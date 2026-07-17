package fun.fengwk.kkstudio.studio.workflow;

import java.util.List;
import java.util.Optional;

public interface WorkflowQueryService {

  Optional<WorkflowDocument> findDocument(long workflowId);

  List<WorkflowDocument> listDocuments(long workspaceId);

  Optional<WorkflowVersion> findVersion(long workflowVersionId);
}
