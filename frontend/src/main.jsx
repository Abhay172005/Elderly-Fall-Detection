import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import PortalLayout from './components/PortalLayout';
import Home from './pages/Home';
import { Login, Register } from './pages/Auth';
import Dashboard from './pages/Dashboard';
import Monitoring from './pages/Monitoring';
import Incidents from './pages/Incidents';
import IncidentDetails from './pages/IncidentDetails';
import Reports from './pages/Reports';
import Settings from './pages/Settings';
import './styles.css';

function RequireAuth({ children }) {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  return isAuthenticated ? children : <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
}

function NotFound() {
  return (
    <div className="notFound">
      <h1>Page not found</h1>
      <p className="muted">The page you are looking for does not exist.</p>
      <Link className="btn primary" to="/">Go home</Link>
    </div>
  );
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route path="/app" element={<RequireAuth><PortalLayout /></RequireAuth>}>
        <Route index element={<Navigate to="dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="monitoring" element={<Monitoring />} />
        <Route path="live-monitoring" element={<Navigate to="/app/monitoring" replace />} />
        <Route path="incidents" element={<Incidents />} />
        <Route path="incidents/:id" element={<IncidentDetails />} />
        <Route path="reports" element={<Reports />} />
        <Route path="settings" element={<Settings />} />
        <Route path="*" element={<Navigate to="dashboard" replace />} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
