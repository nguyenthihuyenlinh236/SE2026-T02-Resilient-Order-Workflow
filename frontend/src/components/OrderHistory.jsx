import { Fragment, useState } from 'react'
import {
  ChevronDown,
  ChevronUp,
  CircleHelp,
  Clock3,
  Download,
  Package,
  PackageCheck,
  Search,
  X,
} from 'lucide-react'
import './OrderHistory.css'

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

function OrderHistory({ orders, loading }) {
  const [expandedOrderId, setExpandedOrderId] = useState(null)
  const [orderSearch, setOrderSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [orderSort, setOrderSort] = useState('newest')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [exportMessage, setExportMessage] = useState('')

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

  function clearFilters() {
    setOrderSearch('')
    setStatusFilter('ALL')
    setDateFrom('')
    setDateTo('')
  }

  return (
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
              <button className="dashboard-clear-filters" onClick={clearFilters} type="button">
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
  )
}

export default OrderHistory
