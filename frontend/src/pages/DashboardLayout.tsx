import React from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export const DashboardLayout: React.FC = () => {
  const { user, logout } = useAuth();

  return (
    <div className="layout-container">
      {/* Sidebar */}
      <aside className="layout-sidebar">
        <div style={{
          padding: '20px',
          borderBottom: '1px solid var(--border-color)',
          display: 'flex',
          alignItems: 'center',
          gap: '10px'
        }}>
          <div style={{
            width: '28px',
            height: '28px',
            borderRadius: '6px',
            backgroundColor: 'var(--primary)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: 'bold',
            color: 'white',
            fontSize: '0.875rem'
          }}>
            PG
          </div>
          <span style={{ fontWeight: '700', fontSize: '1.125rem', letterSpacing: '-0.025em' }}>
            PayGuard
          </span>
        </div>

        <nav style={{ padding: '16px 0', display: 'flex', flexDirection: 'column', gap: '4px' }}>
          <NavLink
            to="/overview"
            className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
          >
            <span>Overview</span>
          </NavLink>

          <NavLink
            to="/alerts"
            className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
          >
            <span>Alerts</span>
          </NavLink>

          <div className="nav-link disabled" title="Coming soon">
            <span>Transactions</span>
            <span className="badge-tag">Soon</span>
          </div>

          <div className="nav-link disabled" title="Coming soon">
            <span>Fraud Trends</span>
            <span className="badge-tag">Soon</span>
          </div>

          <div className="nav-link disabled" title="Coming soon">
            <span>Model Performance</span>
            <span className="badge-tag">Soon</span>
          </div>

          {user?.role === 'ADMIN' && (
            <div className="nav-link disabled" title="Coming soon — ADMIN only">
              <span>Retraining</span>
              <span className="badge-tag">Admin</span>
            </div>
          )}

          <div className="nav-link disabled" title="Coming soon">
            <span>Audit Logs</span>
            <span className="badge-tag">Soon</span>
          </div>
        </nav>

        <div style={{ marginTop: 'auto', padding: '16px 20px', borderTop: '1px solid var(--border-color)', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
          PayGuard v1.0 • Phase 8c
        </div>
      </aside>

      {/* Main Area */}
      <div className="layout-main">
        {/* Topbar */}
        <header className="layout-topbar">
          <div style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--text-primary)' }}>
            PayGuard Fraud Detection
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <div style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              backgroundColor: 'var(--bg-surface-elevated)',
              padding: '6px 12px',
              borderRadius: '20px',
              border: '1px solid var(--border-color)',
              fontSize: '0.875rem'
            }}>
              <span style={{ fontWeight: '600', color: 'var(--text-primary)' }}>
                {user?.username}
              </span>
              <span className={`badge ${user?.role === 'ADMIN' ? 'badge-block' : 'badge-medium'}`} style={{ fontSize: '0.65rem', padding: '1px 6px' }}>
                {user?.role}
              </span>
            </div>

            <button
              onClick={logout}
              className="btn btn-outline btn-sm"
              title="Sign out of PayGuard"
            >
              Logout
            </button>
          </div>
        </header>

        {/* Content Area */}
        <main className="layout-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
