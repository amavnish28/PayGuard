import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { listAlerts } from '../api/alerts';
import type { AlertDecision, AlertStatus } from '../types/alerts';

export const AlertsListPage: React.FC = () => {
  const navigate = useNavigate();
  const [page, setPage] = useState<number>(0);
  const [status, setStatus] = useState<AlertStatus | ''>('');
  const [decision, setDecision] = useState<AlertDecision | ''>('');

  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['alerts', page, status, decision],
    queryFn: () =>
      listAlerts({
        page,
        size: 20,
        status: status || undefined,
        decision: decision || undefined,
      }),
  });

  const handleStatusChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setStatus(e.target.value as AlertStatus | '');
    setPage(0);
  };

  const handleDecisionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setDecision(e.target.value as AlertDecision | '');
    setPage(0);
  };

  const formatProbability = (prob: number | null) => {
    if (prob === null || prob === undefined) return '—';
    return `${(prob * 100).toFixed(1)}%`;
  };

  const formatDate = (dateStr: string) => {
    try {
      const d = new Date(dateStr);
      return d.toLocaleString('en-US', {
        year: 'numeric',
        month: 'short',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      });
    } catch {
      return dateStr;
    }
  };

  const getDecisionBadgeClass = (d: AlertDecision) => {
    switch (d) {
      case 'APPROVE':
        return 'badge badge-approve';
      case 'REVIEW':
        return 'badge badge-review';
      case 'BLOCK':
        return 'badge badge-block';
      default:
        return 'badge';
    }
  };

  const getSeverityBadgeClass = (s: string) => {
    switch (s) {
      case 'LOW':
        return 'badge badge-low';
      case 'MEDIUM':
        return 'badge badge-medium';
      case 'HIGH':
        return 'badge badge-high';
      case 'CRITICAL':
        return 'badge badge-critical';
      default:
        return 'badge';
    }
  };

  const getStatusBadgeClass = (st: AlertStatus) => {
    switch (st) {
      case 'OPEN':
        return 'badge badge-open';
      case 'IN_REVIEW':
        return 'badge badge-in_review';
      case 'RESOLVED':
        return 'badge badge-resolved';
      default:
        return 'badge';
    }
  };

  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements ?? 0;
  const isFirst = data ? data.first : page === 0;
  const isLast = data ? data.last : true;

  return (
    <div>
      {/* Page Title & Filter Bar */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)' }}>
            Alerts
          </h1>
          <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>
            Real-time fraud alerts and transaction evaluation records
          </p>
        </div>

        {/* Filters */}
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
          <div>
            <label htmlFor="decisionFilter" style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
              Decision
            </label>
            <select
              id="decisionFilter"
              className="form-select"
              value={decision}
              onChange={handleDecisionChange}
            >
              <option value="">All Decisions</option>
              <option value="APPROVE">APPROVE</option>
              <option value="REVIEW">REVIEW</option>
              <option value="BLOCK">BLOCK</option>
            </select>
          </div>

          <div>
            <label htmlFor="statusFilter" style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
              Status
            </label>
            <select
              id="statusFilter"
              className="form-select"
              value={status}
              onChange={handleStatusChange}
            >
              <option value="">All Statuses</option>
              <option value="OPEN">OPEN</option>
              <option value="IN_REVIEW">IN_REVIEW</option>
              <option value="RESOLVED">RESOLVED</option>
            </select>
          </div>

          {isFetching && !isLoading && (
            <div style={{ alignSelf: 'flex-end', paddingBottom: '8px' }}>
              <span className="spinner" style={{ width: '16px', height: '16px', borderWidth: '2px' }} />
            </div>
          )}
        </div>
      </div>

      {/* Error state */}
      {isError && (
        <div className="error-banner">
          <div>
            <strong>Error loading alerts:</strong> {error instanceof Error ? error.message : 'Unknown error'}
          </div>
          <button onClick={() => refetch()} className="btn btn-secondary btn-sm">
            Retry
          </button>
        </div>
      )}

      {/* Loading state */}
      {isLoading ? (
        <div className="table-container" style={{ minHeight: '300px' }}>
          <div className="loading-container">
            <span className="spinner" style={{ width: '32px', height: '32px' }} />
            <span>Loading alerts...</span>
          </div>
        </div>
      ) : (
        <>
          {/* Empty state */}
          {!data || data.content.length === 0 ? (
            <div className="table-container">
              <div className="empty-state">
                <div style={{ fontSize: '2rem', marginBottom: '8px' }}>🔍</div>
                <h3 style={{ color: 'var(--text-primary)', marginBottom: '4px' }}>No alerts found</h3>
                <p style={{ fontSize: '0.875rem' }}>
                  {status || decision ? 'No alerts match the selected filters.' : 'There are currently no fraud alerts recorded.'}
                </p>
              </div>
            </div>
          ) : (
            /* Table */
            <div className="table-container">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Transaction ID</th>
                    <th>Decision</th>
                    <th>Severity</th>
                    <th>Status</th>
                    <th>Fraud Prob</th>
                    <th>Rule Score</th>
                    <th>Created At</th>
                    <th style={{ textAlign: 'right' }}>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((alert) => (
                    <tr
                      key={alert.id}
                      className="clickable-row"
                      onClick={() => navigate(`/alerts/${alert.id}`)}
                    >
                      <td className="monospace">
                        {alert.transactionId}
                      </td>
                      <td>
                        <span className={getDecisionBadgeClass(alert.decision)}>
                          {alert.decision}
                        </span>
                      </td>
                      <td>
                        <span className={getSeverityBadgeClass(alert.severity)}>
                          {alert.severity}
                        </span>
                      </td>
                      <td>
                        <span className={getStatusBadgeClass(alert.status)}>
                          {alert.status}
                        </span>
                      </td>
                      <td style={{ fontWeight: alert.fraudProbability !== null ? '600' : 'normal' }}>
                        {formatProbability(alert.fraudProbability)}
                      </td>
                      <td>
                        {alert.ruleScore}
                      </td>
                      <td style={{ color: 'var(--text-secondary)', fontSize: '0.8125rem' }}>
                        {formatDate(alert.createdAt)}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <button
                          className="btn btn-secondary btn-sm"
                          onClick={(e) => {
                            e.stopPropagation();
                            navigate(`/alerts/${alert.id}`);
                          }}
                        >
                          View
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Pagination Controls */}
          <div style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginTop: '16px',
            padding: '4px 8px',
            fontSize: '0.875rem',
            color: 'var(--text-secondary)'
          }}>
            <div>
              {totalElements} total {totalElements === 1 ? 'alert' : 'alerts'}
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <span>
                {totalPages === 0 ? 'Page 0 of 0' : `Page ${page + 1} of ${totalPages}`}
              </span>

              <div style={{ display: 'flex', gap: '6px' }}>
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={() => setPage((prev) => Math.max(0, prev - 1))}
                  disabled={isFirst || page === 0}
                >
                  Previous
                </button>
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={() => setPage((prev) => prev + 1)}
                  disabled={isLast || (page + 1 >= totalPages)}
                >
                  Next
                </button>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
};
