import React from 'react';
import { View, Text, Button } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useUserStore } from '@/store/user';
import { maskPhone } from '@/utils/format';
import styles from './index.module.scss';

const MinePage: React.FC = () => {
  const { userInfo, logout } = useUserStore();

  const handleLogout = () => {
    Taro.showModal({
      title: '提示',
      content: '确定要退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          logout();
          Taro.reLaunch({ url: '/pages/login/index' });
        }
      },
    });
  };

  if (!userInfo) {
    return (
      <View className={styles.container}>
        <View className={styles.header}>
          <View className={styles.profile}>
            <View className={styles.avatar}>👤</View>
            <View className={styles.info}>
              <Text className={styles.name}>未登录</Text>
              <Text className={styles.phone}>登录后体验完整功能</Text>
            </View>
          </View>
        </View>
        <View className={styles.menu}>
          <Button
            className={styles.logoutBtn}
            onClick={() => Taro.navigateTo({ url: '/pages/login/index' })}
          >
            去登录
          </Button>
        </View>
      </View>
    );
  }

  return (
    <View className={styles.container}>
      <View className={styles.header}>
        <View className={styles.profile}>
          <View className={styles.avatar}>👤</View>
          <View className={styles.info}>
            <Text className={styles.name}>{userInfo.nickname || userInfo.username}</Text>
            <Text className={styles.phone}>{maskPhone(userInfo.phone)}</Text>
          </View>
        </View>
      </View>

      <View className={styles.menu}>
        <View className={styles.menuCard}>
          <View className={styles.menuItem} onClick={() => Taro.navigateTo({ url: '/pages/resume/list/index' })}>
            <View className={styles.menuLeft}>
              <Text className={styles.menuIcon}>📄</Text>
              <Text className={styles.menuText}>我的简历</Text>
            </View>
            <Text className={styles.menuArrow}>›</Text>
          </View>
          <View className={styles.menuItem} onClick={() => Taro.navigateTo({ url: '/pages/position/list/index' })}>
            <View className={styles.menuLeft}>
              <Text className={styles.menuIcon}>💼</Text>
              <Text className={styles.menuText}>目标岗位</Text>
            </View>
            <Text className={styles.menuArrow}>›</Text>
          </View>
          <View className={styles.menuItem} onClick={() => Taro.switchTab({ url: '/pages/history/index' })}>
            <View className={styles.menuLeft}>
              <Text className={styles.menuIcon}>📊</Text>
              <Text className={styles.menuText}>面试历史</Text>
            </View>
            <Text className={styles.menuArrow}>›</Text>
          </View>
        </View>

        <Button className={styles.logoutBtn} onClick={handleLogout}>
          退出登录
        </Button>
      </View>
    </View>
  );
};

export default MinePage;
