import React, { useEffect, useState } from 'react';
import { View, Text, Button, ScrollView } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { getInterviewHistory } from '@/api/interview';
import type { InterviewSession } from '@/api/interview';
import Empty from '@/components/Empty';
import styles from './index.module.scss';
import classnames from 'classnames';

const HistoryPage: React.FC = () => {
  const [interviews, setInterviews] = useState<InterviewSession[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchHistory = async () => {
    setLoading(true);
    try {
      const res = await getInterviewHistory();
      setInterviews(res);
    } catch (err) {
      console.error('[History] fetch failed', err);
      Taro.showToast({ title: '加载失败', icon: 'none' });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHistory();
  }, []);

  const getStatusClass = (status: string) => {
    switch (status) {
      case 'completed': return styles.statusCompleted;
      case 'interrupted': return styles.statusInterrupted;
      default: return styles.statusOngoing;
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case 'completed': return '已完成';
      case 'interrupted': return '已中断';
      default: return '进行中';
    }
  };

  return (
    <ScrollView scrollY className={styles.container} refresherTriggered={loading} onRefresherRefresh={fetchHistory}>
      {interviews.length === 0 ? (
        <Empty title="暂无面试记录" description="快去首页开始一场模拟面试吧" />
      ) : (
        <View className={styles.list}>
          {interviews.map((interview) => (
            <View key={interview.id} className={styles.card}>
              <View className={styles.cardHeader}>
                <Text className={styles.title}>{interview.positionTitle || '模拟面试'}</Text>
                <Text className={classnames(styles.status, getStatusClass(interview.status))}>
                  {getStatusText(interview.status)}
                </Text>
              </View>
              <Text className={styles.meta}>{interview.startTime || '-'}</Text>
              <View className={styles.footer}>
                <Text className={styles.score}>{interview.score ? `${interview.score} · ${interview.level}` : '暂无评分'}</Text>
                <View className={styles.actions}>
                  {interview.status === 'ongoing' && (
                    <Button
                      className={classnames(styles.btn, styles.btnPrimary)}
                      onClick={() => Taro.navigateTo({ url: `/pages/interview/chat/index?id=${interview.id}` })}
                    >
                      继续
                    </Button>
                  )}
                  {interview.status === 'completed' && (
                    <Button
                      className={classnames(styles.btn, styles.btnPrimary)}
                      onClick={() => Taro.navigateTo({ url: `/pages/interview/report/index?id=${interview.id}` })}
                    >
                      报告
                    </Button>
                  )}
                </View>
              </View>
            </View>
          ))}
        </View>
      )}
    </ScrollView>
  );
};

export default HistoryPage;
