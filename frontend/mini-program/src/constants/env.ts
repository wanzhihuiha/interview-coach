import { getToken } from '@/utils/storage';

/**
 * 小程序运行环境常量
 *
 * isMockEnabled：是否在接口失败时返回本地 Mock 数据。
 * - 开发/H5 预览阶段，且当前为演示账号（未登录或 demo token）时启用，
 *   方便在云预览沙箱无法访问 localhost 后端时仍能测试页面。
 * - 真实用户登录后（token 非 demo token），即使接口失败也不走 Mock，
 *   确保首页等页面使用真实接口数据。
 */
const DEMO_TOKEN = 'mock-token-for-preview-only';

const isDev = process.env.NODE_ENV === 'development';
const isH5 = process.env.TARO_ENV === 'h5';

function isKnownCloudPreview(): boolean {
  if (typeof window === 'undefined') return false;
  const host = window.location.hostname;
  return (
    /apigateway.*\.volceapi\.com$/.test(host) ||
    host.endsWith('.aipa-cloud.bytedance.net') ||
    host.includes('aipa-cloud')
  );
}

function isDemoAccount(): boolean {
  const token = getToken();
  return !token || token === DEMO_TOKEN;
}

export function isMockEnabled(): boolean {
  const envEnabled = isDev || (isH5 && isKnownCloudPreview());
  return envEnabled && isDemoAccount();
}
