import React, { useState } from 'react';
import { View, Text, Input, Button } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useUserStore } from '@/store/user';
import { login, register, loginAsDemo } from '@/api/auth';
import { setToken } from '@/utils/storage';
import { isMockEnabled } from '@/constants/env';
import styles from './index.module.scss';

const LoginPage: React.FC = () => {
  const [isLogin, setIsLogin] = useState(true);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [smsCode, setSmsCode] = useState('');
  const [loading, setLoading] = useState(false);
  const setUserInfo = useUserStore((state) => state.setUserInfo);

  const handleSubmit = async () => {
    if (!username.trim() || !password.trim()) {
      Taro.showToast({ title: '请输入用户名和密码', icon: 'none' });
      return;
    }
    if (!isLogin) {
      if (!phone.trim()) {
        Taro.showToast({ title: '请输入手机号', icon: 'none' });
        return;
      }
      if (password !== confirmPassword) {
        Taro.showToast({ title: '两次密码不一致', icon: 'none' });
        return;
      }
    }

    setLoading(true);
    try {
      const res = isLogin
        ? await login({ username: username.trim(), password })
        : await register({ username: username.trim(), phone: phone.trim(), password, confirmPassword, smsCode: '000000' });
      setToken(res.data.token);
      setUserInfo(res.data.user);
      Taro.showToast({ title: isLogin ? '登录成功' : '注册成功', icon: 'success' });
      setTimeout(() => {
        Taro.switchTab({ url: '/pages/home/index' });
      }, 1000);
    } catch (err) {
      Taro.showToast({ title: (err as Error).message || '操作失败', icon: 'none' });
    } finally {
      setLoading(false);
    }
  };

  const handleDemoLogin = async () => {
    const res = await loginAsDemo();
    if (!res) {
      Taro.showToast({ title: '演示登录仅在开发模式可用', icon: 'none' });
      return;
    }
    setToken(res.data.token);
    setUserInfo(res.data.user);
    Taro.showToast({ title: '演示登录成功', icon: 'success' });
    setTimeout(() => {
      Taro.switchTab({ url: '/pages/home/index' });
    }, 1000);
  };

  return (
    <View className={styles.container}>
      <View className={styles.logoArea}>
        <View className={styles.logo}>💼</View>
        <Text className={styles.title}>面试教练</Text>
        <Text className={styles.subtitle}>AI 驱动的模拟面试助手</Text>
      </View>

      <View className={styles.form}>
        <View className={styles.inputItem}>
          <Text className={styles.label}>用户名</Text>
          <Input
            className={styles.input}
            placeholder="请输入用户名"
            value={username}
            onInput={(e) => setUsername(e.detail.value)}
          />
        </View>

        {!isLogin && (
          <View className={styles.inputItem}>
            <Text className={styles.label}>手机号</Text>
            <Input
              className={styles.input}
              placeholder="请输入手机号"
              value={phone}
              onInput={(e) => setPhone(e.detail.value)}
            />
          </View>
        )}

        <View className={styles.inputItem}>
          <Text className={styles.label}>密码</Text>
          <Input
            className={styles.input}
            placeholder="请输入密码"
            password
            value={password}
            onInput={(e) => setPassword(e.detail.value)}
          />
        </View>

        {!isLogin && (
          <View className={styles.inputItem}>
            <Text className={styles.label}>确认密码</Text>
            <Input
              className={styles.input}
              placeholder="请再次输入密码"
              password
              value={confirmPassword}
              onInput={(e) => setConfirmPassword(e.detail.value)}
            />
          </View>
        )}

        <Button className={styles.submitBtn} onClick={handleSubmit} disabled={loading}>
          {loading ? '请稍候...' : isLogin ? '登录' : '注册'}
        </Button>

        {isMockEnabled() && (
          <Button className={styles.demoBtn} onClick={handleDemoLogin}>
            演示登录（免后端）
          </Button>
        )}
      </View>

      <View className={styles.switchMode}>
        <Text>{isLogin ? '还没有账号？' : '已有账号？'}</Text>
        <Text className={styles.link} onClick={() => setIsLogin(!isLogin)}>
          {isLogin ? '立即注册' : '去登录'}
        </Text>
      </View>
    </View>
  );
};

export default LoginPage;
