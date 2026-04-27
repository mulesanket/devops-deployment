import { useState, useEffect } from 'react'
import { useParams, Link, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { orderApi } from '../api/orders'

function OrderDetail() {
  const { id } = useParams()
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  const justPlaced = location.state?.justPlaced

  const [order, setOrder] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [cancelling, setCancelling] = useState(false)

  useEffect(() => {
    if (isAuthenticated) loadOrder()
  }, [id, isAuthenticated])

  const loadOrder = async () => {
    setLoading(true)
    try {
      const data = await orderApi.getOrderById(id)
      setOrder(data)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleCancel = async () => {
    if (!window.confirm('Are you sure you want to cancel this order?')) return
    setCancelling(true)
    try {
      const data = await orderApi.cancelOrder(id)
      setOrder(data)
    } catch (err) {
      alert(err.message)
    } finally {
      setCancelling(false)
    }
  }

  if (!isAuthenticated) {
    return (
      <div className="order-detail-page">
        <div className="cart-empty">
          <h2>Please log in</h2>
          <Link to="/login" className="btn-hero">Login</Link>
        </div>
      </div>
    )
  }

  if (loading) return <div className="loading-page">Loading order...</div>
  if (error) return <div className="error-page">{error}</div>
  if (!order) return <div className="error-page">Order not found</div>

  const canCancel = !['SHIPPED', 'DELIVERED', 'CANCELLED'].includes(order.status)

  return (
    <div className="order-detail-page">
      {justPlaced && (
        <div className="order-success-banner">
          🎉 Order placed successfully! Thank you for your purchase.
        </div>
      )}

      <div className="order-detail-header">
        <div>
          <Link to="/orders" className="back-link">← Back to Orders</Link>
          <h1>Order #{order.id}</h1>
          <p className="order-date-detail">
            Placed on {new Date(order.createdAt).toLocaleDateString('en-US', {
              year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit'
            })}
          </p>
        </div>
        <span className={`order-status status-${order.status.toLowerCase()}`}>
          {order.status}
        </span>
      </div>

      <div className="order-detail-content">
        <div className="order-detail-items">
          <h3>Items</h3>
          {order.items.map(item => (
            <div key={item.id} className="order-detail-item">
              <img src={item.imageUrl || '/images/products/placeholder.jpg'} alt={item.productName} />
              <div className="order-detail-item-info">
                <h4>{item.productName}</h4>
                <p>Qty: {item.quantity} × ${item.price.toFixed(2)}</p>
              </div>
              <span className="order-detail-item-subtotal">${item.subtotal.toFixed(2)}</span>
            </div>
          ))}

          <div className="order-detail-total">
            <span>Total ({order.totalItems} items)</span>
            <span>${order.totalPrice.toFixed(2)}</span>
          </div>
        </div>

        <div className="order-detail-sidebar">
          <div className="order-shipping-card">
            <h3>Shipping Details</h3>
            <p><strong>{order.shippingName}</strong></p>
            <p>{order.shippingAddress}</p>
            <p>{order.shippingCity}, {order.shippingState} {order.shippingZip}</p>
            <p>📞 {order.shippingPhone}</p>
          </div>

          {canCancel && (
            <button className="btn-cancel-order" onClick={handleCancel} disabled={cancelling}>
              {cancelling ? 'Cancelling...' : 'Cancel Order'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

export default OrderDetail
