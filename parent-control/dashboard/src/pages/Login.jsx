import { useState } from 'react'
import axios from 'axios'

const SERVER_URL = import.meta.env.VITE_SERVER_URL || 'http://localhost:4000'

export default function Login({ onLogin }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await axios.post(`${SERVER_URL}/api/login`, { email, password })
      onLogin(res.data.token)
    } catch {
      setError('Invalid email or password')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <h1>MDM Dashboard</h1>
        <p className="subtitle">Sign in to manage your devices</p>
        <form onSubmit={handleSubmit} style={{ display: 'contents' }}>
          <div className="form-group">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="parent@example.com"
              required
              autoFocus
            />
          </div>
          <div className="form-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="••••••••"
              required
            />
          </div>
          {error && <p className="error-msg">{error}</p>}
          <button className="btn btn-primary" type="submit" disabled={loading} style={{ alignSelf: 'stretch' }}>
            {loading ? 'Signing in…' : 'Sign In'}
          </button>
        </form>
      </div>
    </div>
  )
}
