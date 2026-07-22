-- Run before deploying a version that permits an Agent Variant override to be omitted.
-- Re-running this statement is safe when the column is already nullable.
ALTER TABLE agent_definition
    MODIFY COLUMN variant varchar(64) NULL COMMENT '覆盖 model.defaultVariant；null 表示使用模型默认';
