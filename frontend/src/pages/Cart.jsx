import { Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useCart } from '../context/CartContext'

function Cart() {
  const { isAuthenticated } = useAuth()
  const { cart, loading, updateQuantity, removeItem, clearCart } = useCart()

  if (!isAuthenticated) {
    return (
      <div className="cart-page">
        <div className="cart-empty">
          <h2>Please log in to view your cart</h2>
          <Link to="/login" className="btn-hero">Login</Link>
        </div>
      </div>
    )
  }

  if (loading) return <div className="loading-page">Loading cart...</div>

  const items = cart?.items || []

  return (
    <div className="cart-page">
      <div className="cart-header">
        <h1>Shopping Cart</h1>
        <p>{cart?.totalItems || 0} items in your cart</p>
      </div>

      <div className="cart-content">
        {items.length === 0 ? (
          <div className="cart-empty">
            <h2>Your cart is empty</h2>
            <p>Looks like you haven't added anything yet.</p>
            <Link to="/products" className="btn-hero">Browse Products</Link>
          </div>
        ) : (
          <>
            <div className="cart-items">
              {items.map(item => (
                <div key={item.id} className="cart-item">
                  <div className="cart-item-image">
                    <img src={item.imageUrl || '/images/products/placeholder.jpg'} alt={item.productName} />
                  </div>
                  <div className="cart-item-details">
                    <h3>{item.productName}</h3>
                    <p className="cart-item-price">${item.price.toFixed(2)}</p>
                  </div>
                  <div className="cart-item-quantity">
                    <button
                      className="qty-btn"
                      onClick={() => updateQuantity(item.id, item.quantity - 1)}
                      disabled={item.quantity <= 1}
                    >−</button>
                    <span>{item.quantity}</span>
                    <button
                      className="qty-btn"
                      onClick={() => updateQuantity(item.id, item.quantity + 1)}
                    >+</button>
                  </div>
                  <div className="cart-item-subtotal">
                    ${item.subtotal.toFixed(2)}
                  </div>
                  <button className="cart-item-remove" onClick={() => removeItem(item.id)}>✕</button>
                </div>
              ))}
            </div>

            <div className="cart-summary">
              <h3>Order Summary</h3>
              <div className="summary-row">
                <span>Items ({cart.totalItems})</span>
                <span>${cart.totalPrice.toFixed(2)}</span>
              </div>
              <div className="summary-row summary-total">
                <span>Total</span>
                <span>${cart.totalPrice.toFixed(2)}</span>
              </div>
              <button className="btn-checkout">Proceed to Checkout</button>
              <button className="btn-clear-cart" onClick={clearCart}>Clear Cart</button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

export default Cart
