import React from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import { getAlertDetail } from '../api/alerts';
import type { AlertDecision, AlertStatus } from '../types/alerts';

export const AlertDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();

  const {
    data: alert,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['alert', id],
    queryFn: () => getAlertDetail(id!),
    enabled: !!id,
  });

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

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return '—';
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

  const formatCurrency = (amount?: number, currency?: string) => {
    if (amount === undefined || amount === null) return '—';
    return `${currency || 'USD'} ${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  };

  if (isLoading) {
    return (
      <div className="loading-container" style={{ minHeight: '400px' }}>
        <span className="spinner" style={{ width: '32px', height: '32px' }} />
        <span>Loading alert details...</span>
      </div>
    );
  }

  if (isError) {
    const is404 = axios.isAxiosError(error) && error.response?.status === 404;

    if (is404) {
      return (
        <div style={{ maxWidth: '600px', margin: '40px auto', textAlign: 'center' }}>
          <div className="card">
            <h2 style={{ fontSize: '1.25rem', color: 'var(--text-primary)', marginBottom: '8px' }}>
              Alert Not Found
            </h2>
            <p style={{ color: 'var(--text-secondary)', marginBottom: '20px', fontSize: '0.875rem' }}>
              The alert with ID <code className="monospace">{id}</code> could not be found.
            </p>
            <Link to="/alerts" className="btn btn-primary btn-sm">
              ← Back to Alerts
            </Link>
          </div>
        </div>
      );
    }

    return (
      <div>
        <Link to="/alerts" style={{ display: 'inline-block', marginBottom: '16px', fontSize: '0.875rem' }}>
          ← Back to Alerts
        </Link>
        <div className="error-banner">
          <div>
            <strong>Error loading alert:</strong> {error instanceof Error ? error.message : 'Unknown error'}
          </div>
          <button onClick={() => refetch()} className="btn btn-secondary btn-sm">
            Retry
          </button>
        </div>
      </div>
    );
  }

  if (!alert) {
    return null;
  }

  const { explanation } = alert;

  return (
    <div style={{ maxWidth: '1200px', margin: '0 auto' }}>
      {/* Back button */}
      <div style={{ marginBottom: '16px' }}>
        <Link
          to="/alerts"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '6px',
            fontSize: '0.875rem',
            color: 'var(--text-secondary)',
            textDecoration: 'none'
          }}
        >
          ← Back to Alerts
        </Link>
      </div>

      {/* Header Panel */}
      <div className="card" style={{ marginBottom: '24px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '16px' }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap', marginBottom: '8px' }}>
              <h1 style={{ fontSize: '1.25rem', fontWeight: '700', color: 'var(--text-primary)' }}>
                Alert: <span className="monospace" style={{ fontSize: '1.125rem' }}>{alert.id}</span>
              </h1>
              <span className={getDecisionBadgeClass(alert.decision)}>{alert.decision}</span>
              <span className={getSeverityBadgeClass(alert.severity)}>{alert.severity}</span>
              <span className={getStatusBadgeClass(alert.status)}>{alert.status}</span>
            </div>
            <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
              Created: <span style={{ color: 'var(--text-secondary)' }}>{formatDate(alert.createdAt)}</span>
              {' • '}
              Updated: <span style={{ color: 'var(--text-secondary)' }}>{formatDate(alert.updatedAt)}</span>
            </div>
          </div>

          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
              Final Score
            </div>
            <div style={{ fontSize: '1.75rem', fontWeight: '800', color: 'var(--text-primary)' }}>
              {alert.finalScore !== null && alert.finalScore !== undefined ? alert.finalScore : '—'}
            </div>
          </div>
        </div>
      </div>

      {/* 2-Column Grid */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))',
        gap: '24px',
        alignItems: 'start'
      }}>
        {/* Left Column: Transaction Details & Rule Evaluation */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
          {/* Transaction Details */}
          <div className="card">
            <h2 style={{ fontSize: '1rem', fontWeight: '600', marginBottom: '16px', color: 'var(--text-primary)', borderBottom: '1px solid var(--border-color)', paddingBottom: '8px' }}>
              Transaction Details
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '0.875rem' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Transaction ID</span>
                <span className="monospace">{alert.transactionId}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Amount & Currency</span>
                <span style={{ fontWeight: '600' }}>{formatCurrency(alert.amount, alert.currency)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Device ID</span>
                <span className="monospace" style={{ fontSize: '0.75rem' }}>{alert.deviceId || '—'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Location</span>
                <span>{alert.location || '—'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Merchant Type</span>
                <span>{alert.merchantType || '—'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Transaction Timestamp</span>
                <span>{formatDate(alert.transactionTimestamp)}</span>
              </div>
            </div>
          </div>

          {/* Rule Evaluation */}
          <div className="card">
            <h2 style={{ fontSize: '1rem', fontWeight: '600', marginBottom: '16px', color: 'var(--text-primary)', borderBottom: '1px solid var(--border-color)', paddingBottom: '8px' }}>
              Rule Evaluation
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '0.875rem' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Rule Score</span>
                <span style={{ fontWeight: '600' }}>{alert.ruleScore}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Rule Tier</span>
                <span style={{ fontWeight: '600', color: 'var(--text-primary)' }}>{explanation?.ruleTier || '—'}</span>
              </div>

              <div style={{ marginTop: '8px' }}>
                <span style={{ color: 'var(--text-secondary)', display: 'block', marginBottom: '8px' }}>
                  Triggered Rules:
                </span>
                {explanation?.triggeredRules && explanation.triggeredRules.length > 0 ? (
                  <ul style={{ paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
                    {explanation.triggeredRules.map((rule, idx) => (
                      <li key={idx} style={{ color: 'var(--color-amber-text)' }}>
                        {rule}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <span style={{ color: 'var(--text-muted)' }}>None</span>
                )}
              </div>

              {explanation?.ruleReasons && explanation.ruleReasons.length > 0 && (
                <div style={{ marginTop: '4px' }}>
                  <span style={{ color: 'var(--text-secondary)', display: 'block', marginBottom: '8px' }}>
                    Rule Reasons:
                  </span>
                  <ul style={{ paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '0.8125rem', color: 'var(--text-secondary)' }}>
                    {explanation.ruleReasons.map((reason, idx) => (
                      <li key={idx}>{reason}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Right Column: ML Evaluation & SHAP */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
          <div className="card">
            <h2 style={{ fontSize: '1rem', fontWeight: '600', marginBottom: '16px', color: 'var(--text-primary)', borderBottom: '1px solid var(--border-color)', paddingBottom: '8px' }}>
              ML Evaluation
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '0.875rem' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ color: 'var(--text-secondary)' }}>ML Available</span>
                <span>
                  {explanation?.mlAvailable ? (
                    <span className="badge badge-resolved" style={{ fontSize: '0.7rem' }}>Yes</span>
                  ) : (
                    <span className="badge badge-dismissed" style={{ fontSize: '0.7rem' }}>No (rules-only fallback)</span>
                  )}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Fraud Probability</span>
                <span style={{ fontWeight: '600' }}>
                  {alert.fraudProbability !== null && alert.fraudProbability !== undefined
                    ? `${(alert.fraudProbability * 100).toFixed(1)}%`
                    : 'N/A'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>ML Band</span>
                <span>{explanation?.mlBand || '—'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Final Score</span>
                <span style={{ fontWeight: '600' }}>
                  {alert.finalScore !== null && alert.finalScore !== undefined ? alert.finalScore : '—'}
                </span>
              </div>
            </div>

            {/* SHAP Top Reasons */}
            <div style={{ marginTop: '24px' }}>
              <h3 style={{ fontSize: '0.875rem', fontWeight: '600', marginBottom: '12px', color: 'var(--text-secondary)' }}>
                SHAP Top Reasons
              </h3>
              {explanation?.shapReasons && explanation.shapReasons.length > 0 ? (
                <div className="table-container">
                  <table className="data-table" style={{ fontSize: '0.8125rem' }}>
                    <thead>
                      <tr>
                        <th>Feature</th>
                        <th>Value</th>
                        <th>Contribution</th>
                        <th>Direction</th>
                      </tr>
                    </thead>
                    <tbody>
                      {explanation.shapReasons.map((reason, idx) => (
                        <tr key={idx}>
                          <td className="monospace">{reason.feature}</td>
                          <td>{typeof reason.value === 'number' ? reason.value.toFixed(2) : reason.value}</td>
                          <td style={{ fontWeight: '600' }}>
                            {typeof reason.contribution === 'number' ? reason.contribution.toFixed(4) : reason.contribution}
                          </td>
                          <td>
                            <span style={{
                              color: reason.direction?.toUpperCase() === 'INCREASES_RISK' || reason.direction?.toUpperCase() === 'POSITIVE'
                                ? 'var(--color-red-text)'
                                : 'var(--color-green-text)'
                            }}>
                              {reason.direction}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div style={{ padding: '16px', backgroundColor: 'var(--bg-app)', borderRadius: '6px', textAlign: 'center', fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
                  No SHAP explanations available
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
