import { apiClient } from './client';
import type { VerdictRequest, VerdictResponse } from '../types/verdict';

export const submitVerdict = async (alertId: string, request: VerdictRequest): Promise<VerdictResponse> => {
  const response = await apiClient.post<VerdictResponse>(`/api/v1/alerts/${alertId}/verdict`, request);
  return response.data;
};
