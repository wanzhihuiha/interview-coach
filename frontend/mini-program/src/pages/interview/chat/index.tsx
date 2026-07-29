import React, { useEffect, useState, useRef } from 'react';
import { View, Text, Textarea, Button } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import {
  getInterview,
  getInterviewMessages,
  submitAnswer,
  endInterview,
  phaseKeyToName,
  type AnswerEvent,
  type InterviewSession,
  type InterviewMessage,
} from '@/api/interview';
import styles from './index.module.scss';
import classnames from 'classnames';

const ChatPage: React.FC = () => {
  const router = useRouter();
  const interviewId = Number(router.params.id);

  const [session, setSession] = useState<InterviewSession | null>(null);
  const [messages, setMessages] = useState<InterviewMessage[]>([]);
  const [answer, setAnswer] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [thinking, setThinking] = useState(false);
  const [isEnded, setIsEnded] = useState(false);
  const [phaseChange, setPhaseChange] = useState<{ previous: string; current: string } | null>(null);
  const abortRef = useRef<{ abort: () => void } | null>(null);

  useEffect(() => {
    if (!interviewId) {
      Taro.showToast({ title: '面试ID无效', icon: 'none' });
      return;
    }
    loadInterview();
    return () => {
      abortRef.current?.abort();
    };
  }, [interviewId]);

  const loadInterview = async () => {
    try {
      const [detail, msgList] = await Promise.all([
        getInterview(interviewId),
        getInterviewMessages(interviewId),
      ]);
      if (!detail) {
        Taro.showToast({ title: '面试不存在', icon: 'none' });
        return;
      }
      setSession(detail);
      setMessages(msgList);
      setIsEnded(detail.status === 'completed' || detail.status === 'interrupted');
    } catch (err) {
      Taro.showToast({ title: (err as Error).message || '加载失败', icon: 'none' });
    }
  };

  const updateSessionPhase = (currentPhase: string) => {
    setSession((prev) => {
      if (!prev) return prev;
      const keyMap: Record<string, string> = {
        SELF_INTRO: 'intro',
        PROFESSIONAL: 'professional',
        RESUME_DISCUSSION: 'resume',
        BEHAVIORAL: 'behavior',
        ENDING: 'ending',
      };
      const currentKey = keyMap[currentPhase] || currentPhase.toLowerCase();
      let reachedCurrent = false;
      const phases = prev.phases.map((p) => {
        const isCurrent = p.key === currentKey;
        if (isCurrent) reachedCurrent = true;
        return { ...p, current: isCurrent, completed: !isCurrent && !reachedCurrent };
      });
      return { ...prev, phases, currentPhase };
    });
  };

  const handleSubmit = async () => {
    if (!answer.trim() || !session) return;
    setSubmitting(true);
    setThinking(true);

    const userAnswer = answer.trim();
    setAnswer('');

    setMessages((prev) => [
      ...prev,
      {
        messageId: Date.now(),
        phase: session.currentPhase || '',
        role: 'candidate',
        content: userAnswer,
        seqNo: prev.length + 1,
        createdAt: new Date().toISOString(),
      },
    ]);

    abortRef.current = submitAnswer(interviewId, userAnswer, (event: AnswerEvent) => {
      switch (event.type) {
        case 'thinking':
          setThinking(true);
          break;
        case 'phaseChange':
          if (event.previousPhase && event.currentPhase) {
            setPhaseChange({
              previous: phaseKeyToName(event.previousPhase),
              current: phaseKeyToName(event.currentPhase),
            });
            updateSessionPhase(event.currentPhase);
          }
          break;
        case 'question':
          if (event.content) {
            setMessages((prev) => [
              ...prev,
              {
                messageId: Date.now(),
                phase: event.phase || session.currentPhase || '',
                role: 'interviewer',
                content: event.content!,
                topic: event.topicName,
                depth: event.depth,
                seqNo: prev.length + 1,
                createdAt: new Date().toISOString(),
              },
            ]);
            updateSessionPhase(event.phase || session.currentPhase || '');
          }
          setThinking(false);
          break;
        case 'interviewEnd':
          setIsEnded(true);
          setSession((prev) => (prev ? { ...prev, status: 'completed' } : prev));
          Taro.showToast({ title: '面试已结束', icon: 'success' });
          break;
        case 'done':
          setSubmitting(false);
          setThinking(false);
          break;
        case 'error':
          setSubmitting(false);
          setThinking(false);
          Taro.showToast({ title: event.message || '回答处理失败', icon: 'none' });
          break;
      }
    });
  };

  const handleInterrupt = async () => {
    const action = isEnded ? '离开' : '中断';
    const { confirm } = await Taro.showModal({
      title: '提示',
      content: `确定要${action}当前面试吗？`,
    });
    if (!confirm) return;
    if (!isEnded) {
      try {
        await endInterview(interviewId);
      } catch (err) {
        console.error('[Chat] end interview failed', err);
      }
    }
    Taro.switchTab({ url: '/pages/history/index' });
  };

  const viewReport = () => {
    Taro.navigateTo({ url: `/pages/interview/report/index?id=${interviewId}` });
  };

  if (!session) {
    return (
      <View className={styles.container}>
        <View className={styles.empty}>加载中...</View>
      </View>
    );
  }

  return (
    <View className={styles.container}>
      <View className={styles.header}>
        <View>
          <Text className={styles.headerTitle}>{isEnded ? '面试详情' : '面试进行中'}</Text>
          <Text className={styles.headerMeta}>
            {session.positionTitle || '模拟面试'} · 第 {messages.filter((m) => m.role === 'interviewer').length} 题
          </Text>
        </View>
        <View className={styles.headerActions}>
          {isEnded && (
            <Text className={classnames(styles.actionText, styles.successText)} onClick={viewReport}>
              报告
            </Text>
          )}
          <Text className={styles.actionText} onClick={handleInterrupt}>
            {isEnded ? '离开' : '中断'}
          </Text>
        </View>
      </View>

      <View className={styles.chatArea}>
        {messages.map((msg) => (
          <View
            key={msg.messageId}
            className={classnames(styles.message, msg.role === 'interviewer' ? styles.messageInterviewer : styles.messageCandidate)}
          >
            {msg.role === 'interviewer' && (
              <View className={styles.avatar}>👔</View>
            )}
            <View className={classnames(styles.bubble, msg.role === 'interviewer' ? styles.bubbleInterviewer : styles.bubbleCandidate)}>
              {msg.role === 'interviewer' && thinking && msg === messages[messages.length - 1] ? (
                <Text className={styles.thinking}>面试官正在思考...</Text>
              ) : (
                <Text>{msg.content}</Text>
              )}
            </View>
            {msg.role === 'candidate' && (
              <View className={classnames(styles.avatar, styles.avatarCandidate)}>🧑</View>
            )}
          </View>
        ))}
      </View>

      <View className={styles.inputArea}>
        {isEnded ? (
          <Text className={styles.hint}>该面试已结束，无法继续回答</Text>
        ) : (
          <>
            <Textarea
              className={styles.textarea}
              placeholder="请输入你的回答..."
              value={answer}
              onInput={(e) => setAnswer(e.detail.value)}
              maxlength={2000}
              disabled={submitting}
            />
            <View className={styles.toolbar}>
              <Text className={styles.hint}>回答完成后将自动进入下一题</Text>
              <Button className={styles.submitBtn} onClick={handleSubmit} disabled={!answer.trim() || submitting || thinking}>
                {submitting ? '提交中...' : '提交'}
              </Button>
            </View>
          </>
        )}
      </View>

      {phaseChange && (
        <View className={styles.phaseChangeMask} onClick={() => setPhaseChange(null)}>
          <View className={styles.phaseChangeCard}>
            <View className={styles.phaseIcon}>✅</View>
            <Text className={styles.phaseTitle}>{phaseChange.previous} 环节完成</Text>
            <Text className={styles.phaseDesc}>即将进入：{phaseChange.current}</Text>
            <Button className={styles.phaseBtn} onClick={() => setPhaseChange(null)}>
              继续
            </Button>
          </View>
        </View>
      )}
    </View>
  );
};

export default ChatPage;
