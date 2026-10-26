export interface ModelMetricsSummary {
  testPrAuc: number | null;
  testRocAuc: number | null;
  valPrAuc: number | null;
  valRocAuc: number | null;
  valBrier: number | null;
  blockThreshold: number | null;
  reviewThreshold: number | null;
}

export interface ModelVersionSummary {
  version: string;
  modelName: string;
  algorithm: string;
  active: boolean;
  createdAt: string;
  metrics: ModelMetricsSummary;
}

export interface RetrainingEvent {
  action: string;
  entityId: string;
  createdAt: string;
  eligible: boolean | null;
  activationGatePassed: boolean | null;
  reason: string | null;
  activeBefore: string | null;
  activeAfter: string | null;
  totalVerdicts: number | null;
  fraudCount: number | null;
  legitimateCount: number | null;
  candidatePrAuc: number | null;
}
