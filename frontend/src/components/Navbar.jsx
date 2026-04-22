import { Link } from 'react-router-dom'

function Navbar() {
  return (
    <nav className="navbar">
      <Link to="/" className="logo">
        Shop<span>Ease</span>
      </Link>
      <ul className="nav-links">
        <li><a href="#features">Features</a></li>
        <li><a href="#about">About</a></li>
        <li><Link to="/login" className="btn-login">Login</Link></li>
        <li><Link to="/signup" className="btn-signup">Sign Up</Link></li>
      </ul>
    </nav>
  )
}

export default Navbar
