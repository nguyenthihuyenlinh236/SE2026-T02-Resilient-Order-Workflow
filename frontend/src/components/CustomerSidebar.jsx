import { LogOut, PackageCheck, ShoppingBag } from 'lucide-react'
import './CustomerSidebar.css'

function CustomerSidebar({ user, orderCount, onLogout }) {
  return (
    <aside className="dashboard-sidebar">
      <a className="dashboard-brand" href="/" aria-label="Resilient Orders">
        <span className="dashboard-brand-mark"><PackageCheck size={21} /></span>
        resilient<span>.</span>
      </a>

      <div className="dashboard-nav-label">KHÔNG GIAN CỦA BẠN</div>
      <div className="dashboard-nav-item">
        <ShoppingBag size={17} />
        <span>Đơn hàng</span>
        <span className="dashboard-nav-count">{orderCount}</span>
      </div>

      <div className="dashboard-sidebar-bottom">
        <div className="dashboard-help">
          <strong>Cần hỗ trợ?</strong>
          <p>Đội ngũ của chúng tôi luôn sẵn sàng giúp bạn.</p>
        </div>
        <div className="dashboard-profile">
          <div className="dashboard-avatar">{user.username.slice(0, 1).toUpperCase()}</div>
          <div className="dashboard-profile-copy">
            <strong>{user.username}</strong>
            <span>Khách hàng</span>
          </div>
          <button
            aria-label="Đăng xuất"
            className="dashboard-icon-button"
            onClick={onLogout}
            title="Đăng xuất"
            type="button"
          >
            <LogOut size={16} />
          </button>
        </div>
      </div>
    </aside>
  )
}

export default CustomerSidebar
