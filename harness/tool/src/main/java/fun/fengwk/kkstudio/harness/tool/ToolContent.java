package fun.fengwk.kkstudio.harness.tool;

/** 工具结果中可保存或流式传输的内容单元。 */
public sealed interface ToolContent
    permits TextToolContent, JsonToolContent, ResourceToolContent, BinaryToolContent {}
