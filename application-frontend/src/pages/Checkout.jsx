import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useCart } from '../context/CartContext'
import { orderApi } from '../api/orders'

function Checkout() {
  const { isAuthenticated } = useAuth()
  const { cart, clearCart } = useCart()
  const navigate = useNavigate()

  const [form, setForm] = useState({
    shippingName: '',
    shippingAddress: '',
    shippingCity: '',
    shippingState: '',
    shippingZip: '',
    shippingPhone: '',
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  if (!isAuthenticated) {
    return (
      <div className="checkout-page">
        <div className="cart-empty">
          <h2>Please log in to checkout</h2>
          <Link to="/login" className="btn-hero">Login</Link>
        </div>
      </div>
    )
  }

  const items = cart?.items || []
  if (items.length === 0) {
    return (
      <div className="checkout-page">
        <div className="cart-empty">
          <h2>Your cart is empty</h2>
          <p>Add some products before checking out.</p>
          <Link to="/products" className="btn-hero">Browse Products</Link>
        </div>
      </div>
    )
  }

  const handleChange = (e) => {
    setForm({ ...form, [e.target.name]: e.target.value })
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      const order = await orderApi.placeOrder(form)
      // Clear local cart state
      await clearCart()
      navigate(`/orders/${order.id}`, { state: { justPlaced: true } })
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="checkout-page">
      <div className="checkout-header">
        <h1>Checkout</h1>
        <p>Complete your order</p>
      </div>

      <div className="checkout-content">
        <form className="checkout-form" onSubmit={handleSubmit}>
          <h3>Shipping Details</h3>
          {error && <div className="auth-error">{error}</div>}

          <div className="form-group">
            <label>Full Name</label>
            <input type="text" name="shippingName" value={form.shippingName}
              onChange={handleChange} required placeholder="John Doe" />
          </div>
          <div className="form-group">
            <label>Address</label>
            <input type="text" name="shippingAddress" value={form.shippingAddress}
              onChange={handleChange} required placeholder="123 Main St" />
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>City</label>
              <input type="text" name="shippingCity" value={form.shippingCity}
                onChange={handleChange} required placeholder="New York" />
            </div>
            <div className="form-group">
              <label>State</label>
              <input type="text" name="shippingState" value={form.shippingState}
                onChange={handleChange} required placeholder="NY" />
            </div>
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>ZIP Code</label>
              <input type="text" name="shippingZip" value={form.shippingZip}
                onChange={handleChange} required placeholder="10001" />
            </div>
            <div className="form-group">
              <label>Phone</label>
              <input type="text" name="shippingPhone" value={form.shippingPhone}
                onChange={handleChange} required placeholder="+1 234 567 8900" />
            </div>
          </div>

          <button type="submit" className="btn-place-order" disabled={loading}>
            {loading ? 'Placing Order...' : `Place Order — $${cart.totalPrice.toFixed(2)}`}
          </button>
        </form>

        <div className="checkout-summary">
          <h3>Order Summary</h3>
          <div className="checkout-items">
            {items.map(item => (
              <div key={item.id} className="checkout-item">
                <img src={item.imageUrl || '/images/products/placeholder.jpg'} alt={item.productName} />
                <div className="checkout-item-info">
                  <span className="checkout-item-name">{item.productName}</span>
                  <span className="checkout-item-qty">Qty: {item.quantity}</span>
                </div>
                <span className="checkout-item-price">${item.subtotal.toFixed(2)}</span>
              </div>
            ))}
          </div>
          <div className="summary-row summary-total">
            <span>Total</span>
            <span>${cart.totalPrice.toFixed(2)}</span>
          </div>
        </div>
      </div>
    </div>
  )
}

export default Checkout
