package fun.fengwk.kkstudio.canvas;

import fun.fengwk.kkstudio.canvas.CanvasConflict.NodeGroup;
import fun.fengwk.kkstudio.canvas.CanvasConflict.Target;
import fun.fengwk.kkstudio.canvas.CanvasMutation.DeleteFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasMutation.DeleteGroup;
import fun.fengwk.kkstudio.canvas.CanvasMutation.DeleteNode;
import fun.fengwk.kkstudio.canvas.CanvasMutation.DeleteResource;
import fun.fengwk.kkstudio.canvas.CanvasMutation.DetachResource;
import fun.fengwk.kkstudio.canvas.CanvasMutation.InsertGroup;
import fun.fengwk.kkstudio.canvas.CanvasMutation.InsertNode;
import fun.fengwk.kkstudio.canvas.CanvasMutation.ReleaseNodePins;
import fun.fengwk.kkstudio.canvas.CanvasMutation.UpdateGroup;
import fun.fengwk.kkstudio.canvas.CanvasMutation.UpdateNode;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 单次命令批的可变规划状态：按命令顺序推进节点、分组、资源与冲突，最终产出持久化步骤与 patch。
 *
 * <p>只在一次 {@link CanvasCommandPlanner#plan} 调用内存在。每条命令只比较自己语义组的前置条件，不同节点、同节点不同语义组互不覆盖；
 * 结构冲突提前抛出，语义组过期累积为冲突，存在冲突时整批不生效。
 */
final class CanvasCommandPlanning {

  private final UUID canvasId;
  private final Set<UUID> pinnedResourceIds;
  private final CanvasResourceSlots resourceSlots;
  private final Map<UUID, NodeState> nodes = new LinkedHashMap<>();
  private final Map<UUID, GroupState> groups = new LinkedHashMap<>();
  private final Map<UUID, CanvasResource> resources = new LinkedHashMap<>();
  private final List<CanvasMutation> mutations = new ArrayList<>();
  private final List<CanvasConflict> conflicts = new ArrayList<>();
  private final Set<UUID> touchedNodes = new LinkedHashSet<>();
  private final Set<UUID> removedNodes = new LinkedHashSet<>();
  private final Set<UUID> touchedGroups = new LinkedHashSet<>();
  private final Set<UUID> removedGroups = new LinkedHashSet<>();

  CanvasCommandPlanning(CanvasGraph graph, CanvasResourceSlots resourceSlots) {
    this.canvasId = graph.canvasId();
    this.pinnedResourceIds = Set.copyOf(graph.pinnedResourceIds());
    this.resourceSlots = resourceSlots;
    for (CanvasResourceNode node : graph.nodes()) {
      nodes.put(node.id(), new NodeState(node));
      for (CanvasResource resource : node.resources()) {
        resources.put(resource.id(), resource);
      }
    }
    for (CanvasGroup group : graph.groups()) {
      groups.put(group.id(), new GroupState(group));
    }
  }

  void apply(CanvasCommand command) {
    switch (command) {
      case CanvasCommand.CreateNode createNode -> createNode(createNode);
      case CanvasCommand.RenameNode renameNode -> renameNode(renameNode);
      case CanvasCommand.SetNodeResources setNodeResources -> setNodeResources(setNodeResources);
      case CanvasCommand.SetNodeFunction setNodeFunction -> setNodeFunction(setNodeFunction);
      case CanvasCommand.SetNodeGroup setNodeGroup -> setNodeGroup(setNodeGroup);
      case CanvasCommand.DeleteNode deleteNode -> deleteNode(deleteNode);
      case CanvasCommand.UpdateNodeTransform updateNodeTransform -> updateNodeTransform(
          updateNodeTransform);
      case CanvasCommand.CreateGroup createGroup -> createGroup(createGroup);
      case CanvasCommand.RenameGroup renameGroup -> renameGroup(renameGroup);
      case CanvasCommand.UpdateGroupTransform updateGroupTransform -> updateGroupTransform(
          updateGroupTransform);
      case CanvasCommand.DeleteGroup deleteGroup -> deleteGroup(deleteGroup);
    }
  }

  private void createNode(CanvasCommand.CreateNode command) {
    UUID nodeId = command.nodeId();
    if (removedNodes.contains(nodeId)) {
      throw DomainException.validation(
          "canvas", "node id deleted in this batch must not be reused by create");
    }
    if (nodes.containsKey(nodeId)) {
      conflicts.add(new CanvasConflict.TargetPresent(nodeId, Target.NODE));
      return;
    }
    String name = command.name();
    requireUniqueName(nodeId, name);
    NodeState node = new NodeState(nodeId, name, command.transform());
    nodes.put(nodeId, node);
    touchedNodes.add(nodeId);
    mutations.add(new InsertNode(nodeId, name, command.transform(), null, null));
    applyResources(node, command.resources());
  }

  private void renameNode(CanvasCommand.RenameNode command) {
    NodeState node = nodes.get(command.nodeId());
    if (node == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.nodeId(), Target.NODE));
      return;
    }
    if (!node.name.equals(command.expectedName())) {
      conflicts.add(staleNode(node, NodeGroup.NAME));
      return;
    }
    if (node.name.equals(command.name())) {
      return;
    }
    requireUniqueName(node.id, command.name());
    node.name = command.name();
    touchedNodes.add(node.id);
    mutations.add(updateNode(node));
  }

  private void setNodeResources(CanvasCommand.SetNodeResources command) {
    NodeState node = nodes.get(command.nodeId());
    if (node == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.nodeId(), Target.NODE));
      return;
    }
    if (requireIdleNode(node)) {
      return;
    }
    if (!node.resourceIds.equals(command.expectedResourceIds())) {
      conflicts.add(staleNode(node, NodeGroup.RESOURCES));
      return;
    }
    if (applyResources(node, command.resources())) {
      touchedNodes.add(node.id);
    }
  }

  private void setNodeFunction(CanvasCommand.SetNodeFunction command) {
    NodeState node = nodes.get(command.nodeId());
    if (node == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.nodeId(), Target.NODE));
      return;
    }
    if (requireIdleNode(node)) {
      return;
    }
    if (!Objects.equals(node.function, command.expectedFunction())) {
      conflicts.add(staleNode(node, NodeGroup.FUNCTION));
      return;
    }
    if (Objects.equals(node.function, command.function())) {
      return;
    }
    node.function = command.function();
    touchedNodes.add(node.id);
    mutations.add(updateNode(node));
  }

  private void setNodeGroup(CanvasCommand.SetNodeGroup command) {
    NodeState node = nodes.get(command.nodeId());
    if (node == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.nodeId(), Target.NODE));
      return;
    }
    if (!Objects.equals(node.groupId, command.expectedGroupId())) {
      conflicts.add(staleNode(node, NodeGroup.MEMBERSHIP));
      return;
    }
    if (command.groupId() != null && !groups.containsKey(command.groupId())) {
      conflicts.add(new CanvasConflict.TargetMissing(command.groupId(), Target.GROUP));
      return;
    }
    if (Objects.equals(node.groupId, command.groupId())) {
      return;
    }
    node.groupId = command.groupId();
    touchedNodes.add(node.id);
    mutations.add(updateNode(node));
  }

  private void deleteNode(CanvasCommand.DeleteNode command) {
    NodeState node = nodes.get(command.nodeId());
    if (node == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.nodeId(), Target.NODE));
      return;
    }
    if (requireIdleNode(node)) {
      return;
    }
    if (!node.resourceIds.equals(command.expectedResourceIds())) {
      conflicts.add(staleNode(node, NodeGroup.RESOURCES));
      return;
    }
    if (!Objects.equals(node.function, command.expectedFunction())) {
      conflicts.add(staleNode(node, NodeGroup.FUNCTION));
      return;
    }
    // 顺序固定：先释放本节点资源槽位，再释放 Run pin（无 owner 且无 pin 的历史资源随之回收），
    // 最后删除 Run 行与节点行，满足 pin→run→resource→node 的外键限制。
    discardResources(node, node.resourceIds);
    mutations.add(new ReleaseNodePins(node.id));
    mutations.add(new DeleteFunctionRun(node.id));
    mutations.add(new DeleteNode(node.id));
    nodes.remove(node.id);
    removedNodes.add(node.id);
    touchedNodes.add(node.id);
  }

  private void updateNodeTransform(CanvasCommand.UpdateNodeTransform command) {
    NodeState node = nodes.get(command.nodeId());
    if (node == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.nodeId(), Target.NODE));
      return;
    }
    if (command.expectedTransform() != null
        && !node.transform.equals(command.expectedTransform())) {
      conflicts.add(staleNode(node, NodeGroup.LAYOUT));
      return;
    }
    if (node.transform.equals(command.transform())) {
      return;
    }
    node.transform = command.transform();
    touchedNodes.add(node.id);
    mutations.add(updateNode(node));
  }

  private void createGroup(CanvasCommand.CreateGroup command) {
    UUID groupId = command.groupId();
    if (removedGroups.contains(groupId)) {
      throw DomainException.validation(
          "canvas", "group id deleted in this batch must not be reused by create");
    }
    if (groups.containsKey(groupId)) {
      conflicts.add(new CanvasConflict.TargetPresent(groupId, Target.GROUP));
      return;
    }
    CanvasGroup group = new CanvasGroup(groupId, canvasId, command.title(), command.transform());
    groups.put(groupId, new GroupState(group));
    touchedGroups.add(groupId);
    mutations.add(new InsertGroup(group));
  }

  private void renameGroup(CanvasCommand.RenameGroup command) {
    GroupState group = groups.get(command.groupId());
    if (group == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.groupId(), Target.GROUP));
      return;
    }
    if (!group.group.title().equals(command.expectedTitle())) {
      conflicts.add(new CanvasConflict.StaleGroup(command.groupId(), group.group));
      return;
    }
    if (group.group.title().equals(command.title())) {
      return;
    }
    group.group =
        new CanvasGroup(group.group.id(), canvasId, command.title(), group.group.transform());
    touchedGroups.add(group.group.id());
    mutations.add(new UpdateGroup(group.group));
  }

  private void updateGroupTransform(CanvasCommand.UpdateGroupTransform command) {
    GroupState group = groups.get(command.groupId());
    if (group == null) {
      conflicts.add(new CanvasConflict.TargetMissing(command.groupId(), Target.GROUP));
      return;
    }
    if (command.expectedTransform() != null
        && !group.group.transform().equals(command.expectedTransform())) {
      conflicts.add(new CanvasConflict.StaleGroup(command.groupId(), group.group));
      return;
    }
    if (group.group.transform().equals(command.transform())) {
      return;
    }
    group.group =
        new CanvasGroup(group.group.id(), canvasId, group.group.title(), command.transform());
    touchedGroups.add(group.group.id());
    mutations.add(new UpdateGroup(group.group));
  }

  private void deleteGroup(CanvasCommand.DeleteGroup command) {
    UUID groupId = command.groupId();
    if (!groups.containsKey(groupId)) {
      conflicts.add(new CanvasConflict.TargetMissing(groupId, Target.GROUP));
      return;
    }
    List<UUID> members = membersOf(groupId);
    Set<UUID> expected = new LinkedHashSet<>(command.expectedMemberNodeIds());
    if (!expected.equals(new LinkedHashSet<>(members))) {
      reportMembershipConflicts(members, expected);
      return;
    }
    for (UUID memberId : members) {
      NodeState member = nodes.get(memberId);
      member.groupId = null;
      touchedNodes.add(memberId);
      mutations.add(updateNode(member));
    }
    mutations.add(new DeleteGroup(groupId));
    groups.remove(groupId);
    removedGroups.add(groupId);
    touchedGroups.add(groupId);
  }

  /** 成员集合不一致时，对仍存在于画布中的差异节点逐个报告成员组过期。 */
  private void reportMembershipConflicts(List<UUID> members, Set<UUID> expected) {
    Set<UUID> mismatched = new LinkedHashSet<>(members);
    mismatched.addAll(expected);
    List<UUID> reported = new ArrayList<>();
    for (UUID memberId : mismatched) {
      if (nodes.containsKey(memberId)) {
        reported.add(memberId);
      }
    }
    for (UUID nodeId : reported) {
      conflicts.add(staleNode(nodes.get(nodeId), NodeGroup.MEMBERSHIP));
    }
  }

  /**
   * 落地节点资源数组：先释放被移除的槽位，再把移动过的保留资源脱离并按新顺序重挂，最后插入新内容行。
   *
   * <p>返回是否真的产生变化，使「资源列表与基线完全一致」的批不推进 revision。
   */
  private boolean applyResources(NodeState node, List<CanvasResourceInput> inputs) {
    if (inputs.size() > CanvasCommandPlanner.MAX_RESOURCES_PER_NODE) {
      throw DomainException.validation(
          "canvas",
          "node must not hold more than "
              + CanvasCommandPlanner.MAX_RESOURCES_PER_NODE
              + " resources");
    }
    CanvasResourceSlots.Plan plan =
        resourceSlots.plan(
            canvasId, node.id, node.resourceIds, inputs, resources, pinnedResourceIds);
    mutations.addAll(plan.mutations());
    node.resourceIds = plan.slots().stream().map(CanvasResource::id).toList();
    return plan.changed();
  }

  /** 删除节点时清理其当前资源：有 pin 的历史行只解除挂接，其余删除行并释放 Blob 引用。 */
  private void discardResources(NodeState node, List<UUID> resourceIds) {
    for (UUID resourceId : resourceIds) {
      CanvasResource resource = requireResource(resourceId);
      if (pinnedResourceIds.contains(resourceId)) {
        mutations.add(new DetachResource(resourceId, node.id));
        resources.put(resourceId, resource.withoutOwner());
      } else {
        mutations.add(new DeleteResource(resourceId));
        resources.remove(resourceId);
      }
    }
    node.resourceIds = List.of();
  }

  /** 批内自引用、悬空引用与删除仍在被引用节点的检查。 */
  void validate() {
    for (NodeState node : nodes.values()) {
      if (node.function == null) {
        continue;
      }
      for (CanvasResourceReference reference : node.function.references()) {
        if (reference.nodeId().equals(node.id)) {
          throw DomainException.validation("canvas", "function must not reference its own node");
        }
        if (!nodes.containsKey(reference.nodeId()) && !removedNodes.contains(reference.nodeId())) {
          throw DomainException.validation(
              "canvas", "referenced source node must exist in the same canvas");
        }
      }
    }
    for (UUID removedNodeId : removedNodes) {
      List<UUID> referencing = new ArrayList<>();
      for (NodeState node : nodes.values()) {
        if (node.function == null) {
          continue;
        }
        for (CanvasResourceReference reference : node.function.references()) {
          if (reference.nodeId().equals(removedNodeId)) {
            referencing.add(node.id);
            break;
          }
        }
      }
      if (!referencing.isEmpty()) {
        referencing.sort(Comparator.naturalOrder());
        conflicts.add(new CanvasConflict.NodeReferenced(removedNodeId, referencing));
      }
    }
  }

  CanvasCommandPlan toPlan() {
    return new CanvasCommandPlan(mutations, nodePatches(), groupPatches(), conflicts);
  }

  private List<CanvasNodePatch> nodePatches() {
    List<CanvasNodePatch> patches = new ArrayList<>();
    for (UUID nodeId : touchedNodes.stream().sorted().toList()) {
      if (removedNodes.contains(nodeId)) {
        patches.add(new CanvasNodePatch.Remove(nodeId));
        continue;
      }
      NodeState node = nodes.get(nodeId);
      if (node != null) {
        patches.add(new CanvasNodePatch.Upsert(project(node)));
      }
    }
    return List.copyOf(patches);
  }

  private List<CanvasGroupPatch> groupPatches() {
    List<CanvasGroupPatch> patches = new ArrayList<>();
    for (UUID groupId : touchedGroups.stream().sorted().toList()) {
      GroupState group = groups.get(groupId);
      if (removedGroups.contains(groupId) || group == null) {
        patches.add(new CanvasGroupPatch.Remove(groupId));
        continue;
      }
      patches.add(new CanvasGroupPatch.Upsert(group.group));
    }
    return List.copyOf(patches);
  }

  private CanvasResourceNode project(NodeState node) {
    List<CanvasResource> owned = new ArrayList<>(node.resourceIds.size());
    for (UUID resourceId : node.resourceIds) {
      owned.add(requireResource(resourceId));
    }
    return new CanvasResourceNode(
        node.id, canvasId, node.name, node.transform, node.groupId, owned, node.function, node.run);
  }

  private CanvasResource requireResource(UUID resourceId) {
    CanvasResource resource = resources.get(resourceId);
    if (resource == null) {
      throw new IllegalStateException("canvas resource disappeared during planning");
    }
    return resource;
  }

  private CanvasConflict.StaleNode staleNode(NodeState node, NodeGroup group) {
    return new CanvasConflict.StaleNode(node.id, group, project(node));
  }

  /** 活跃执行或结果待核查期间，禁止手工换输出、修改函数配置或删除节点。 */
  private boolean requireIdleNode(NodeState node) {
    if (node.run == null) {
      return false;
    }
    if (node.run.status() == CanvasFunctionRunStatus.READY
        || node.run.status() == CanvasFunctionRunStatus.RUNNING
        || node.run.status() == CanvasFunctionRunStatus.UNKNOWN) {
      conflicts.add(new CanvasConflict.NodeRunning(node.id, node.run));
      return true;
    }
    return false;
  }

  private void requireUniqueName(UUID nodeId, String name) {
    String key = CanvasNames.key(name);
    for (NodeState node : nodes.values()) {
      if (!node.id.equals(nodeId) && CanvasNames.key(node.name).equals(key)) {
        throw DomainException.validation(
            "canvas", "node name is already used in this canvas: " + name);
      }
    }
  }

  private List<UUID> membersOf(UUID groupId) {
    List<UUID> members = new ArrayList<>();
    for (NodeState node : nodes.values()) {
      if (groupId.equals(node.groupId)) {
        members.add(node.id);
      }
    }
    members.sort(Comparator.naturalOrder());
    return members;
  }

  private CanvasMutation.UpdateNode updateNode(NodeState node) {
    return new UpdateNode(node.id, node.name, node.transform, node.groupId, node.function);
  }

  /** 节点规划状态。 */
  private static final class NodeState {

    private final UUID id;
    private final CanvasFunctionRun run;
    private String name;
    private CanvasTransform transform;
    private UUID groupId;
    private CanvasFunction function;
    private List<UUID> resourceIds;

    private NodeState(CanvasResourceNode node) {
      id = node.id();
      run = node.run();
      name = node.name();
      transform = node.transform();
      groupId = node.groupId();
      function = node.function();
      resourceIds = node.resources().stream().map(CanvasResource::id).toList();
    }

    private NodeState(UUID id, String name, CanvasTransform transform) {
      this.id = id;
      this.run = null;
      this.name = name;
      this.transform = transform;
      this.resourceIds = new ArrayList<>();
    }
  }

  /** 分组规划状态。 */
  private static final class GroupState {

    private CanvasGroup group;

    private GroupState(CanvasGroup group) {
      this.group = group;
    }
  }
}
