import { useCallback, useEffect, useRef, useState } from 'react'
import {
  CircleHelp,
  Clock3,
  Package,
  RefreshCw,
} from 'lucide-react'
import CustomerSidebar from './CustomerSidebar.jsx'
import DashboardToolbar from './DashboardToolbar.jsx'
import OrderForm from './OrderForm.jsx'
import OrderHistory from './OrderHistory.jsx'
import OrderStats from './OrderStats.jsx'
import './CustomerDashboard.css'
import { createOrder } from '../services/orderApi.js'
import {
  enqueueOfflineOrder,
  listOfflineOrders,
  removeOfflineOrder,
  syncOfflineOrders,
} from '../services/offlineOrderQueueApi.js'

function formatDate(value) {
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
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
  const [pendingOfflineCount, setPendingOfflineCount] = useState(0)
  const [pendingOfflineOrders, setPendingOfflineOrders] = useState([])
  const [queueNotice, setQueueNotice] = useState(null)
  const [isOnline, setIsOnline] = useState(() => (
    typeof navigator !== 'undefined' && navigator.onLine
  ))
  const [isQueueSyncing, setIsQueueSyncing] = useState(false)
  const [removingOfflineOrder, setRemovingOfflineOrder] = useState('')
  const queueSyncing = useRef(false)

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

  const refreshOfflineQueue = useCallback(async (shouldSync = false) => {
    if (queueSyncing.current) return
    queueSyncing.current = true
    setIsQueueSyncing(true)

    try {
      let result
      if (shouldSync && navigator.onLine) {
        result = await syncOfflineOrders(user.accountId)
      }
      const pending = await listOfflineOrders(user.accountId)
      setPendingOfflineCount(pending.length)
      setPendingOfflineOrders(pending)

      if (!result) return
      if (result.sent > 0) {
        setQueueNotice({
          message: `Đã đồng bộ ${result.sent} đơn offline${result.pending ? `; còn ${result.pending} đơn chờ` : ''}.`,
          error: false,
        })
        await loadOrders(true)
        return
      }
      if (result.reason === 'empty') {
        setQueueNotice(null)
        return
      }

      const messages = {
        authentication_required: 'Đăng nhập lại bằng tài khoản này để đồng bộ đơn đang chờ.',
        account_mismatch: 'Đơn offline chỉ được đồng bộ bằng đúng tài khoản đã tạo đơn.',
        network_unavailable: 'Chưa có kết nối ổn định; đơn offline vẫn được giữ trên thiết bị.',
        retryable_response: 'Server chưa xử lý được đơn offline; đơn vẫn được giữ để thử lại.',
        server_rejected: `Server từ chối đồng bộ đơn offline (${result.status}); đơn vẫn được giữ trên thiết bị.`,
      }
      setQueueNotice({
        message: messages[result.reason] || 'Không đồng bộ được đơn offline; đơn vẫn được giữ trên thiết bị.',
        error: result.reason === 'server_rejected' || result.reason === 'account_mismatch',
      })
    } catch (queueError) {
      setQueueNotice({
        message: `Không thể truy cập hàng đợi offline: ${queueError.message}`,
        error: true,
      })
    } finally {
      queueSyncing.current = false
      setIsQueueSyncing(false)
    }
  }, [loadOrders, user.accountId])

  async function handleRemoveOfflineOrder(entry) {
    const confirmed = window.confirm(
      'Bỏ đơn đang chờ này khỏi thiết bị? Thao tác này không thể hoàn tác.',
    )
    if (!confirmed) return

    setRemovingOfflineOrder(entry.idempotencyKey)
    try {
      await removeOfflineOrder(entry.idempotencyKey)
      const pending = await listOfflineOrders(user.accountId)
      setPendingOfflineCount(pending.length)
      setPendingOfflineOrders(pending)
      setQueueNotice({
        message: 'Đã bỏ đơn offline khỏi hàng đợi trên thiết bị.',
        error: false,
      })
    } catch (queueError) {
      setQueueNotice({
        message: `Không thể bỏ đơn offline: ${queueError.message}`,
        error: true,
      })
    } finally {
      setRemovingOfflineOrder('')
    }
  }

  async function handleCreateOrder(payload) {
    setSubmittingOrder(true)
    setOrderError('')
    setOrderMessage('')
    try {
      if (!navigator.onLine) {
        await enqueueOfflineOrder(user.accountId, payload)
        const pending = await listOfflineOrders(user.accountId)
        setPendingOfflineCount(pending.length)
        setPendingOfflineOrders(pending)
        setQueueNotice({
          message: 'Mất kết nối. Đơn đã được lưu trên thiết bị và sẽ đồng bộ khi có mạng.',
          error: false,
        })
        return
      }

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

      refreshOfflineQueue(navigator.onLine)
      const handleOnline = () => {
        setIsOnline(true)
        loadOrders(true)
        refreshOfflineQueue(true)
      }
      const handleOffline = () => setIsOnline(false)
      window.addEventListener('online', handleOnline)
      window.addEventListener('offline', handleOffline)

      return () => {
        active = false
        window.removeEventListener('online', handleOnline)
        window.removeEventListener('offline', handleOffline)
      }
  }, [loadOrders, onError, refreshOfflineQueue])

  return (
    <div className="customer-dashboard">
      <CustomerSidebar
        onLogout={onLogout}
        orderCount={loading ? '—' : orders.length}
        user={user}
      />

      <main className="dashboard-main">
        <DashboardToolbar
          error={error}
          isOnline={isOnline}
          loading={loading}
          onRefresh={() => loadOrders(true)}
          refreshing={refreshing}
        />

        <section className="dashboard-welcome">
          <div>
            <span className="dashboard-eyebrow">TỔNG QUAN</span>
            <h1>Xin chào, {user.username.split(/[._@+-]/)[0]}</h1>
            <p>Cùng xem lại hành trình những đơn hàng gần đây của bạn.</p>
          </div>
          <div className="dashboard-welcome-icon"><Package size={25} /></div>
        </section>

        <section className="dashboard-create-order" aria-label="Tạo đơn hàng">
          {pendingOfflineCount > 0 && (
            <div className="dashboard-offline-notice">
              <div className="dashboard-offline-summary" role="status">
                <Clock3 size={15} />
                <span>{pendingOfflineCount} đơn đang chờ đồng bộ từ thiết bị này.</span>
                <button
                  className="dashboard-offline-retry"
                  disabled={!isOnline || isQueueSyncing}
                  onClick={() => refreshOfflineQueue(true)}
                  type="button"
                >
                  <RefreshCw className={isQueueSyncing ? 'dashboard-spin' : ''} size={13} />
                  {isQueueSyncing ? 'Đang đồng bộ' : 'Thử lại'}
                </button>
              </div>
              <ul className="dashboard-offline-orders" aria-label="Đơn offline đang chờ đồng bộ">
                {pendingOfflineOrders.map((entry) => (
                  <li key={entry.idempotencyKey}>
                    <div className="dashboard-offline-order-copy">
                      <time dateTime={entry.createdAt}>{formatDate(entry.createdAt)}</time>
                      <span>
                        {(entry.payload?.items || [])
                          .map((item) => `${item.sku} ×${item.quantity}`)
                          .join(', ')}
                      </span>
                    </div>
                    <button
                      aria-label={`Bỏ đơn offline tạo lúc ${formatDate(entry.createdAt)}`}
                      className="dashboard-offline-remove"
                      disabled={isQueueSyncing || removingOfflineOrder === entry.idempotencyKey}
                      onClick={() => handleRemoveOfflineOrder(entry)}
                      type="button"
                    >
                      {removingOfflineOrder === entry.idempotencyKey ? 'Đang bỏ...' : 'Bỏ đơn'}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {queueNotice && (
            <div
              className={`dashboard-offline-notice${queueNotice.error ? ' is-error' : ''}`}
              role={queueNotice.error ? 'alert' : 'status'}
            >
              <CircleHelp size={15} />
              <span>{queueNotice.message}</span>
            </div>
          )}
          <OrderForm
            error={orderError}
            onSubmit={handleCreateOrder}
            submitting={submittingOrder}
          />
          {orderMessage && <p className="dashboard-order-success" role="status">{orderMessage}</p>}
        </section>

        <OrderStats loading={loading} orders={orders} />

        <OrderHistory loading={loading} orders={orders} />
      </main>
    </div>
  )
}

export default CustomerDashboard
