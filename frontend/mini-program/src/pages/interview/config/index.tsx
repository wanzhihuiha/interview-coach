import React, { useEffect, useState } from 'react';
import { View, Text, Button } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { getResumeList } from '@/api/resume';
import { getAccessiblePositionList } from '@/api/position';
import { startInterview } from '@/api/interview';
import type { Resume } from '@/api/resume';
import type { Position } from '@/api/position';
import styles from './index.module.scss';
import classnames from 'classnames';

const PHASE_OPTIONS = [
  { key: 'intro', name: '自我介绍', required: true },
  { key: 'professional', name: '专业面试' },
  { key: 'resume', name: '简历探讨' },
  { key: 'behavior', name: '行为面试' },
];

const ConfigPage: React.FC = () => {
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [positions, setPositions] = useState<Position[]>([]);
  const [selectedResume, setSelectedResume] = useState<number | null>(null);
  const [selectedPosition, setSelectedPosition] = useState<number | null>(null);
  const [selectedPhases, setSelectedPhases] = useState<string[]>(['intro', 'professional', 'resume', 'behavior']);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    Promise.all([getResumeList(), getAccessiblePositionList({ size: 50 })]).then(([rList, pList]) => {
      setResumes(rList);
      setPositions(pList);
      if (rList.length > 0) setSelectedResume(rList[0].resumeId);
      if (pList.length > 0) setSelectedPosition(pList[0].positionId);
    }).catch((err) => {
      console.error('[Config] load failed', err);
      Taro.showToast({ title: '加载数据失败', icon: 'none' });
    });
  }, []);

  const togglePhase = (key: string) => {
    setSelectedPhases((prev) => {
      if (prev.includes(key)) {
        if (prev.length === 1) {
          Taro.showToast({ title: '至少选择一个面试环节', icon: 'none' });
          return prev;
        }
        return prev.filter((k) => k !== key);
      }
      return [...prev, key];
    });
  };

  const handleStart = async () => {
    if (!selectedResume || !selectedPosition) {
      Taro.showToast({ title: '请选择简历和岗位', icon: 'none' });
      return;
    }
    setLoading(true);
    try {
      const session = await startInterview({
        resumeId: selectedResume,
        positionId: selectedPosition,
        phases: selectedPhases,
      });
      Taro.navigateTo({ url: `/pages/interview/chat/index?id=${session.id}` });
    } catch (err) {
      Taro.showToast({ title: (err as Error).message || '启动失败', icon: 'none' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <View className={styles.container}>
      <View className={styles.card}>
        <Text className={styles.cardTitle}>选择简历</Text>
        {resumes.length === 0 ? (
          <View className={styles.emptyTip} onClick={() => Taro.navigateTo({ url: '/pages/resume/list/index' })}>
            暂无简历，点击前往上传 ›
          </View>
        ) : (
          <View className={styles.list}>
            {resumes.map((resume) => (
              <View
                key={resume.resumeId}
                className={classnames(styles.item, { [styles.selected]: selectedResume === resume.resumeId })}
                onClick={() => setSelectedResume(resume.resumeId)}
              >
                <View className={styles.itemInfo}>
                  <Text className={styles.itemTitle}>{resume.fileName}</Text>
                  <Text className={styles.itemMeta}>{resume.jobCategory || '-'}</Text>
                </View>
                {selectedResume === resume.resumeId && <View className={styles.check}>✓</View>}
              </View>
            ))}
          </View>
        )}
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>选择岗位</Text>
        {positions.length === 0 ? (
          <View className={styles.emptyTip} onClick={() => Taro.navigateTo({ url: '/pages/position/list/index' })}>
            暂无岗位，点击前往添加 ›
          </View>
        ) : (
          <View className={styles.list}>
            {positions.map((position) => (
              <View
                key={position.positionId}
                className={classnames(styles.item, { [styles.selected]: selectedPosition === position.positionId })}
                onClick={() => setSelectedPosition(position.positionId)}
              >
                <View className={styles.itemInfo}>
                  <Text className={styles.itemTitle}>{position.positionName}</Text>
                  <Text className={styles.itemMeta}>{position.companyName || '未知公司'} · {position.jobCategory}</Text>
                </View>
                {selectedPosition === position.positionId && <View className={styles.check}>✓</View>}
              </View>
            ))}
          </View>
        )}
      </View>

      <View className={styles.card}>
        <Text className={styles.cardTitle}>选择面试环节</Text>
        <View className={styles.phaseGrid}>
          {PHASE_OPTIONS.map((phase) => (
            <View
              key={phase.key}
              className={classnames(styles.phaseTag, { [styles.selected]: selectedPhases.includes(phase.key) })}
              onClick={() => togglePhase(phase.key)}
            >
              {phase.name}
            </View>
          ))}
        </View>
      </View>

      <Button className={styles.startBtn} onClick={handleStart} disabled={loading}>
        {loading ? '启动中...' : '开始面试'}
      </Button>
    </View>
  );
};

export default ConfigPage;
