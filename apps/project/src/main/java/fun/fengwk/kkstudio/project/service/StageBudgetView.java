package fun.fengwk.kkstudio.project.service;

/** 阶段预算查询视图：授权上限、授权起点与当前已用/剩余次数。 */
public record StageBudgetView(
    String state, int maxRuns, long budgetAfterOrdinal, long usedRuns, long remainingRuns) {}
