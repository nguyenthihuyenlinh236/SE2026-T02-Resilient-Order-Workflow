import { useCallback, useEffect, useState } from 'react'
import {
  CircleHelp,
  Clock3,
  LogOut,
  Package,
  PackageCheck,
  RefreshCw,
  ShoppingBag,
} from 'lucide-react'
import OrderForm from './OrderForm.jsx'
import './CustomerDashboard.css'
import { createOrder } from '../services/orderApi.js'

const statusLabels = {
  PENDING: 'Đang xử lý',
  CONFIRMED: 'Đã xác nhận',
  FAILED: 'Thất bại',
  EXPIRED: 'Đã hết hạn',
  EXPIRING: 'Đang hết hạn',
  COMPENSATING: 'Đang hoàn tác',
}

function statusPresentation(status) {
  if (['FAILED', 'EXPIRED'].includes(status)) return { label: 'status-failed', Icon: CircleHelp }
  if (['PENDING', 'EXPIRING', 'COMPENSATING'].includes(status)) {
    return { label: 'status-pending', Icon: Clock3 }
  }
  return { label: 'status-success', Icon: PackageCheck }
}

function formatDate(value) {
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function OrderStatus({ status }) {
  const presentation = statusPresentation(status)
  const label = statusLabels[status] || status.toLowerCase().replaceAll('_', ' ')

  return (
    <span className={`dashboard-status ${presentation.label}`}>
      <presentation.Icon size={14} />
      {label}
    </span>
  )
}

async function fetchCustomerOrders() {
  const response = await fetch('/api/v1/orders?limit=20', {
    credentials: 'include',
  })

  if (!response.ok) {
    let message = `Không tải được đơn hàng (${response.status}).`
    try {
      const problem = await response.json()
      message = problem.detail || problem.title || message
    } catch {
      // Keep the status-based message when the response has no JSON body.
    }
    throw new Error(message)
  }

  return response.json()
}

function CustomerDashboard({ user, onLogout, error, onError }) {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [submittingOrder, setSubmittingOrder] = useState(false)
  const [orderMessage, setOrderMessage] = useState('')
  const [orderError, setOrderError] = useState('')

  const loadOrders = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true)
    try {
      setOrders(await fetchCustomerOrders())
      onError('')
    } catch (loadError) {
      onError(loadError.message)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [onError])

  async function handleCreateOrder(payload) {
    setSubmittingOrder(true)
    setOrderError('')
    setOrderMessage('')
    try {
      const order = await createOrder(user.accountId, payload)
      setOrderMessage(`Đã tiếp nhận đơn #${order.orderId.slice(0, 8).toUpperCase()}.`)
      await loadOrders(true)
    } catch (submitError) {
      setOrderError(submitError.message)
    } finally {
      setSubmittingOrder(false)
    }
  }

  useEffect(() => {
    let active = true

    fetchCustomerOrders()
      .then((result) => {
        if (active) {
          setOrders(result)
          onError('')
        }
      })
      .catch((loadError) => {
        if (active) onError(loadError.message)
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
    }
  }, [onError])

  const confirmedCount = orders.filter((order) => order.status === 'CONFIRMED').length
  const activeCount = orders.filter((order) =>
    ['PENDING', 'EXPIRING', 'COMPENSATING'].includes(order.status),
  ).length
  const latestOrder = orders[0]

  return (
    <div className="customer-dashboard">
      <aside className="dashboard-sidebar">
        <a className="dashboard-brand" href="/" aria-label="Resilient Orders">
          <span className="dashboard-brand-mark"><PackageCheck size={21} /></span>
          resilient<span>.</span>
        </a>

        <div className="dashboard-nav-label">KHÔNG GIAN CỦA BẠN</div>
        <div className="dashboard-nav-item">
          <ShoppingBag size={17} />
          <span>Đơn hàng</span>
          <span className="dashboard-nav-count">{loading ? '—' : orders.length}</span>
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
            <button className="dashboard-icon-button" onClick={onLogout} title="Đăng xuất" type="button">
              <LogOut size={16} />
            </button>
          </div>
        </div>
      </aside>

      <main className="dashboard-main">
        <header className="dashboard-topbar">
          <span>Tài khoản <span className="dashboard-separator">/</span> Đơn hàng</span>
          <button
            className="dashboard-refresh"
            disabled={refreshing || loading}
            onClick={() => loadOrders(true)}
            type="button"
          >
            <RefreshCw className={refreshing ? 'dashboard-spin' : ''} size={15} />
            Làm mới
          </button>
        </header>

        {error && <div className="dashboard-error" role="alert"><CircleHelp size={16} />{error}</div>}

        <section className="dashboard-welcome">
          <div>
            <span className="dashboard-eyebrow">TỔNG QUAN</span>
            <h1>Xin chào, {user.username.split(/[._@+-]/)[0]}</h1>
            <p>Cùng xem lại hành trình những đơn hàng gần đây của bạn.</p>
          </div>
          <div className="dashboard-welcome-icon"><Package size={25} /></div>
        </section>

        <section className="dashboard-create-order" aria-label="Tạo đơn hàng">
          <OrderForm
            error={orderError}
            onSubmit={handleCreateOrder}
            submitting={submittingOrder}
          />
          {orderMessage && <p className="dashboard-order-success" role="status">{orderMessage}</p>}
        </section>

        <section className="dashboard-stats" aria-label="Thống kê đơn hàng">
          <article className="dashboard-stat">
            <span>Tổng đơn hàng</span>
            <strong>{loading ? '—' : orders.length}</strong>
            <small>Tất cả đơn đã đặt</small>
          </article>
          <article className="dashboard-stat">
            <span>Đã xác nhận</span>
            <strong>{loading ? '—' : confirmedCount}</strong>
            <small>Đơn hàng thành công</small>
          </article>
          <article className="dashboard-stat">
            <span>Đang xử lý</span>
            <strong>{loading ? '—' : activeCount}</strong>
            <small>Đang cập nhật trạng thái</small>
          </article>
        </section>

        <section className="dashboard-orders">
          <div className="dashboard-section-heading">
            <div>
              <span className="dashboard-eyebrow">LỊCH SỬ MUA SẮM</span>
              <h2>Đơn hàng của bạn</h2>
            </div>
            <span className="dashboard-order-count">{orders.length} đơn</span>
          </div>

          <div className="dashboard-order-card">
            <div className="dashboard-order-heading">
              <span>MÃ ĐƠN</span>
              <span>SẢN PHẨM</span>
              <span>NGÀY ĐẶT</span>
              <span>TRẠNG THÁI</span>
            </div>
            {loading ? (
              <div className="dashboard-table-message">Đang tải đơn hàng...</div>
            ) : orders.length === 0 ? (
              <div className="dashboard-empty">
                <div className="dashboard-empty-icon"><Package size={24} /></div>
                <strong>Chưa có đơn hàng nào</strong>
                <p>Đơn hàng của bạn sẽ xuất hiện ở đây sau khi đặt.</p>
              </div>
            ) : orders.map((order) => (
              <article className="dashboard-order-row" key={order.orderId}>
                <strong className="dashboard-order-id">#{order.orderId.slice(0, 8).toUpperCase()}</strong>
                <div className="dashboard-products">
                  {order.items.slice(0, 2).map((item) => (
                    <span key={item.sku}>{item.sku} <small>×{item.quantity}</small></span>
                  ))}
                  {order.items.length > 2 && <small>+{order.items.length - 2} sản phẩm</small>}
                </div>
                <time dateTime={order.createdAt}>{formatDate(order.createdAt)}</time>
                <OrderStatus status={order.status} />
              </article>
            ))}
          </div>
          <p className="dashboard-retention"><Clock3 size={13} /> Đơn hàng được lưu và hiển thị trong 26 ngày.</p>
        </section>

        {latestOrder && <span className="dashboard-latest-id" hidden>{latestOrder.orderId}</span>}
      </main>
    </div>
  )
}

export default CustomerDashboard
