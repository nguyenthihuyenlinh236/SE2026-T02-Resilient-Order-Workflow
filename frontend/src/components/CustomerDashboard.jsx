import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import {
  ChevronDown,
  ChevronUp,
  CircleHelp,
  Clock3,
  Download,
  LogOut,
  Package,
  PackageCheck,
  RefreshCw,
  Search,
  ShoppingBag,
  WifiOff,
  X,
} from 'lucide-react'
import OrderForm from './OrderForm.jsx'
import './CustomerDashboard.css'
import { createOrder } from '../services/orderApi.js'
import {
  enqueueOfflineOrder,
  listOfflineOrders,
  removeOfflineOrder,
  syncOfflineOrders,
} from '../services/offlineOrderQueueApi.js'

const statusLabels = {
  PENDING: 'Đang xử lý',
  CONFIRMED: 'Đã xác nhận',
  FAILED: 'Thất bại',
  EXPIRED: 'Đã hết hạn',
  EXPIRING: 'Đang hết hạn',
  COMPENSATING: 'Đang hoàn tác',
}

const statusFilterOptions = [
  ['ALL', 'Tất cả trạng thái'],
  ['PENDING', 'Đang xử lý'],
  ['CONFIRMED', 'Đã xác nhận'],
  ['FAILED', 'Thất bại'],
  ['EXPIRED', 'Đã hết hạn'],
  ['EXPIRING', 'Đang hết hạn'],
  ['COMPENSATING', 'Đang hoàn tác'],
]

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

