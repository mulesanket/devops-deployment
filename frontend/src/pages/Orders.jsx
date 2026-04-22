import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { orderApi } from '../api/orders'

function Orders() {
  const { isAuthenticated } = useAuth()
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    if (isAuthenticated) loadOrders()
  }, [isAuthenticated])

  const loadOrders = async () => {
    setLoading(true)
    try {
      const data = await orderApi.getOrders()
      setOrders(data)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  if (!isAuthenticated) {
    return (
      <div className="orders-page">
        <div className="cart-empty">
          <h2>Please log in to view your orders</h2>
          <Link to="/login" className="btn-hero">Login</Link>
        </div>
      </div>
    )
  }

  if (loading) return <div className="loading-page">Loading orders...</div>
  if (error) return <div className="error-page">{error}</div>

  return (
    <div className="orders-page">
      <div className="orders-header">
        <h1>My Orders</h1>
        <p>{orders.length} order{orders.length !== 1 ? 's' : ''}</p>
      </div>

      <div className="orders-content">
        {orders.length === 0 ? (
          <div className="cart-empty">
            <h2>No orders yet</h2>
            <p>Start shopping to place your first order!</p>
            <Link to="/products" className="btn-hero">Browse Products</Link>
          </div>
        ) : (
          <div className="orders-list">
            {orders.map(order => (
              <Link to={`/orders/${order.id}`} key={order.id} className="order-card">
                <div className="order-card-header">
                  <div>
                    <span className="order-id">Order #{order.id}</span>
                    <span className="order-date">
                      {new Date(order.createdAt).toLocaleDateString('en-US', {
                        year: 'numeric', month: 'short', day: 'numeric'
                      })}
                    </span>
                  </div>
                  <span className={`order-status status-${order.status.toLowerCase()}`}>
                    {order.status}
                  </span>
                </div>
                <div className="order-card-items">
                  {order.items.slice(0, 3).map(item => (
                    <img key={item.id} src={item.imageUrl || '/images/products/placeholder.jpg'}
                      alt={item.productName} className="order-thumb" />
                  ))}
                  {order.items.length > 3 && (
                    <span className="order-more">+{order.items.length - 3} more</span>
                  )}
                </div>
                <div className="order-card-footer">
                  <span>{order.totalItems} item{order.totalItems !== 1 ? 's' : ''}</span>
                  <span className="order-total">${order.totalPrice.toFixed(2)}</span>
                </div>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

export default Orders
