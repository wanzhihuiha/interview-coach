import React, { useEffect, useState } from 'react';
import { View, Text, Button, ScrollView } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { getInterviewReport } from '@/api/interview';
import type { InterviewReport } from '@/api/interview';
import Empty from '@/components/Empty';
import styles from './index.module.scss';

const ReportPage: React.FC = () => {
  const router = useRouter();
  const interviewId = Number(router.params.id);
  const [report, setReport] = useState<InterviewReport | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!interviewId) return;
    setLoading(true);
    getInterviewReport(interviewId)
      .then((res) => setReport(res))
      .catch((err) => {
        console.error('[Report] load failed', err);
        Taro.showToast({ title: '加载失败', icon: 'none' });
      })
      .finally(() => setLoading(false));
  }, [interviewId]);

  if (!report && !loading) {
    return (
      <View className={styles.container}>
        <Empty title="暂无报告" description="该面试尚未生成报告" />
      </View>
    );
  }

  if (!report) {
    return (
      <View className={styles.container}>
        <Text>加载中...</Text>
      </View>
    );
  }

  return (
    <ScrollView scrollY className={styles.container}>
      <View className={styles.scoreCard}>
        <Text className={styles.scoreValue}>{report.totalScore}</Text>
        <Text className={styles.scoreLevel}>{report.level}</Text>
        <Text className={styles.scoreTitle}>{report.positionTitle || '模拟面试'}</Text>
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>维度得分</Text>
        <View className={styles.scoreList}>
          {report.scores.map((score) => (
            <View key={score.name} className={styles.scoreItem}>
              <Text className={styles.scoreName}>{score.name}</Text>
              <Text className={styles.scoreNum}>{score.score}</Text>
            </View>
          ))}
        </View>
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>环节完成情况</Text>
        <View className={styles.list}>
          {report.phaseSummary.map((item, index) => (
            <Text key={index} className={styles.listItem}>{item}</Text>
          ))}
        </View>
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>优势知识点</Text>
        <View>
          {report.strongPoints.map((point, index) => (
            <Text key={index} className={`${styles.tag} ${styles.tagStrong}`}>{point}</Text>
          ))}
        </View>
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>薄弱知识点</Text>
        <View>
          {report.weakPoints.map((point, index) => (
            <Text key={index} className={`${styles.tag} ${styles.tagWeak}`}>{point}</Text>
          ))}
        </View>
      </View>

      <View className={styles.actionBar}>
        <Button
          className={`${styles.btn} ${styles.btnPrimary}`}
          onClick={() => Taro.navigateTo({ url: `/pages/interview/growth/index?id=${interviewId}` })}
        >
          查看成长方案
        </Button>
        <Button
          className={`${styles.btn} ${styles.btnDefault}`}
          onClick={() => Taro.switchTab({ url: '/pages/history/index' })}
        >
          返回历史
        </Button>
      </View>
    </ScrollView>
  );
};

export default ReportPage;
