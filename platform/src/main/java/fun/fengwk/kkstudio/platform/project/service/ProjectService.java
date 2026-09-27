package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.Project;

import java.util.List;
import java.util.UUID;

/**
 * Project 配置用例：workflow 整体保存、严格校验与版本 CAS。
 *
 * <p>workflow 是唯一事实源；影响执行的结构、指令或职责绑定调整要求项目无活动主 Run。归档不删除历史与资源。
 */
public interface ProjectService {

  /** 创建项目：生成 INIT→WORK→DONE 的默认工作流（WORK 为无 Agent 的人工阶段）。 */
  Project createProject(String title, String description, boolean yoloEnabled);

  Project getProject(UUID projectId);

  List<Project> listProjects(boolean archived);

  /** 以版本 CAS 整体替换 workflow 配置：严格解码 / 校验保留值、唯一编码、边、可达性与引用。 */
  Project updateWorkflow(UUID projectId, long expectedVersion, String workflowJson);

  /** 以版本 CAS 更新 YOLO 执行策略；接受与派发按当前策略对齐 Thread 设置。 */
  Project updateYolo(UUID projectId, long expectedVersion, boolean yoloEnabled);

  /** 归档项目：归档后禁止执行，不删除历史与资源。 */
  Project archiveProject(UUID projectId, long expectedVersion);
}
