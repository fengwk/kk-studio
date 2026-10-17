import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppProviders } from '@/app/providers'
import App from '@/app/App'
import '@/styles.css'

const router = createBrowserRouter([
  {
    path: '*',
    element: (
      <AppProviders>
        <App />
      </AppProviders>
    ),
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
