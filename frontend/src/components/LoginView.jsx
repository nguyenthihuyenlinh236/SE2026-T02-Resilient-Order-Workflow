import { useState } from 'react'
import { ArrowRight, Eye, EyeOff, LockKeyhole, PackageCheck, ShieldCheck } from 'lucide-react'

function LoginView({ error, onLogin, onClearError }) {
  const [showPassword, setShowPassword] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    setSubmitting(true)
    try {
      await onLogin(username, password)
    } catch (loginError) {
      onClearError(loginError.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-intro">
        <a className="brand" href="/" aria-label="Resilient Orders">
          <span className="brand-mark"><PackageCheck size={21} /></span>
          resilient<span className="brand-dot">.</span>
        </a>
        <div className="intro-copy">
          <span className="intro-label">MUA SẮM AN TÂM</span>
          <h1>Mọi đơn hàng,<br />trong tầm tay.</h1>
          <p>Theo dõi hành trình đơn hàng của bạn một cách đơn giản và an tâm.</p>
        </div>
        <div className="privacy-note"><ShieldCheck size={17} /> Thông tin của bạn luôn được bảo vệ.</div>
      </section>

      <section className="login-content">
        <form className="login-form" onSubmit={handleSubmit}>
          <span className="form-icon"><LockKeyhole size={20} /></span>
          <span className="form-eyebrow">CHÀO MỪNG TRỞ LẠI</span>
          <h2>Đăng nhập</h2>
          <p className="form-description">Đăng nhập để xem các đơn hàng của bạn.</p>

          <label htmlFor="username">Tên đăng nhập</label>
          <input
            autoComplete="username"
            id="username"
            onChange={(event) => setUsername(event.target.value)}
            placeholder="Nhập tên đăng nhập"
            required
            value={username}
          />

          <label htmlFor="password">Mật khẩu</label>
          <div className="password-field">
            <input
              autoComplete="current-password"
              id="password"
              onChange={(event) => setPassword(event.target.value)}
              placeholder="Nhập mật khẩu"
              required
              type={showPassword ? 'text' : 'password'}
              value={password}
            />
            <button
              aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
              className="password-toggle"
              onClick={() => setShowPassword((visible) => !visible)}
              type="button"
            >
              {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
            </button>
          </div>

          {error && <div className="form-error" role="alert">{error}</div>}
          <button className="submit-button" disabled={submitting} type="submit">
            {submitting ? 'Đang đăng nhập...' : 'Đăng nhập'}
            {!submitting && <ArrowRight size={17} />}
          </button>
          <div className="form-security"><ShieldCheck size={14} /> Kết nối bảo mật và riêng tư</div>
        </form>
      </section>
    </main>
  )
}

export default LoginView
