import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthContext';
import { getModels, getRetrainingHistory } from '../api/models';
import type { ModelVersionSummary, RetrainingEvent } from '../types/models';
import { buildTimelineElements } from '../utils/historyMarkers';

export const ModelPerformancePage: React.FC = () => {
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  // 1. Fetch Model Versions
  const {
    data: models,
    isLoading: isModelsLoading,
    isError: isModelsError,
    error: modelsError,
    refetch: refetchModels,
  } = useQuery({
    queryKey: ['models'],
    queryFn: getModels,
  });

  // 2. Fetch Retraining History (ADMIN ONLY)
  const {
    data: history,
    isLoading: isHistoryLoading,
    isError: isHistoryError,
    error: historyError,
    refetch: refetchHistory,
  } = useQuery({
    queryKey: ['retrainingHistory'],
    queryFn: () => getRetrainingHistory(50),
    enabled: isAdmin,
  });

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

  const formatMetric = (val: number | null | undefined, decimals = 5): string => {
    if (val === null || val === undefined || isNaN(val)) {
      return '—';
    }
    return Number(val).toFixed(decimals);
  };

  const getTimelineStatus = (event: RetrainingEvent) => {
    if (event.action === 'MODEL_RECONCILED') {
      return {
        label: 'Model Reconciled',
        description: event.reason || `Active model reconciled at startup (${event.activeAfter || event.entityId || 'system'})`,
      };
    }
    if (event.action === 'MODEL_ACTIVATION_MISMATCH_CRITICAL') {
      return {
        label: 'Activation Mismatch (Critical)',
        description: event.reason || 'Critical mismatch during model activation',
      };
    }

    if (event.eligible === false) {
      return {
        label: 'Refused: not eligible',
        description: event.reason || 'Did not meet verdict count retraining gates',
      };
    }

    if (event.eligible === true) {
      if (event.activationGatePassed === true) {
        return {
          label: 'Gate passed: model activated',
          description: event.reason || 'Candidate passed PR-AUC evaluation and was activated',
        };
      }
      if (event.activationGatePassed === false) {
        return {
          label: 'Gate failed: not activated',
          description: event.reason || 'Candidate did not satisfy minimum PR-AUC improvement threshold',
        };
      }
    }

    return {
      label: event.action,
      description: event.reason || 'Lifecycle event recorded',
    };
  };

  if (isModelsLoading) {
    return (
      <div className="table-container" style={{ minHeight: '350px' }}>
        <div className="loading-container">
          <span className="spinner" style={{ width: '32px', height: '32px' }} />
          <span>Loading model performance metrics...</span>
        </div>
      </div>
    );
  }

  if (isModelsError) {
    return (
      <div className="error-banner">
        <div>
          <strong>Error loading model performance:</strong>{' '}
          {modelsError instanceof Error ? modelsError.message : 'Unknown error'}
        </div>
        <button onClick={() => refetchModels()} className="btn btn-secondary btn-sm">
          Retry
        </button>
      </div>
    );
  }

  const modelList: ModelVersionSummary[] = models || [];
  const activeModel = modelList.find((m) => m.active) || modelList[0];

  // Most recent other version
  let otherModel: ModelVersionSummary | undefined;
  if (modelList.length >= 2 && activeModel) {
    const otherModels = modelList
      .filter((m) => m.version !== activeModel.version)
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    otherModel = otherModels[0];
  }

  let absPrAucDiff: number | null = null;
  if (
    activeModel?.metrics?.testPrAuc !== null &&
    activeModel?.metrics?.testPrAuc !== undefined &&
    otherModel?.metrics?.testPrAuc !== null &&
    otherModel?.metrics?.testPrAuc !== undefined
  ) {
    absPrAucDiff = Math.abs(activeModel.metrics.testPrAuc - otherModel.metrics.testPrAuc);
  }

  return (
    <div style={{ maxWidth: '1200px', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: '28px' }}>
      {/* Header */}
      <div>
        <h1 style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)' }}>
          Model Performance & Metrics
        </h1>
        <p style={{ color: 'var(--text-secondary)', marginTop: '4px', fontSize: '0.875rem' }}>
          Evaluate offline test metrics, probability cut-offs, and verdict-driven model retraining history.
        </p>
      </div>

      {/* Empty State */}
      {modelList.length === 0 ? (
        <div className="empty-state">
          <p>No model versions recorded yet.</p>
        </div>
      ) : (
        <>
          {/* Active Model Card */}
          {activeModel && (
            <div
              style={{
                backgroundColor: 'var(--bg-surface)',
                border: '1px solid var(--border-color)',
                borderRadius: '8px',
                padding: '24px',
                display: 'flex',
                flexDirection: 'column',
                gap: '16px',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                  <h2 style={{ fontSize: '1.25rem', fontWeight: '600', color: 'var(--text-primary)', margin: 0 }}>
                    Active Model: <span style={{ fontFamily: 'var(--font-mono)' }}>{activeModel.version}</span>
                  </h2>
                  <span className="badge badge-approve">Active</span>
                </div>
                <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
                  Registered: {formatDate(activeModel.createdAt)}
                </div>
              </div>

              {/* Metrics Grid */}
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                  gap: '16px',
                  marginTop: '8px',
                }}
              >
                <div style={{ backgroundColor: 'var(--bg-surface-elevated)', padding: '14px', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                    Test PR-AUC
                  </div>
                  <div style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)', marginTop: '4px', fontFamily: 'var(--font-mono)' }}>
                    {formatMetric(activeModel.metrics?.testPrAuc)}
                  </div>
                </div>

                <div style={{ backgroundColor: 'var(--bg-surface-elevated)', padding: '14px', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                    Test ROC-AUC
                  </div>
                  <div style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)', marginTop: '4px', fontFamily: 'var(--font-mono)' }}>
                    {formatMetric(activeModel.metrics?.testRocAuc)}
                  </div>
                </div>

                <div style={{ backgroundColor: 'var(--bg-surface-elevated)', padding: '14px', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                    Block Threshold
                  </div>
                  <div style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)', marginTop: '4px', fontFamily: 'var(--font-mono)' }}>
                    {formatMetric(activeModel.metrics?.blockThreshold)}
                  </div>
                </div>

                <div style={{ backgroundColor: 'var(--bg-surface-elevated)', padding: '14px', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                    Review Threshold
                  </div>
                  <div style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--text-primary)', marginTop: '4px', fontFamily: 'var(--font-mono)' }}>
                    {formatMetric(activeModel.metrics?.reviewThreshold)}
                  </div>
                </div>
              </div>

              {/* Short Note */}
              <div style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)', marginTop: '4px' }}>
                Thresholds are ML-only probability cut-offs. The final APPROVE/REVIEW/BLOCK decision also uses the rule score.
              </div>
            </div>
          )}

          {/* Comparison Section (Neutral styling) */}
          {modelList.length >= 2 && activeModel && otherModel && (
            <div
              style={{
                backgroundColor: 'var(--bg-surface)',
                border: '1px solid var(--border-color)',
                borderRadius: '8px',
                padding: '20px',
                display: 'flex',
                flexDirection: 'column',
                gap: '8px',
              }}
            >
              <h3 style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--text-primary)', margin: 0 }}>
                Active version vs. most recent other version
              </h3>
              <div style={{ display: 'flex', alignItems: 'center', gap: '20px', flexWrap: 'wrap', marginTop: '4px' }}>
                <span style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>
                  Active (<span style={{ fontFamily: 'var(--font-mono)' }}>{activeModel.version}</span>) Test PR-AUC:{' '}
                  <strong style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>
                    {formatMetric(activeModel.metrics?.testPrAuc)}
                  </strong>
                </span>
                <span style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>
                  Other (<span style={{ fontFamily: 'var(--font-mono)' }}>{otherModel.version}</span>) Test PR-AUC:{' '}
                  <strong style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>
                    {formatMetric(otherModel.metrics?.testPrAuc)}
                  </strong>
                </span>
                <span style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>
                  Absolute Difference:{' '}
                  <strong style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>
                    {absPrAucDiff !== null ? formatMetric(absPrAucDiff) : '—'}
                  </strong>
                </span>
              </div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontStyle: 'italic', marginTop: '4px', display: 'flex', flexDirection: 'column', gap: '2px' }}>
                <div>Metrics are measured on the fixed synthetic test split. Differences smaller than about 0.005 are within normal run-to-run variation.</div>
                <div>The other version is not necessarily older and may not have been active for long.</div>
              </div>
            </div>
          )}

          {/* Version Table */}
          <div
            style={{
              backgroundColor: 'var(--bg-surface)',
              border: '1px solid var(--border-color)',
              borderRadius: '8px',
              overflow: 'hidden',
            }}
          >
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border-color)' }}>
              <h3 style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--text-primary)', margin: 0 }}>
                Registered Model Versions
              </h3>
            </div>
            <div className="table-responsive">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Version</th>
                    <th>Algorithm</th>
                    <th>Registered</th>
                    <th>Test PR-AUC</th>
                    <th>Test ROC-AUC</th>
                    <th>Val PR-AUC</th>
                    <th>Val ROC-AUC</th>
                    <th>Val Brier</th>
                    <th>Block Cutoff</th>
                    <th>Review Cutoff</th>
                  </tr>
                </thead>
                <tbody>
                  {modelList.map((mv) => (
                    <tr key={mv.version}>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                          <span style={{ fontFamily: 'var(--font-mono)', fontWeight: '600' }}>{mv.version}</span>
                          {mv.active && <span className="badge badge-approve" style={{ fontSize: '0.6875rem' }}>Active</span>}
                        </div>
                      </td>
                      <td style={{ color: 'var(--text-secondary)' }}>{mv.algorithm}</td>
                      <td style={{ color: 'var(--text-secondary)', fontSize: '0.8125rem' }}>{formatDate(mv.createdAt)}</td>
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{formatMetric(mv.metrics?.testPrAuc)}</td>
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{formatMetric(mv.metrics?.testRocAuc)}</td>
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{formatMetric(mv.metrics?.valPrAuc)}</td>
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{formatMetric(mv.metrics?.valRocAuc)}</td>
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{formatMetric(mv.metrics?.valBrier)}</td>
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{formatMetric(mv.metrics?.blockThreshold)}</td>
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{formatMetric(mv.metrics?.reviewThreshold)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{ padding: '12px 20px', fontSize: '0.75rem', color: 'var(--text-muted)', borderTop: '1px solid var(--border-color)' }}>
              Fields shown as — were not recorded for that version.
            </div>
          </div>
        </>
      )}

      {/* Retraining History Section (ADMIN ONLY) */}
      {isAdmin && (
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-color)',
            borderRadius: '8px',
            padding: '24px',
            display: 'flex',
            flexDirection: 'column',
            gap: '16px',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <h3 style={{ fontSize: '1.125rem', fontWeight: '600', color: 'var(--text-primary)', margin: 0 }}>
                Retraining History & Audits
              </h3>
              <p style={{ color: 'var(--text-muted)', fontSize: '0.8125rem', marginTop: '2px' }}>
                Timeline of verdict-driven retraining attempts, gating decisions, and model reconciliation events.
              </p>
            </div>
            <span className="badge" style={{ backgroundColor: 'var(--primary-subtle)', color: 'var(--primary)', borderColor: 'var(--primary)' }}>
              Admin Only
            </span>
          </div>

          {isHistoryLoading ? (
            <div className="loading-container" style={{ padding: '30px 0' }}>
              <span className="spinner" style={{ width: '24px', height: '24px' }} />
              <span>Loading retraining audit history...</span>
            </div>
          ) : isHistoryError ? (
            <div className="error-banner">
              <div>
                <strong>Error loading retraining history:</strong>{' '}
                {historyError instanceof Error ? historyError.message : 'Unknown error'}
              </div>
              <button onClick={() => refetchHistory()} className="btn btn-secondary btn-sm">
                Retry
              </button>
            </div>
          ) : !history || history.length === 0 ? (
            <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.875rem' }}>
              No retraining events recorded yet.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', marginTop: '8px' }}>
              {buildTimelineElements(history, activeModel?.version).map((item, idx) => {
                if (item.type === 'marker') {
                  return (
                    <div
                      key={`marker-${idx}-${item.marker.fromVersion}-${item.marker.toVersion}`}
                      style={{
                        backgroundColor: 'rgba(245, 158, 11, 0.08)',
                        border: '1px dashed rgba(245, 158, 11, 0.4)',
                        borderRadius: '6px',
                        padding: '12px 16px',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '10px',
                        fontSize: '0.8125rem',
                        color: 'var(--color-amber-text)',
                      }}
                    >
                      <span style={{ fontSize: '1rem', lineHeight: 1 }} aria-hidden="true">
                        ⚠
                      </span>
                      <span>{item.marker.message}</span>
                    </div>
                  );
                }

                const event = item.data;
                const status = getTimelineStatus(event);
                const hasVerdicts = event.totalVerdicts !== null && event.totalVerdicts !== undefined;
                const hasCandidatePrAuc = event.candidatePrAuc !== null && event.candidatePrAuc !== undefined;

                return (
                  <div
                    key={`${event.entityId}-${event.createdAt}-${idx}`}
                    style={{
                      backgroundColor: 'var(--bg-surface-elevated)',
                      border: '1px solid var(--border-subtle)',
                      borderRadius: '6px',
                      padding: '16px',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: '8px',
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '8px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                        <span style={{ fontWeight: '600', color: 'var(--text-primary)', fontSize: '0.9375rem' }}>
                          {status.label}
                        </span>
                        <span style={{ fontSize: '0.75rem', fontFamily: 'var(--font-mono)', color: 'var(--text-muted)' }}>
                          [{event.action}]
                        </span>
                      </div>
                      <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                        {formatDate(event.createdAt)}
                      </span>
                    </div>

                    <div style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)', lineHeight: '1.4' }}>
                      {status.description}
                    </div>

                    {(hasVerdicts || hasCandidatePrAuc || event.activeBefore || event.activeAfter) && (
                      <div
                        style={{
                          display: 'flex',
                          flexWrap: 'wrap',
                          gap: '16px',
                          fontSize: '0.75rem',
                          color: 'var(--text-muted)',
                          marginTop: '4px',
                          paddingTop: '8px',
                          borderTop: '1px solid var(--border-subtle)',
                        }}
                      >
                        {hasVerdicts && (
                          <span>
                            Verdicts:{' '}
                            <strong style={{ color: 'var(--text-secondary)' }}>
                              {event.totalVerdicts}
                            </strong>{' '}
                            ({event.fraudCount ?? 0} fraud, {event.legitimateCount ?? 0} legit)
                          </span>
                        )}
                        {hasCandidatePrAuc && (
                          <span>
                            Candidate PR-AUC:{' '}
                            <strong style={{ color: 'var(--text-secondary)', fontFamily: 'var(--font-mono)' }}>
                              {formatMetric(event.candidatePrAuc)}
                            </strong>
                          </span>
                        )}
                        {event.activeBefore && event.activeAfter && (
                          <span>
                            Active Transition:{' '}
                            <span style={{ fontFamily: 'var(--font-mono)' }}>
                              {event.activeBefore} &rarr; {event.activeAfter}
                            </span>
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default ModelPerformancePage;
