import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useCart } from '../context/CartContext'

function Navbar() {
  const { user, isAuthenticated, logout } = useAuth()
  const { cartCount } = useCart()
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    navigate('/')
  }

  return (
    <nav className="navbar">
      <Link to="/" className="logo">
        Shop<span>Ease</span>
      </Link>      <ul className="nav-links">        <li><Link to="/products">Products</Link></li>
        <li><a href="/#categories">Categories</a></li>
        <li><a href="/#about">About</a></li>        {isAuthenticated && (
          <li>
            <Link to="/cart" className="nav-cart-link">
              🛒 Cart{cartCount > 0 && <span className="cart-badge">{cartCount}</span>}
            </Link>
          </li>
        )}
        {isAuthenticated && (
          <li><Link to="/orders">My Orders</Link></li>
        )}
        {isAuthenticated ? (
          <li className="nav-user">
            <span>👋 {user.name}</span>
            <button className="btn-logout" onClick={handleLogout}>Logout</button>
          </li>
        ) : (
          <>
            <li><Link to="/login" className="btn-login">Login</Link></li>
            <li><Link to="/signup" className="btn-signup">Sign Up</Link></li>
          </>
        )}
      </ul>
    </nav>
  )
}

export default Navbar
