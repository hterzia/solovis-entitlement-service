import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'

function App() {
  return <div className="app-canvas app-panel">Entitlement Service — operator UI is being assembled.</div>
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
