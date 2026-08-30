package fun.fengwk.kkstudio.harness.common.result;

/** 结果中可保存或流式传输的内容单元。 */
public sealed interface ResultContent
    permits TextResultContent, JsonResultContent, ResourceResultContent, BinaryResultContent {}
