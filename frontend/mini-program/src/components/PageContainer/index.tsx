import React from 'react';
import { View } from '@tarojs/components';
import styles from './index.module.scss';

interface PageContainerProps {
  children: React.ReactNode;
  className?: string;
  withPadding?: boolean;
}

const PageContainer: React.FC<PageContainerProps> = ({ children, className = '', withPadding = true }) => {
  return (
    <View className={`${styles.container} ${withPadding ? styles.withPadding : ''} ${className}`}>
      {children}
    </View>
  );
};

export default PageContainer;
