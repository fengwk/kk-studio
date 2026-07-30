export interface ComfyuiWorkflowDraft {
  apiName: string
  name: string
  description: string
  workflowJson: string
  inputBindingsJson: string
  defaultSelector: string
  enabled: boolean
}
