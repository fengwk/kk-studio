package fun.fengwk.kkstudio.web.studio;

import fun.fengwk.kkstudio.share.model.studio.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.model.studio.CanvasLinkDTO;
import fun.fengwk.kkstudio.share.model.studio.CanvasNodeDTO;
import fun.fengwk.kkstudio.share.model.studio.CanvasResourceReferenceDTO;
import fun.fengwk.kkstudio.share.model.studio.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.model.studio.FunctionDefinitionDTO;
import fun.fengwk.kkstudio.share.model.studio.FunctionRunDTO;
import fun.fengwk.kkstudio.share.model.studio.WorkflowDocumentDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceReference;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.model.FunctionDefinition;
import fun.fengwk.kkstudio.studio.model.FunctionRun;
import fun.fengwk.kkstudio.studio.workflow.WorkflowDocument;

import java.util.stream.Collectors;

/** Web-layer mapper from studio domain objects to share DTOs. */
public final class StudioWebMapper {

  private StudioWebMapper() {}

  public static CanvasDocumentDTO toDto(CanvasDocument document) {
    CanvasDocumentDTO dto = new CanvasDocumentDTO();
    dto.setId(Long.toString(document.id()));
    dto.setWorkspaceId(Long.toString(document.workspaceId()));
    dto.setTitle(document.title());
    dto.setSchemaVersion(document.schemaVersion());
    dto.setRevision(Long.toString(document.revision()));
    dto.setLifecycle(document.lifecycle().name());
    dto.setHomeViewportJson(document.homeViewportJson());
    return dto;
  }

  public static CanvasSnapshotDTO toDto(CanvasSnapshot snapshot) {
    CanvasSnapshotDTO dto = new CanvasSnapshotDTO();
    dto.setDocument(toDto(snapshot.document()));
    dto.setNodes(
        snapshot.nodes().stream().map(StudioWebMapper::toDto).collect(Collectors.toList()));
    dto.setLinks(
        snapshot.links().stream().map(StudioWebMapper::toDto).collect(Collectors.toList()));
    dto.setReferences(
        snapshot.references().stream().map(StudioWebMapper::toDto).collect(Collectors.toList()));
    return dto;
  }

  public static CanvasNodeDTO toDto(CanvasNode node) {
    CanvasNodeDTO dto = new CanvasNodeDTO();
    dto.setId(Long.toString(node.id()));
    dto.setCanvasId(Long.toString(node.canvasId()));
    dto.setKind(node.kind().name());
    dto.setNodeType(node.nodeType());
    dto.setNodeTypeVersion(node.nodeTypeVersion());
    dto.setName(node.name());
    dto.setParentGroupId(node.parentGroupId() == null ? null : Long.toString(node.parentGroupId()));
    dto.setX(node.transform().x());
    dto.setY(node.transform().y());
    dto.setWidth(node.transform().width());
    dto.setHeight(node.transform().height());
    dto.setRotation(node.transform().rotation());
    dto.setZIndex(Long.toString(node.zIndex()));
    dto.setLocked(node.locked());
    dto.setHidden(node.hidden());
    dto.setValidity(node.validity().name());
    dto.setRevision(Long.toString(node.revision()));
    dto.setDataJson(node.dataJson());
    return dto;
  }

  public static CanvasLinkDTO toDto(CanvasLink link) {
    CanvasLinkDTO dto = new CanvasLinkDTO();
    dto.setId(Long.toString(link.id()));
    dto.setCanvasId(Long.toString(link.canvasId()));
    dto.setSourceNodeId(Long.toString(link.sourceNodeId()));
    dto.setTargetNodeId(Long.toString(link.targetNodeId()));
    dto.setRevision(Long.toString(link.revision()));
    return dto;
  }

  public static CanvasResourceReferenceDTO toDto(CanvasResourceReference reference) {
    CanvasResourceReferenceDTO dto = new CanvasResourceReferenceDTO();
    dto.setId(Long.toString(reference.id()));
    dto.setCanvasId(Long.toString(reference.canvasId()));
    dto.setTargetNodeId(Long.toString(reference.targetNodeId()));
    dto.setTargetPath(reference.targetPath());
    dto.setVisibilityLinkId(Long.toString(reference.visibilityLinkId()));
    dto.setDependencySourceNodeId(Long.toString(reference.dependencySourceNodeId()));
    dto.setSelectorType(reference.selector().getClass().getSimpleName());
    dto.setSelectorJson(reference.selector().toString());
    dto.setRevision(Long.toString(reference.revision()));
    return dto;
  }

  public static FunctionDefinitionDTO toDto(FunctionDefinition definition) {
    FunctionDefinitionDTO dto = new FunctionDefinitionDTO();
    dto.setFunctionId(definition.ref().functionId());
    dto.setVersion(definition.ref().version());
    dto.setScope(definition.scope().name());
    dto.setWorkspaceId(
        definition.workspaceId() == null ? null : Long.toString(definition.workspaceId()));
    dto.setDisplayName(definition.displayName());
    dto.setDescription(definition.description());
    dto.setInputKeys(definition.inputKeys());
    dto.setOutputChannelKeys(definition.outputChannelKeys());
    dto.setConfigSchemaJson(definition.configSchemaJson());
    dto.setCacheable(definition.cacheable());
    return dto;
  }

  public static FunctionRunDTO toDto(FunctionRun run) {
    FunctionRunDTO dto = new FunctionRunDTO();
    dto.setId(Long.toString(run.id()));
    dto.setWorkspaceId(Long.toString(run.workspaceId()));
    dto.setCanvasId(run.canvasId() == null ? null : Long.toString(run.canvasId()));
    dto.setFunctionNodeId(
        run.functionNodeId() == null ? null : Long.toString(run.functionNodeId()));
    dto.setFunctionId(run.functionRef().functionId());
    dto.setFunctionVersion(run.functionRef().version());
    dto.setStatus(run.status().name());
    dto.setAttempt(Long.toString(run.attempt()));
    dto.setRetryOfRunId(run.retryOfRunId() == null ? null : Long.toString(run.retryOfRunId()));
    dto.setIdempotencyKey(run.idempotencyKey());
    dto.setRevision(Long.toString(run.revision()));
    return dto;
  }

  public static WorkflowDocumentDTO toDto(WorkflowDocument document) {
    WorkflowDocumentDTO dto = new WorkflowDocumentDTO();
    dto.setId(Long.toString(document.id()));
    dto.setWorkspaceId(Long.toString(document.workspaceId()));
    dto.setName(document.name());
    dto.setLifecycle(document.lifecycle().name());
    dto.setPublishedVersionId(
        document.publishedVersionId() == null
            ? null
            : Long.toString(document.publishedVersionId()));
    dto.setRevision(Long.toString(document.revision()));
    return dto;
  }
}
