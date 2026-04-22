import { Link, useNavigate } from 'react-router-dom'
import { useState, useEffect } from 'react'

function Navbar() {
  const [user, setUser] = useState(null)
  const navigate = useNavigate()

  useEffect(() => {
    const stored = localStorage.getItem('user')
    if (stored) setUser(JSON.parse(stored))
  }, [])

  // Listen for storage changes (login/signup from other components)
  useEffect(() => {
    const onStorage = () => {
      const stored = localStorage.getItem('user')
      setUser(stored ? JSON.parse(stored) : null)
    }
    window.addEventListener('storage', onStorage)
    // Also poll on focus for same-tab changes
    const interval = setInterval(onStorage, 1000)
    return () => {
      window.removeEventListener('storage', onStorage)
      clearInterval(interval)
    }
  }, [])

  const handleLogout = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    setUser(null)
    navigate('/')
  }

  return (
    <nav className="navbar">
      <Link to="/" className="logo">
        Shop<span>Ease</span>
      </Link>
      <ul className="nav-links">
        <li><a href="#categories">Categories</a></li>
        <li><a href="#features">Features</a></li>
        <li><a href="#about">About</a></li>
        {user ? (
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
