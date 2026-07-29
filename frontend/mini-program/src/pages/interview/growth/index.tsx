import React, { useEffect, useState } from 'react';
import { View, Text, ScrollView } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { getGrowthPlan } from '@/api/interview';
import type { GrowthPlan } from '@/api/interview';
import Empty from '@/components/Empty';
import styles from './index.module.scss';

const GrowthPage: React.FC = () => {
  const router = useRouter();
  const interviewId = Number(router.params.id);
  const [plan, setPlan] = useState<GrowthPlan | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!interviewId) return;
    setLoading(true);
    getGrowthPlan(interviewId)
      .then((res) => setPlan(res))
      .catch((err) => {
        console.error('[Growth] load failed', err);
        Taro.showToast({ title: '加载失败', icon: 'none' });
      })
      .finally(() => setLoading(false));
  }, [interviewId]);

  if (!plan && !loading) {
    return (
      <View className={styles.container}>
        <Empty title="暂无成长方案" description="该面试尚未生成成长方案" />
      </View>
    );
  }

  if (!plan) {
    return (
      <View className={styles.container}>
        <Text>加载中...</Text>
      </View>
    );
  }

  return (
    <ScrollView scrollY className={styles.container}>
      <View className={styles.card}>
        <Text className={styles.cardTitle}>学习路径</Text>
        {plan.learningPath.map((item, index) => (
          <View key={index} className={styles.pathItem}>
            <Text className={styles.pathTitle}>{item.phase}</Text>
            {item.goal && <Text className={styles.pathGoal}>{item.goal}</Text>}
            {item.tasks.map((task, idx) => (
              <Text key={idx} className={styles.task}>· {task}</Text>
            ))}
          </View>
        ))}
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>知识薄弱点</Text>
        {plan.knowledgeGaps.map((gap, index) => (
          <View key={index} className={styles.gapItem}>
            <Text className={styles.gapTitle}>{gap.topic}</Text>
            <Text className={styles.gapDesc}>{gap.description}</Text>
            <Text className={styles.importance}>{gap.importance}</Text>
            <View style={{ marginTop: '8rpx' }}>
              {gap.keywords.map((keyword, idx) => (
                <Text key={idx} className={styles.keyword}>{keyword}</Text>
              ))}
            </View>
          </View>
        ))}
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>推荐练习</Text>
        {plan.exercises.map((exercise, index) => (
          <View key={index} className={styles.exerciseItem}>
            <Text className={styles.exerciseTitle}>{exercise.title}</Text>
            <Text className={styles.exerciseContent}>{exercise.content}</Text>
          </View>
        ))}
      </View>
    </ScrollView>
  );
};

export default GrowthPage;
