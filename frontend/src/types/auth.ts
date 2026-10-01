export interface LoginRequest {
  username: string;
  password: string;
}

export type UserRole = 'ADMIN' | 'ANALYST';

export interface LoginResponse {
  token: string;
  username: string;
  role: UserRole;
}

export interface Authority {
  authority: string;
}

export interface MeResponse {
  username: string;
  role: UserRole;
  authorities: Authority[];
}
