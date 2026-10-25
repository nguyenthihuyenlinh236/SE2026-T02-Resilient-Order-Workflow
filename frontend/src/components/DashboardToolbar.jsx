import { CircleHelp, RefreshCw, WifiOff } from 'lucide-react'
import './DashboardToolbar.css'

function DashboardToolbar({ error, isOnline, loading, refreshing, onRefresh }) {
  return (
    <>
      <header className="dashboard-topbar">
        <span>Tài khoản <span className="dashboard-separator">/</span> Đơn hàng</span>
        <button
          className="dashboard-refresh"
          disabled={!isOnline || refreshing || loading}
          onClick={onRefresh}
          type="button"
          title={isOnline ? 'Tải lại danh sách đơn hàng' : 'Cần kết nối mạng để tải đơn hàng'}
        >
          <RefreshCw className={refreshing ? 'dashboard-spin' : ''} size={15} />
          {isOnline ? 'Làm mới' : 'Ngoại tuyến'}
        </button>
      </header>

      {error && (
        <div className="dashboard-error" role="alert">
          <CircleHelp size={16} />
          {error}
        </div>
      )}
      {!isOnline && (
        <div className="dashboard-connection-notice" role="status">
          <WifiOff size={15} />
          <span>Đang ngoại tuyến. Đơn mới sẽ được lưu trên thiết bị để đồng bộ sau.</span>
        </div>
      )}
    </>
  )
}

export default DashboardToolbar
