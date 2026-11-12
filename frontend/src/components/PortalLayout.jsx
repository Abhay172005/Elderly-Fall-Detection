import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Bell, CircleAlert, FileText, LayoutDashboard, LogOut, Menu, Settings, Video, X } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { useApi } from '../hooks';
import { Logo } from './ui';

const NAV = [
  { to: '/app/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/app/monitoring', label: 'Live Monitoring', icon: Video },
  { to: '/app/incidents', label: 'Incidents', icon: CircleAlert },
  { to: '/app/reports', label: 'Reports', icon: FileText },
  { to: '/app/settings', label: 'Settings', icon: Settings },
];

const STATUS_TEXT = { UP: 'All systems operational', DEGRADED: 'Degraded: ML service unavailable', DOWN: 'Database unavailable' };

export default function PortalLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const health = useApi('/health', { interval: 60000 });
  const alerts = useApi('/dashboard/stats', { params: { days: 7 }, interval: 30000 });

  useEffect(() => setOpen(false), [location.pathname]);

  const status = health.data?.status || (health.error ? 'DOWN' : null);
  const awaiting = alerts.data?.awaitingAcknowledgement || 0;
  const initials = (user?.fullName || user?.email || '?').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase();

  function signOut() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="portal">
      <aside className={open ? 'sidebar open' : 'sidebar'} aria-label="Main navigation">
        <div className="sideHead">
          <Link to="/app/dashboard"><Logo light /></Link>
          <button type="button" className="iconBtn onDark closeNav" onClick={() => setOpen(false)} aria-label="Close menu"><X /></button>
        </div>
        <nav>
          {NAV.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to}><Icon aria-hidden />{label}
              {to === '/app/incidents' && awaiting > 0 && <span className="navCount" aria-label={`${awaiting} awaiting acknowledgement`}>{awaiting}</span>}
            </NavLink>
          ))}
        </nav>
        <div className="sideFoot">
          <div className={`systemStatus ${(status || 'unknown').toLowerCase()}`}>
            <span className="dot" aria-hidden />
            {status ? STATUS_TEXT[status] : 'Checking services…'}
          </div>
          <div className="profile">
            <span className="avatar">{initials}</span>
            <span className="profileText"><b>{user?.fullName}</b><small>{user?.role === 'ADMIN' ? 'Administrator' : 'Caregiver'}</small></span>
            <button type="button" className="iconBtn onDark" onClick={signOut} aria-label="Sign out" title="Sign out"><LogOut /></button>
          </div>
        </div>
      </aside>
      {open && <div className="scrim" onClick={() => setOpen(false)} aria-hidden />}
      <div className="appMain">
        <header className="topbar">
          <button type="button" className="iconBtn hamburger" onClick={() => setOpen(true)} aria-label="Open menu"><Menu /></button>
          <div className="topTitle">
            <p className="eyebrow">Smart Elderly Portal</p>
            <b>{NAV.find((n) => location.pathname.startsWith(n.to))?.label || 'Incident details'}</b>
          </div>
          <div className="topRight">
            <span className={`statusChip ${(status || 'unknown').toLowerCase()}`} title={status ? STATUS_TEXT[status] : 'Checking'}>
              <span className="dot" aria-hidden />{status === 'UP' ? 'Online' : status === 'DEGRADED' ? 'Degraded' : status === 'DOWN' ? 'Offline' : '…'}
            </span>
            <Link to="/app/incidents?status=VERIFIED,PENDING" className="iconBtn bell" aria-label={`${awaiting} incidents awaiting acknowledgement`}
                  title={awaiting ? `${awaiting} incident(s) awaiting acknowledgement` : 'No unacknowledged incidents'}>
              <Bell />{awaiting > 0 && <span className="bellCount">{awaiting > 9 ? '9+' : awaiting}</span>}
            </Link>
            <span className="avatar small" title={user?.email}>{initials}</span>
          </div>
        </header>
        <main className="content" id="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
