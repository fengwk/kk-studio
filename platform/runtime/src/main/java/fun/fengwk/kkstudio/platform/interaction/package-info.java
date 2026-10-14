/**
 * 统一人工交互：把 Harness 两种等待状态（问卷 / 审批）投影为带产品来源的待处理列表，并提供唯一的人工写入口。
 *
 * <p>交互事实只来自 Harness Invocation；本包不复制待办、不写产品表，只用 Chat 归属边与 Issue+Agent 线程绑定把 Thread/Session 解析为产品
 * owner，从而组装 Pane 跳转所需来源，并在写入前按 {@code Project SHARE -> Issue UPDATE -> Harness Thread} 锁序串行化。
 * 产品归属无法解析的 Thread（例如内部委派）不对外暴露，也不能经本包写入。
 */
package fun.fengwk.kkstudio.platform.interaction;
