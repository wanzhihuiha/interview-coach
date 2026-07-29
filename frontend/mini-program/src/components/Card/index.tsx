import React from 'react';
import { View } from '@tarojs/components';
import classnames from 'classnames';
import styles from './index.module.scss';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  gradient?: boolean;
  onClick?: () => void;
}

const Card: React.FC<CardProps> = ({ children, className = '', gradient = false, onClick }) => {
  return (
    <View
      className={classnames(styles.card, { [styles.gradient]: gradient }, className)}
      onClick={onClick}
    >
      {children}
    </View>
  );
};

export default Card;
