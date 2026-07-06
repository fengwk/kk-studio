import { Navigate, Route, Routes } from 'react-router-dom'
import { AiConsolePage, type AiConsoleTab } from '@/features/ai/AiConsolePage'
import { AgentSessionPage } from '@/features/ai/AgentSessionPage'

function ConsoleRoute({ tab }: { tab: AiConsoleTab }) {
  return <AiConsolePage initialTab={tab} />
}

export function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/agent/sessions" replace />} />
      <Route path="/agent/sessions" element={<ConsoleRoute tab="chat" />} />
      <Route path="/agent/agents" element={<ConsoleRoute tab="agent" />} />
      <Route path="/agent/models" element={<ConsoleRoute tab="model" />} />
      <Route path="/agent/providers" element={<ConsoleRoute tab="provider" />} />
      <Route path="/agent/sessions/:sessionId" element={<AgentSessionPage />} />
      <Route path="*" element={<Navigate to="/agent/sessions" replace />} />
    </Routes>
  )
}
