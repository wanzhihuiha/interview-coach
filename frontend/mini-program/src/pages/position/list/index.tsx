import React, { useEffect, useState } from 'react';
import { View, Text, ScrollView } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { getAccessiblePositionList } from '@/api/position';
import type { Position } from '@/api/position';
import Empty from '@/components/Empty';
import styles from './index.module.scss';

const PositionListPage: React.FC = () => {
  const [positions, setPositions] = useState<Position[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchPositions = async () => {
    setLoading(true);
    try {
      const res = await getAccessiblePositionList({ size: 50 });
      setPositions(res);
    } catch (err) {
      console.error('[PositionList] fetch failed', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchPositions();
  }, []);

  return (
    <ScrollView scrollY className={styles.container} refresherTriggered={loading} onRefresherRefresh={fetchPositions}>
      {positions.length === 0 ? (
        <Empty title="暂无岗位" description="请在网页端添加岗位信息" />
      ) : (
        positions.map((position) => (
          <View
            key={position.positionId}
            className={styles.card}
            onClick={() => Taro.navigateTo({ url: `/pages/position/detail/index?id=${position.positionId}` })}
          >
            <Text className={styles.title}>{position.positionName}</Text>
            <Text className={styles.company}>{position.companyName || '未知公司'}</Text>
            <Text className={styles.meta}>{position.jobCategory} {position.level ? `· ${position.level}` : ''} {position.location ? `· ${position.location}` : ''}</Text>
          </View>
        ))
      )}
    </ScrollView>
  );
};

export default PositionListPage;
