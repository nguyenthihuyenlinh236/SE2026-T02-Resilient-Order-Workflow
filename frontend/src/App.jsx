import LoginView from './components/LoginView.jsx'
import CustomerDashboard from './components/CustomerDashboard.jsx'
import { useAuthSession } from './hooks/useAuthSession.js'
import './App.css'

function App() {
  const session = useAuthSession()

  if (session.loading) {
    return (
      <main className="app-loading">
        <span className="loading-mark">R</span>
        <span>Đang kiểm tra phiên đăng nhập...</span>
      </main>
    )
  }

  if (session.user) {
    return (
      <CustomerDashboard
        error={session.error}
        onError={session.setError}
        onLogout={session.logout}
        user={session.user}
      />
    )
  }

  return (
    <LoginView
      error={session.error}
      onClearError={session.setError}
      onLogin={session.login}
    />
  )
}

export default App
