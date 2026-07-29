import React, { useEffect } from 'react';
import { useDidShow } from '@tarojs/taro';
import { useUserStore } from '@/store/user';
import './app.scss';

function App(props: { children: React.ReactNode }) {
  const fetchUserInfo = useUserStore((state) => state.fetchUserInfo);

  useEffect(() => {
    fetchUserInfo();
  }, [fetchUserInfo]);

  useDidShow(() => {
    fetchUserInfo();
  });

  return props.children;
}

export default App;
