import React from 'react';
import { View, Text } from '@tarojs/components';
import classnames from 'classnames';
import styles from './index.module.scss';
import type { InterviewPhase } from '@/api/interview';

interface InterviewProgressProps {
  phases: InterviewPhase[];
}

const InterviewProgress: React.FC<InterviewProgressProps> = ({ phases }) => {
  return (
    <View className={styles.container}>
      <Text className={styles.title}>面试进度</Text>
      <View className={styles.list}>
        {phases.map((phase) => (
          <View key={phase.key} className={styles.item}>
            <View
              className={classnames(styles.dot, {
                [styles.completed]: phase.completed,
                [styles.current]: phase.current,
              })}
            />
            <View className={styles.info}>
              <Text className={styles.name}>{phase.name}</Text>
              <Text className={styles.description}>{phase.description}</Text>
            </View>
          </View>
        ))}
      </View>
    </View>
  );
};

export default InterviewProgress;
