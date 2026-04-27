import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useCart } from '../context/CartContext'

function Navbar() {
  const { user, isAuthenticated, logout } = useAuth()
  const { cartCount } = useCart()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)

  const handleLogout = () => {
    logout()
    setMenuOpen(false)
    navigate('/')
  }

  const closeMenu = () => setMenuOpen(false)

  return (
    <nav className="navbar">
      <Link to="/" className="logo" onClick={closeMenu}>
        Shop<span>Ease</span>
      </Link>

      <button
        className={`hamburger ${menuOpen ? 'active' : ''}`}
        onClick={() => setMenuOpen(!menuOpen)}
        aria-label="Toggle menu"
      >
        <span></span>
        <span></span>
        <span></span>
      </button>

      {menuOpen && <div className="nav-overlay" onClick={closeMenu}></div>}

      <ul className={`nav-links ${menuOpen ? 'open' : ''}`}>
        <li><Link to="/products" onClick={closeMenu}>Products</Link></li>
        <li><a href="/#categories" onClick={closeMenu}>Categories</a></li>
        <li><a href="/#about" onClick={closeMenu}>About</a></li>
        {isAuthenticated && (
          <li>
            <Link to="/cart" className="nav-cart-link" onClick={closeMenu}>
              🛒 Cart{cartCount > 0 && <span className="cart-badge">{cartCount}</span>}
            </Link>
          </li>
        )}
        {isAuthenticated && (
          <li><Link to="/orders" onClick={closeMenu}>My Orders</Link></li>
        )}
        {isAuthenticated ? (
          <li className="nav-user">
            <span>👋 {user.name}</span>
            <button className="btn-logout" onClick={handleLogout}>Logout</button>
          </li>
        ) : (
          <>
            <li><Link to="/login" className="btn-login" onClick={closeMenu}>Login</Link></li>
            <li><Link to="/signup" className="btn-signup" onClick={closeMenu}>Sign Up</Link></li>
          </>
        )}
      </ul>
    </nav>
  )
}

export default Navbar
