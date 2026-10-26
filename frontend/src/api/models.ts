import { apiClient } from './client';
import type { ModelVersionSummary, RetrainingEvent } from '../types/models';

export const getModels = async (): Promise<ModelVersionSummary[]> => {
  const response = await apiClient.get<ModelVersionSummary[]>('/api/v1/models');
  return response.data;
};

export const getRetrainingHistory = async (limit: number = 50): Promise<RetrainingEvent[]> => {
  const response = await apiClient.get<RetrainingEvent[]>('/api/v1/admin/retraining-history', {
    params: { limit },
  });
  return response.data;
};
