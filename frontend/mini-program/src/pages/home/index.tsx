import React, { useEffect, useState } from 'react';
import { View, Text, Button, ScrollView } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useUserStore } from '@/store/user';
import { getResumeList } from '@/api/resume';
import { getAccessiblePositionList } from '@/api/position';
import { getInterviewHistory } from '@/api/interview';
import type { Resume } from '@/api/resume';
import type { Position } from '@/api/position';
import type { InterviewSession } from '@/api/interview';
import { formatDate } from '@/utils/format';
import styles from './index.module.scss';

const HomePage: React.FC = () => {
  const userInfo = useUserStore((state) => state.userInfo);
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [positions, setPositions] = useState<Position[]>([]);
  const [interviews, setInterviews] = useState<InterviewSession[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [rList, pList, iList] = await Promise.all([
        getResumeList().catch(() => []),
        getAccessiblePositionList({ size: 5 }).catch(() => []),
        getInterviewHistory().catch(() => []),
      ]);
      setResumes(rList.slice(0, 2));
      setPositions(pList.slice(0, 3));
      setInterviews(iList.slice(0, 3));
    } catch (err) {
      console.error('[Home] fetchData failed', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const handleStartInterview = () => {
    Taro.navigateTo({ url: '/pages/interview/config/index' });
  };

  const handleViewHistory = () => {
    Taro.switchTab({ url: '/pages/history/index' });
  };

  const handleViewReport = (id: number) => {
    Taro.navigateTo({ url: `/pages/interview/report/index?id=${id}` });
  };

  return (
    <ScrollView scrollY className={styles.container} refresherTriggered={loading} onRefresherRefresh={fetchData}>
      <View className={styles.welcomeCard}>
        <Text className={styles.welcomeTitle}>
          👋 欢迎，{userInfo?.nickname || userInfo?.username || '候选人'}
        </Text>
        <Text className={styles.welcomeDesc}>开始一场模拟面试，提升你的面试能力</Text>
        <View className={styles.actionBar}>
          <Button className={styles.primaryBtn} onClick={handleStartInterview}>
            开始面试
          </Button>
          <Button className={styles.secondaryBtn} onClick={handleViewHistory}>
            查看历史
          </Button>
        </View>
      </View>

      <View className={styles.section}>
        <View className={styles.sectionHeader}>
          <Text className={styles.sectionTitle}>快捷入口</Text>
        </View>
        <View className={styles.gridCards}>
          <View className={styles.gridCard} onClick={() => Taro.navigateTo({ url: '/pages/resume/list/index' })}>
            <View className={styles.gridIcon}>📄</View>
            <Text className={styles.gridLabel}>我的简历</Text>
          </View>
          <View className={styles.gridCard} onClick={() => Taro.navigateTo({ url: '/pages/position/list/index' })}>
            <View className={styles.gridIcon}>💼</View>
            <Text className={styles.gridLabel}>目标岗位</Text>
          </View>
          <View className={styles.gridCard} onClick={handleStartInterview}>
            <View className={styles.gridIcon}>🎯</View>
            <Text className={styles.gridLabel}>开始面试</Text>
          </View>
        </View>
      </View>

      <View className={styles.section}>
        <View className={styles.sectionHeader}>
          <Text className={styles.sectionTitle}>我的简历</Text>
          <Text className={styles.sectionLink} onClick={() => Taro.navigateTo({ url: '/pages/resume/list/index' })}>
            查看全部
          </Text>
        </View>
        <View className={styles.cardList}>
          {resumes.map((resume) => (
            <View key={resume.resumeId} className={styles.infoCard} onClick={() => Taro.navigateTo({ url: '/pages/resume/list/index' })}>
              <Text className={styles.cardTitle}>{resume.fileName}</Text>
              <Text className={styles.cardMeta}>
                {resume.jobCategory || '-'} · {resume.status === 'CONFIRMED' ? '已确认' : '待确认'}
              </Text>
              <Text className={styles.cardDate}>{formatDate(resume.createdAt)}</Text>
            </View>
          ))}
          <View className={styles.addCard} onClick={() => Taro.navigateTo({ url: '/pages/resume/list/index' })}>
            <Text className={styles.addIcon}>+</Text>
            <Text className={styles.addText}>上传新简历</Text>
          </View>
        </View>
      </View>

      <View className={styles.section}>
        <View className={styles.sectionHeader}>
          <Text className={styles.sectionTitle}>目标岗位</Text>
          <Text className={styles.sectionLink} onClick={() => Taro.navigateTo({ url: '/pages/position/list/index' })}>
            查看全部
          </Text>
        </View>
        <View className={styles.cardList}>
          {positions.map((position) => (
            <View key={position.positionId} className={styles.infoCard} onClick={() => Taro.navigateTo({ url: '/pages/position/list/index' })}>
              <Text className={styles.cardTitle}>{position.positionName}</Text>
              <Text className={styles.cardMeta}>{position.companyName || '未知公司'}</Text>
              <Text className={styles.cardDate}>{position.jobCategory}</Text>
            </View>
          ))}
        </View>
      </View>

      <View className={styles.section}>
        <View className={styles.sectionHeader}>
          <Text className={styles.sectionTitle}>最近面试</Text>
          <Text className={styles.sectionLink} onClick={handleViewHistory}>
            查看全部
          </Text>
        </View>
        <View className={styles.cardList}>
          {interviews.map((interview) => (
            <View key={interview.id} className={styles.interviewRow} onClick={() => handleViewReport(interview.id)}>
              <View className={styles.interviewInfo}>
                <Text className={styles.interviewTitle}>{interview.positionTitle || '模拟面试'}</Text>
                <Text className={styles.interviewMeta}>{interview.startTime || interview.status}</Text>
              </View>
              <Text className={styles.interviewScore}>{interview.score ?? '-'}</Text>
            </View>
          ))}
        </View>
      </View>
    </ScrollView>
  );
};

export default HomePage;
