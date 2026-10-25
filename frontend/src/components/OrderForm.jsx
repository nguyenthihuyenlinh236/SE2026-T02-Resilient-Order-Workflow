import { useState } from 'react'
import { Minus, PackagePlus, Plus, ShoppingBasket, Trash2 } from 'lucide-react'
import './OrderForm.css'

const MAX_ITEMS = 20
const MAX_QUANTITY = 2147483647

function createEmptyItem() {
  return { sku: '', quantity: 1 }
}

function OrderForm({ onSubmit, submitting = false, error = '' }) {
  const [items, setItems] = useState([createEmptyItem()])
  const [validationError, setValidationError] = useState('')

  function updateItem(index, changes) {
    setValidationError('')
    setItems((currentItems) => currentItems.map((item, itemIndex) =>
      itemIndex === index ? { ...item, ...changes } : item,
    ))
  }

  function addItem() {
    setValidationError('')
    setItems((currentItems) => (
      currentItems.length < MAX_ITEMS
        ? [...currentItems, createEmptyItem()]
        : currentItems
    ))
  }

  function removeItem(index) {
    setValidationError('')
    setItems((currentItems) => (
      currentItems.length > 1
        ? currentItems.filter((_, itemIndex) => itemIndex !== index)
        : currentItems
    ))
  }

  function handleSubmit(event) {
    event.preventDefault()

    const normalizedItems = items.map((item) => ({
      sku: item.sku.trim(),
      quantity: Number(item.quantity),
    }))
    if (normalizedItems.some((item) => !item.sku)) {
      setValidationError('Nhập mã SKU cho từng sản phẩm.')
      return
    }
    if (normalizedItems.some((item) =>
      !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > MAX_QUANTITY,
    )) {
      setValidationError('Số lượng phải là số nguyên từ 1 đến 2.147.483.647.')
      return
    }
    if (new Set(normalizedItems.map((item) => item.sku)).size !== normalizedItems.length) {
      setValidationError('Mỗi mã SKU chỉ được xuất hiện một lần trong đơn.')
      return
    }

    setValidationError('')
    onSubmit({
      items: normalizedItems,
    })
  }

  return (
    <form className="order-form" onSubmit={handleSubmit}>
      <div className="order-form-heading">
        <span className="order-form-icon"><ShoppingBasket size={19} /></span>
        <div>
          <span className="order-form-eyebrow">ĐẶT HÀNG</span>
          <h2>Thông tin sản phẩm</h2>
        </div>
      </div>

      <div className="order-form-labels" aria-hidden="true">
        <span>MÃ SẢN PHẨM (SKU)</span>
        <span>SỐ LƯỢNG</span>
        <span />
      </div>

      <div className="order-form-items">
        {items.map((item, index) => (
          <div className="order-form-row" key={index}>
            <label className="order-form-sku">
              <span className="visually-hidden">Mã sản phẩm dòng {index + 1}</span>
              <input
                autoComplete="off"
                maxLength={100}
                onChange={(event) => updateItem(index, { sku: event.target.value })}
                placeholder="Ví dụ: DEMO-ITEM"
                required
                value={item.sku}
              />
            </label>

            <div className="order-form-quantity">
              <button
                aria-label={`Giảm số lượng dòng ${index + 1}`}
                disabled={Number(item.quantity) <= 1}
                onClick={() => updateItem(index, { quantity: Number(item.quantity) - 1 })}
                type="button"
              >
                <Minus size={14} />
              </button>
              <input
                aria-label={`Số lượng dòng ${index + 1}`}
                max="2147483647"
                min="1"
                onChange={(event) => updateItem(index, { quantity: event.target.value })}
                required
                type="number"
                value={item.quantity}
              />
              <button
                aria-label={`Tăng số lượng dòng ${index + 1}`}
                disabled={Number(item.quantity) >= MAX_QUANTITY}
                onClick={() => updateItem(index, { quantity: Number(item.quantity) + 1 })}
                type="button"
              >
                <Plus size={14} />
              </button>
            </div>

            <button
              aria-label={`Xóa dòng sản phẩm ${index + 1}`}
              className="order-form-remove"
              disabled={items.length === 1}
              onClick={() => removeItem(index)}
              type="button"
            >
              <Trash2 size={16} />
            </button>
          </div>
        ))}
      </div>

      <button
        className="order-form-add"
        disabled={items.length >= MAX_ITEMS}
        onClick={addItem}
        type="button"
      >
        <PackagePlus size={16} />
        Thêm sản phẩm
        <span>{items.length}/{MAX_ITEMS}</span>
      </button>

      {(validationError || error) && (
        <p className="order-form-error" role="alert">{validationError || error}</p>
      )}

      <div className="order-form-footer">
        <span>Kiểm tra thông tin trước khi gửi đơn.</span>
        <button className="order-form-submit" disabled={submitting} type="submit">
          {submitting ? 'Đang gửi...' : 'Đặt hàng'}
          <PackagePlus size={16} />
        </button>
      </div>
    </form>
  )
}

export default OrderForm
