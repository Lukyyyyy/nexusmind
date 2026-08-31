import { request } from '../request';

/**
 * Login
 *
 * @param email Login email
 * @param password Password
 */
export function fetchLogin(email: string, password: string) {
  return request<Api.Auth.LoginToken>({
    url: '/users/login',
    method: 'post',
    data: {
      email,
      password
    }
  });
}

export function fetchLogout() {
  return request({ url: '/users/logout', method: 'post' });
}

export function fetchRegistrationCode(email: string) {
  return request({ url: '/users/registration-code', method: 'post', data: { email } });
}

export function fetchRegister(email: string, verificationCode: string, password: string) {
  return request({
    url: '/users/register',
    method: 'post',
    data: {
      password,
      email,
      verificationCode
    }
  });
}

export function fetchPasswordResetCode(email: string) {
  return request({ url: '/users/password-reset-code', method: 'post', data: { email } });
}

export function fetchResetPassword(email: string, verificationCode: string, password: string) {
  return request({
    url: '/users/reset-password',
    method: 'post',
    data: { email, verificationCode, password }
  });
}

/** Get user info */
export function fetchGetUserInfo() {
  return request<Api.Auth.UserInfo>({ url: '/users/me' });
}

/**
 * Refresh token
 *
 * @param refreshToken Refresh token
 */
export function fetchRefreshToken(refreshToken: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/refreshToken',
    method: 'post',
    data: {
      refreshToken
    }
  });
}

/**
 * return custom backend error
 *
 * @param code error code
 * @param msg error message
 */
export function fetchCustomBackendError(code: string, msg: string) {
  return request({ url: '/auth/error', params: { code, msg } });
}
