import { apiClient } from './client';
import type { DashboardSummaryResponse } from '../types/dashboard';

export const getDashboardSummary = async (sinceHours?: number): Promise<DashboardSummaryResponse> => {
  const params: Record<string, number> = {};
  if (sinceHours !== undefined) {
    params.sinceHours = sinceHours;
  }
  const response = await apiClient.get<DashboardSummaryResponse>('/api/v1/dashboard/summary', { params });
  return response.data;
};
