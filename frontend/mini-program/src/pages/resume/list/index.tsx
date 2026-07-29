import React, { useEffect, useState } from 'react';
import { View, Text, Button, ScrollView } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { getResumeList, uploadResume } from '@/api/resume';
import type { Resume } from '@/api/resume';
import { formatDate } from '@/utils/format';
import Empty from '@/components/Empty';
import styles from './index.module.scss';
import classnames from 'classnames';

const ResumeListPage: React.FC = () => {
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchResumes = async () => {
    setLoading(true);
    try {
      const res = await getResumeList();
      setResumes(res);
    } catch (err) {
      console.error('[ResumeList] fetch failed', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchResumes();
  }, []);

  const handleUpload = () => {
    if (process.env.TARO_ENV === 'h5') {
      // H5 预览环境下使用原生文件输入
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.pdf,.doc,.docx,.txt';
      input.onchange = async () => {
        const file = input.files?.[0];
        if (!file) return;
        Taro.showLoading({ title: '上传中...' });
        try {
          const ext = file.name.split('.').pop()?.toUpperCase() || 'TXT';
          const objectUrl = URL.createObjectURL(file);
          await uploadResume(objectUrl, file.name, ext);
          Taro.showToast({ title: '上传成功', icon: 'success' });
          fetchResumes();
        } catch (err) {
          Taro.showToast({ title: (err as Error).message || '上传失败', icon: 'none' });
        } finally {
          Taro.hideLoading();
        }
      };
      input.click();
      return;
    }

    Taro.chooseMessageFile({
      count: 1,
      type: 'file',
      extension: ['pdf', 'doc', 'docx', 'txt'],
      success: async (res) => {
        const file = res.tempFiles[0];
        if (!file) return;
        Taro.showLoading({ title: '上传中...' });
        try {
          const ext = file.name.split('.').pop()?.toUpperCase() || 'TXT';
          await uploadResume(file.path, file.name, ext);
          Taro.showToast({ title: '上传成功', icon: 'success' });
          fetchResumes();
        } catch (err) {
          Taro.showToast({ title: (err as Error).message || '上传失败', icon: 'none' });
        } finally {
          Taro.hideLoading();
        }
      },
      fail: (err) => {
        console.error('[ResumeList] choose file failed', err);
      },
    });
  };

  const getStatusClass = (status: string) => {
    return status === 'CONFIRMED' ? styles.statusConfirmed : styles.statusPending;
  };

  const getStatusText = (status: string) => {
    return status === 'CONFIRMED' ? '已确认' : '待确认';
  };

  return (
    <ScrollView scrollY className={styles.container} refresherTriggered={loading} onRefresherRefresh={fetchResumes}>
      {resumes.length === 0 ? (
        <Empty title="暂无简历" description="点击下方按钮上传简历" />
      ) : (
        resumes.map((resume) => (
          <View key={resume.resumeId} className={styles.card}>
            <View className={styles.info}>
              <Text className={styles.title}>{resume.fileName}</Text>
              <Text className={styles.meta}>{resume.jobCategory || '-'} · {getStatusText(resume.status)}</Text>
              <Text className={styles.date}>{formatDate(resume.createdAt)}</Text>
            </View>
            <Text className={classnames(styles.status, getStatusClass(resume.status))}>
              {getStatusText(resume.status)}
            </Text>
          </View>
        ))
      )}
      <Button className={styles.uploadBtn} onClick={handleUpload}>
        上传简历
      </Button>
    </ScrollView>
  );
};

export default ResumeListPage;
