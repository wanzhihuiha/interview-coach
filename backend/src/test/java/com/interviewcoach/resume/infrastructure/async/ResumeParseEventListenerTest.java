package com.interviewcoach.resume.infrastructure.async;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.application.service.ResumeParseWorker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 简历解析事件入队测试。
 */
class ResumeParseEventListenerTest {

    @Test
    void shouldSubmitWorkerToExecutor() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeParseWorker worker = Mockito.mock(ResumeParseWorker.class);
        ResumeParseEventListener listener = new ResumeParseEventListener(executor, worker);
        ResumeParseRequestedEvent event = new ResumeParseRequestedEvent(1L, 2L, false);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        listener.onRequested(event);
        verify(executor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        verify(worker).parse(event);
    }

    @Test
    void shouldMarkTaskFailedWhenQueueRejectsSubmission() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeParseWorker worker = Mockito.mock(ResumeParseWorker.class);
        ResumeParseEventListener listener = new ResumeParseEventListener(executor, worker);
        ResumeParseRequestedEvent event = new ResumeParseRequestedEvent(1L, 2L, false);
        doThrow(new RejectedExecutionException("queue full")).when(executor).execute(any(Runnable.class));

        listener.onRequested(event);

        verify(worker).markSchedulingFailed(event);
    }
}
