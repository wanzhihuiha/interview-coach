import { create } from 'zustand';
import type { UserInfo } from '@/api/auth';
import { getCurrentUser } from '@/api/auth';
import { getStoredUser, setStoredUser, removeStoredUser, getToken, removeToken } from '@/utils/storage';

interface UserState {
  userInfo: UserInfo | null;
  loading: boolean;
  setUserInfo: (user: UserInfo | null) => void;
  fetchUserInfo: () => Promise<void>;
  logout: () => void;
}

export const useUserStore = create<UserState>((set) => ({
  userInfo: getStoredUser<UserInfo>() || null,
  loading: false,
  setUserInfo: (user) => {
    set({ userInfo: user });
    if (user) {
      setStoredUser(user);
    } else {
      removeStoredUser();
    }
  },
  fetchUserInfo: async () => {
    if (!getToken()) return;
    set({ loading: true });
    try {
      const res = await getCurrentUser();
      set({ userInfo: res.data });
      setStoredUser(res.data);
    } catch (err) {
      console.error('[UserStore] fetchUserInfo failed', err);
    } finally {
      set({ loading: false });
    }
  },
  logout: () => {
    removeToken();
    removeStoredUser();
    set({ userInfo: null });
  },
}));
