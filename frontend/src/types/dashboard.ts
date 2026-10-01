export interface DecisionCounts {
  approve: number;
  review: number;
  block: number;
}

export interface StatusCounts {
  open: number;
  inReview: number;
  resolved: number;
}

export interface SeverityCounts {
  low: number;
  medium: number;
  high: number;
  critical: number;
  unclassified: number;
}

export interface WindowedStats {
  sinceTimestamp: string;
  transactionsInWindow: number;
  alertsInWindow: number;
  blockCountInWindow: number;
  reviewCountInWindow: number;
}

export interface DashboardSummaryResponse {
  totalTransactions: number;
  totalAlerts: number;
  decisionCounts: DecisionCounts;
  statusCounts: StatusCounts;
  severityCounts: SeverityCounts;
  windowed: WindowedStats;
  mlAvailabilityRate: number | null;
}
