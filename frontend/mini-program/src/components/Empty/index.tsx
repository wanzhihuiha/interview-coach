import React from 'react';
import { View, Text } from '@tarojs/components';
import styles from './index.module.scss';

interface EmptyProps {
  title?: string;
  description?: string;
}

const Empty: React.FC<EmptyProps> = ({ title = '暂无数据', description = '' }) => {
  return (
    <View className={styles.empty}>
      <View className={styles.icon}>📭</View>
      <Text className={styles.title}>{title}</Text>
      {description && <Text className={styles.description}>{description}</Text>}
    </View>
  );
};

export default Empty;
