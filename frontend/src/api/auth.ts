import { apiClient } from './client';
import type { LoginRequest, LoginResponse, MeResponse } from '../types/auth';

export const login = async (req: LoginRequest): Promise<LoginResponse> => {
  const response = await apiClient.post<LoginResponse>('/api/auth/login', req);
  return response.data;
};

export const getCurrentUser = async (): Promise<MeResponse> => {
  const response = await apiClient.get<MeResponse>('/api/auth/me');
  return response.data;
};
