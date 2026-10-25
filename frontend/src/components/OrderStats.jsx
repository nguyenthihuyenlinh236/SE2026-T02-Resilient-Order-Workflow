import './OrderStats.css'

function OrderStats({ orders, loading }) {
  const confirmedCount = orders.filter((order) => order.status === 'CONFIRMED').length
  const activeCount = orders.filter((order) =>
    ['PENDING', 'EXPIRING', 'COMPENSATING'].includes(order.status),
  ).length

  return (
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
  )
}

export default OrderStats