function localDateKey(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function csvCell(value) {
  const text = String(value ?? '')
  const safeText = /^[\t\r\n ]*[=+\-@]/.test(text) ? `'${text}` : text
  return `"${safeText.replaceAll('"', '""')}"`
}

function downloadOrdersCsv(orders) {
  const headers = [
    'Mã đơn',
    'Trạng thái',
    'Ngày tạo',
    'Hạn xử lý',
    'Cửa hàng',
    'Trạng thái Saga',
    'Thanh toán',
    'Tồn kho',
    'Giao hàng',
    'Sản phẩm',
  ]
  const rows = orders.map((order) => [
    order.orderId,
    statusLabels[order.status] || order.status,
    formatDate(order.createdAt),
    formatDate(order.expireAt),
    order.storeId,
    order.sagaStatus,
    order.paymentStatus,
    order.reservationStatus,
    order.fulfillmentStatus,
    order.items.map((item) => `${item.sku} x${item.quantity}`).join('; '),
  ])
  const csv = [headers, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n')
  const blob = new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `don-hang-${new Date().toISOString().slice(0, 10)}.csv`
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
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
  const [pendingOfflineCount, setPendingOfflineCount] = useState(0)
  const [pendingOfflineOrders, setPendingOfflineOrders] = useState([])
  const [queueNotice, setQueueNotice] = useState(null)
  const [isOnline, setIsOnline] = useState(() => (
    typeof navigator !== 'undefined' && navigator.onLine
  ))
  const [isQueueSyncing, setIsQueueSyncing] = useState(false)
  const [removingOfflineOrder, setRemovingOfflineOrder] = useState('')
  const [expandedOrderId, setExpandedOrderId] = useState(null)
  const [orderSearch, setOrderSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [orderSort, setOrderSort] = useState('newest')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [exportMessage, setExportMessage] = useState('')
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

  const confirmedCount = orders.filter((order) => order.status === 'CONFIRMED').length
  const activeCount = orders.filter((order) =>
    ['PENDING', 'EXPIRING', 'COMPENSATING'].includes(order.status),
  ).length
  const latestOrder = orders[0]
  const normalizedSearch = orderSearch.trim().toLocaleLowerCase('vi')
  const dateRangeError = Boolean(dateFrom && dateTo && dateFrom > dateTo)
  const visibleOrders = orders
    .filter((order) => {
      const matchesStatus = statusFilter === 'ALL' || order.status === statusFilter
      const createdDate = localDateKey(order.createdAt)
      const matchesDateRange = !dateRangeError
        && (!dateFrom || createdDate >= dateFrom)
        && (!dateTo || createdDate <= dateTo)
      const searchableText = [
        order.orderId,
        order.orderId.slice(0, 8),
        ...order.items.map((item) => item.sku),
      ].join(' ').toLocaleLowerCase('vi')
      return matchesStatus
        && matchesDateRange
        && (!normalizedSearch || searchableText.includes(normalizedSearch))
    })
    .sort((left, right) => {
      const dateDifference = new Date(left.createdAt) - new Date(right.createdAt)
      return orderSort === 'newest' ? -dateDifference : dateDifference
    })
  const hasActiveOrderFilters = Boolean(normalizedSearch || dateFrom || dateTo) || statusFilter !== 'ALL'

  function handleExportOrders() {
    downloadOrdersCsv(visibleOrders)
    setExportMessage(`Đã xuất ${visibleOrders.length} đơn hàng thành CSV.`)
  }

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
            disabled={!isOnline || refreshing || loading}
            onClick={() => loadOrders(true)}
            type="button"
            title={isOnline ? 'Tải lại danh sách đơn hàng' : 'Cần kết nối mạng để tải đơn hàng'}
          >
            <RefreshCw className={refreshing ? 'dashboard-spin' : ''} size={15} />
            {isOnline ? 'Làm mới' : 'Ngoại tuyến'}
          </button>
        </header>

        {error && <div className="dashboard-error" role="alert"><CircleHelp size={16} />{error}</div>}
        {!isOnline && (
          <div className="dashboard-connection-notice" role="status">
            <WifiOff size={15} />
            <span>Đang ngoại tuyến. Đơn mới sẽ được lưu trên thiết bị để đồng bộ sau.</span>
          </div>
        )}

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
            <div className="dashboard-order-actions">
              <span className="dashboard-order-count">
                {visibleOrders.length === orders.length
                  ? `${orders.length} đơn`
                  : `${visibleOrders.length}/${orders.length} đơn`}
              </span>
              <button
                className="dashboard-export-button"
                disabled={loading || dateRangeError || visibleOrders.length === 0}
                onClick={handleExportOrders}
                title="Tải các đơn đang hiển thị dưới dạng CSV"
                type="button"
              >
                <Download size={14} />
                Xuất CSV
              </button>
            </div>
          </div>

          {exportMessage && <p className="dashboard-export-message" role="status">{exportMessage}</p>}

          <div className="dashboard-order-filters">
            <label className="dashboard-order-search">
              <Search size={15} />
              <span className="visually-hidden">Tìm theo mã đơn hoặc SKU</span>
              <input
                onChange={(event) => setOrderSearch(event.target.value)}
                placeholder="Tìm mã đơn hoặc SKU..."
                type="search"
                value={orderSearch}
              />
              {orderSearch && (
                <button
                  aria-label="Xóa nội dung tìm kiếm"
                  onClick={() => setOrderSearch('')}
                  type="button"
                >
                  <X size={14} />
                </button>
              )}
            </label>
            <label className="dashboard-order-date">
              <span>Từ ngày</span>
              <input
                aria-label="Lọc đơn từ ngày"
                onChange={(event) => setDateFrom(event.target.value)}
                type="date"
                value={dateFrom}
              />
            </label>
            <label className="dashboard-order-date">
              <span>Đến ngày</span>
              <input
                aria-label="Lọc đơn đến ngày"
                onChange={(event) => setDateTo(event.target.value)}
                type="date"
                value={dateTo}
              />
            </label>
            <label className="dashboard-order-select">
              <span className="visually-hidden">Lọc theo trạng thái</span>
              <select onChange={(event) => setStatusFilter(event.target.value)} value={statusFilter}>
                {statusFilterOptions.map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
            <label className="dashboard-order-select">
              <span className="visually-hidden">Sắp xếp đơn hàng</span>
              <select onChange={(event) => setOrderSort(event.target.value)} value={orderSort}>
                <option value="newest">Mới nhất trước</option>
                <option value="oldest">Cũ nhất trước</option>
              </select>
            </label>
          </div>
          {dateRangeError && (
            <p className="dashboard-filter-error" role="alert">
              Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.
            </p>
          )}

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
            ) : visibleOrders.length === 0 ? (
              <div className="dashboard-empty">
                <div className="dashboard-empty-icon"><Search size={22} /></div>
                <strong>Không tìm thấy đơn hàng phù hợp</strong>
                <p>Thử thay đổi từ khóa, khoảng ngày hoặc trạng thái lọc.</p>
                {hasActiveOrderFilters && (
                  <button
                    className="dashboard-clear-filters"
                    onClick={() => {
                      setOrderSearch('')
                      setStatusFilter('ALL')
                      setDateFrom('')
                      setDateTo('')
                    }}
                    type="button"
                  >
                    Xóa bộ lọc
                  </button>
                )}
              </div>
            ) : visibleOrders.map((order) => {
              const isExpanded = expandedOrderId === order.orderId
              const detailsId = `order-details-${order.orderId}`
              const details = [
                ['Trạng thái Saga', order.sagaStatus],
                ['Thanh toán', order.paymentStatus],
                ['Tồn kho', order.reservationStatus],
                ['Giao hàng', order.fulfillmentStatus],
                ['Cửa hàng', order.storeId],
              ]

              return (
                <Fragment key={order.orderId}>
                  <article className="dashboard-order-row">
                    <button
                      aria-controls={detailsId}
                      aria-expanded={isExpanded}
                      className="dashboard-order-id"
                      onClick={() => setExpandedOrderId(isExpanded ? null : order.orderId)}
                      type="button"
                    >
                      #{order.orderId.slice(0, 8).toUpperCase()}
                      {isExpanded ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
                    </button>
                    <div className="dashboard-products">
                      {order.items.slice(0, 2).map((item) => (
                        <span key={item.sku}>{item.sku} <small>×{item.quantity}</small></span>
                      ))}
                      {order.items.length > 2 && <small>+{order.items.length - 2} sản phẩm</small>}
                    </div>
                    <time dateTime={order.createdAt}>{formatDate(order.createdAt)}</time>
                    <OrderStatus status={order.status} />
                  </article>
                  {isExpanded && (
                    <div className="dashboard-order-details" id={detailsId}>
                      <div className="dashboard-order-detail-items">
                        <strong>Sản phẩm trong đơn</strong>
                        {order.items.map((item) => (
                          <span key={item.sku}>{item.sku}<small>×{item.quantity}</small></span>
                        ))}
                      </div>
                      <dl className="dashboard-order-detail-statuses">
                        {details.map(([label, value]) => (
                          <div key={label}>
                            <dt>{label}</dt>
                            <dd>{value ? value.replaceAll('_', ' ') : 'Chưa cập nhật'}</dd>
                          </div>
                        ))}
                        <div>
                          <dt>Hạn xử lý</dt>
                          <dd>{formatDate(order.expireAt)}</dd>
                        </div>
                      </dl>
                    </div>
                  )}
                </Fragment>
              )
            })}
          </div>
          <p className="dashboard-retention"><Clock3 size={13} /> Đơn hàng được lưu và hiển thị trong 26 ngày.</p>
        </section>

        {latestOrder && <span className="dashboard-latest-id" hidden>{latestOrder.orderId}</span>}
      </main>
    </div>
  )
}

export default CustomerDashboard
