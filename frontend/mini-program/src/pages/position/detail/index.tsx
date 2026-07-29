import React, { useEffect, useState } from 'react';
import { View, Text, ScrollView } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { getPositionDetail } from '@/api/position';
import type { Position } from '@/api/position';
import styles from './index.module.scss';

const PositionDetailPage: React.FC = () => {
  const router = useRouter();
  const positionId = Number(router.params.id);
  const [position, setPosition] = useState<Position | null>(null);

  useEffect(() => {
    if (!positionId) return;
    getPositionDetail(positionId)
      .then((res) => setPosition(res))
      .catch((err) => {
        console.error('[PositionDetail] load failed', err);
        Taro.showToast({ title: '加载失败', icon: 'none' });
      });
  }, [positionId]);

  if (!position) {
    return (
      <View className={styles.container}>
        <Text className={styles.empty}>加载中...</Text>
      </View>
    );
  }

  return (
    <ScrollView scrollY className={styles.container}>
      <View className={styles.card}>
        <Text className={styles.title}>{position.positionName}</Text>
        <Text className={styles.company}>{position.companyName || '未知公司'}</Text>
        <View className={styles.tags}>
          <Text className={styles.tag}>{position.jobCategory}</Text>
          {position.level && <Text className={styles.tag}>{position.level}</Text>}
          {position.location && <Text className={styles.tag}>{position.location}</Text>}
          {position.salaryRange && <Text className={styles.tag}>{position.salaryRange}</Text>}
        </View>
      </View>

      <View className={styles.card}>
        <Text className={styles.sectionTitle}>岗位描述</Text>
        <Text className={styles.jd}>{position.jdContent || '暂无岗位描述'}</Text>
      </View>
    </ScrollView>
  );
};

export default PositionDetailPage;
