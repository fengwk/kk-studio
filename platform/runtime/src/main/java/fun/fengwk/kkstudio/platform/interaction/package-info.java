/**
 * 统一人工交互：把 Harness 等待事实投影为待处理列表，并提供人工输入与审批的事务写入口。
 *
 * <p>交互事实来自 Harness；本包不复制待办或写产品表。查询按 Thread 所属 Session 的工作区归属授权，未登记的插件后台任务仅管理员可见。
 */
package fun.fengwk.kkstudio.platform.interaction;
