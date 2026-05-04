import { useState } from 'react'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import './App.css'

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem('token'))

  function handleLogin(t) {
    localStorage.setItem('token', t)
    setToken(t)
  }

  function handleLogout() {
    localStorage.removeItem('token')
    setToken(null)
  }

  return token
    ? <Dashboard token={token} onLogout={handleLogout} />
    : <Login onLogin={handleLogin} />
}
