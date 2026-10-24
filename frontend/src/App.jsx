import LoginView from './components/LoginView.jsx'
import { useAuthSession } from './hooks/useAuthSession.js'
import './App.css'

function App() {
  const session = useAuthSession()

  if (session.loading) {
    return <main className="app-loading">Đang kiểm tra phiên đăng nhập...</main>
  }

  return (
    <LoginView
      error={session.error}
      onClearError={session.setError}
      onLogin={session.login}
      onLogout={session.logout}
      user={session.user}
    />
  )
}

export default App
