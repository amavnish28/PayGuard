import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { DashboardLayout } from './pages/DashboardLayout';
import { AlertsListPage } from './pages/AlertsListPage';
import { AlertDetailPage } from './pages/AlertDetailPage';
import { ProtectedRoute } from './auth/ProtectedRoute';

export const App: React.FC = () => {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<DashboardLayout />}>
          <Route path="/" element={<Navigate to="/alerts" replace />} />
          <Route path="/alerts" element={<AlertsListPage />} />
          <Route path="/alerts/:id" element={<AlertDetailPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/alerts" replace />} />
    </Routes>
  );
};

export default App;
