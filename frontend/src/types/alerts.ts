export type AlertDecision = 'APPROVE' | 'REVIEW' | 'BLOCK';

export type SeverityLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type AlertStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED';

export interface AlertSummary {
  id: string; // UUID
  transactionId: string; // human-readable string
  decision: AlertDecision;
  severity: SeverityLevel;
  status: AlertStatus;
  fraudProbability: number | null;
  ruleScore: number;
  createdAt: string; // ISO-8601
}

export interface ShapReason {
  feature: string;
  value: number;
  contribution: number;
  direction: string;
}

export interface AlertExplanation {
  mlBand: string | null;
  ruleTier: string | null;
  ruleScore: number;
  mlAvailable: boolean;
  ruleReasons: string[] | null;
  shapReasons: ShapReason[] | null;
  triggeredRules: string[] | null;
  fraudProbability: number | null;
}

export interface AlertVerdictSummary {
  id: string;
  verdict: 'FRAUD' | 'LEGITIMATE';
  comment: string | null;
  analystUsername: string;
  createdAt: string;
}

export interface AlertDetail extends AlertSummary {
  finalScore: number | null;
  explanation: AlertExplanation;
  updatedAt: string; // ISO-8601
  amount: number;
  currency: string;
  deviceId: string;
  location: string;
  merchantType: string;
  transactionTimestamp: string; // ISO-8601
  verdict?: AlertVerdictSummary | null;
}

export interface PageResponse<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  first: boolean;
  last: boolean;
  size: number;
  number: number; // 0-indexed current page
  numberOfElements: number;
  empty: boolean;
}
