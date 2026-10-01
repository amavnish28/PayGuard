import { apiClient } from './client';
import type { AlertDecision, AlertDetail, AlertStatus, AlertSummary, PageResponse } from '../types/alerts';

export interface ListAlertsParams {
  page?: number;
  size?: number;
  status?: AlertStatus;
  decision?: AlertDecision;
}

export const listAlerts = async (params: ListAlertsParams = {}): Promise<PageResponse<AlertSummary>> => {
  const queryParams: Record<string, string | number> = {};

  if (params.page !== undefined) {
    queryParams.page = params.page;
  }
  if (params.size !== undefined) {
    queryParams.size = params.size;
  }
  if (params.status) {
    queryParams.status = params.status;
  }
  if (params.decision) {
    queryParams.decision = params.decision;
  }

  const response = await apiClient.get<PageResponse<AlertSummary>>('/api/v1/alerts', {
    params: queryParams,
  });
  return response.data;
};

export const getAlertDetail = async (id: string): Promise<AlertDetail> => {
  const response = await apiClient.get<AlertDetail>(`/api/v1/alerts/${id}`);
  return response.data;
};
