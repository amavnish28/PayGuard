export type VerdictType = 'FRAUD' | 'LEGITIMATE';

export interface VerdictRequest {
  verdict: VerdictType;
  comment?: string;
}

export interface VerdictResponse {
  id: string;
  alertId: string;
  verdict: VerdictType;
  comment: string | null;
  analystUsername: string;
  createdAt: string;
}
