package com.gmail.a93ak.andrei19.threads.MyAsync;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.Activity;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.support.annotation.NonNull;

import static com.gmail.a93ak.andrei19.threads.MyAsync.MyAsyncTask.Status.RUNNING;

public abstract class MyAsyncTask<Params, Progress, Result> {

    private Activity activity;                              // ссылка на наше activity

    private static final String LOG_TAG = "AsyncTask";

    private static final int CORE_POOL_SIZE = 10;           // пул потоков
    private static final int MAXIMUM_POOL_SIZE = 128;       // максимальный пул потоков
    private static final int KEEP_ALIVE = 1;                // время на сокращение очереди, после освобождения старым потоком

    // фабрика потоков создающая потоки с именами "AsynckTask #N" для THREAD_POOL_EXECUTOR
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {

        private final AtomicInteger mCount = new AtomicInteger(1);            // потокобезопасный Integer

        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "AsyncTask #" + mCount.getAndIncrement());
        }
    };

    // попытка извлечь из пустой очереди заблокирует вызывающий поток до тех пор пока не появится элемент
    // попытка записать в полную очередь также заблокиру вызывающтй поток
    // очередь потоков для THREAD_POOL_EXECUTOR
    private static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedBlockingQueue<>(10);

    // executor, который будет выполнять наш код
    private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory);


    private static final int MESSAGE_POST_RESULT = 1;
    private static final int MESSAGE_POST_PROGRESS = 2;

    // наш handler, который постит сообщения об окончании задачи, или промежуточном результате
    private static final InternalHandler sHandler = new InternalHandler();

    // наш executor, только volatile и он static
    private static volatile Executor sDefaultExecutor = THREAD_POOL_EXECUTOR;

    // абстрактный callable c параметрами внутри, возвращать должен результат работы
    private final WorkerRunnable<Params, Result> mWorker;


    // отменяемая задача, на вход примет mWorker. резульат операции получется методом get
    private final FutureTask<Result> mFuture;

    // сначала статус "не начато"
    private volatile Status mStatus = Status.PENDING;

    public Status getmStatus() {
        return mStatus;
    }

    // флаг вызова задачи
    private final AtomicBoolean mTaskInvoked = new AtomicBoolean();

    // статус задачи, каждое состяние может быть установлено однажды
    public enum Status {
        PENDING,    //ещё не начато
        RUNNING,    //выполняется
        FINISHED,   //завершено
    }


    public MyAsyncTask() {

        // теперь наш worker будет выполнять то что в doInBackGround и давать результат
        mWorker = new WorkerRunnable<Params, Result>() {
            public Result call() throws Exception {
                mTaskInvoked.set(true);

                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                return postResult(doInBackground(mParams));
            }
        };

        // а через него получим результат
        mFuture = new FutureTask<Result>(mWorker) {
            @Override
            protected void done() {
                try {

                    final Result result = get();
                    // возможно это мы получаем результат если что-то пошло не так?
                    postResultIfNotInvoked(result);
                } catch (InterruptedException e) {
                    android.util.Log.w(LOG_TAG, e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("An error occured while executing",
                            e.getCause());
                } catch (CancellationException e) {
                    postResultIfNotInvoked(null);
                } catch (Throwable t) {
                    throw new RuntimeException("An error occured while executing ", t);
                }
            }
        };
    }

    // постим резульат если не вызван?
    private void postResultIfNotInvoked(Result result) {
        final boolean wasTaskInvoked = mTaskInvoked.get();
        if (!wasTaskInvoked) {
            postResult(result);
        }
    }

    // метод, дающий нам результат asyncTask
    private Result postResult(Result result) {
        Message message = sHandler.obtainMessage(MESSAGE_POST_RESULT,
                new AsyncTaskResult<Result>(this, result));
        message.sendToTarget();
        return result;
    }

    protected abstract Result doInBackground(Params... params);

    protected void onPreExecute() {
    }

    protected void onPostExecute(Result result) {
    }

    protected void onProgressUpdate(Progress... values) {}

    protected void onCancelled(Result result) {
        onCancelled();
    }

    protected void onCancelled() {}

    public final boolean isCancelled() {
        return mFuture.isCancelled();
    }

    public final boolean cancel(boolean mayInterruptIfRunning) {
        return mFuture.cancel(mayInterruptIfRunning);
    }

    public final Result get() throws InterruptedException, ExecutionException {
        return mFuture.get();
    }

    // ожидать результа иначе таймаут
    public final Result get(long timeout, TimeUnit unit) throws InterruptedException,
            ExecutionException, TimeoutException {
        return mFuture.get(timeout, unit);
    }


    public Activity getActivity() {
        return activity;
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    // основной метод, через него выполняем задачу
    public final MyAsyncTask<Params, Progress, Result> execute(Params... params) {
        if (mStatus != Status.PENDING) {
            switch (mStatus) {
                case RUNNING:
                    throw new IllegalStateException("Cannot execute task:"
                            + " the task is already running.");
                case FINISHED:
                    throw new IllegalStateException("Cannot execute task:"
                            + " the task has already been executed "
                            + "(a task can be executed only once)");
            }
        }


        return this;
    }

    // этот метод может быть вызван из doInBackGround, каждый раз когда этот метод будет вызван
    // выполнится onPrgressUpdate с помощью Handlera (он отправит соответствующий msg)
    protected final void publishProgress(Progress... values) {
        if (!isCancelled()) {
            sHandler.obtainMessage(MESSAGE_POST_PROGRESS,
                    new AsyncTaskResult<Progress>(this, values)).sendToTarget();
        }
    }

    //  если задача была прервана, то соответсвующий метод, иначе onPostExecute
    private void finish(Result result) {
        if (isCancelled()) {
            onCancelled(result);
        } else {
            onPostExecute(result);
        }
        mStatus = Status.FINISHED;
    }

    // handler для обработки и отправки сообщений, он вызывает finish при завершении, и он же вызывает
    // onProgressUpdate, в роли объекта передачи либо результат выполнения, либо промежуточные результаты
    private static class InternalHandler extends Handler {
        @SuppressWarnings({"unchecked", "RawUseOfParameterizedType"})
        @Override
        public void handleMessage(Message msg) {
            AsyncTaskResult result = (AsyncTaskResult) msg.obj;
            switch (msg.what) {
                case MESSAGE_POST_RESULT:
                    // если задача завершена
                    // то вызываем метод финиш с результатом
                    result.mTask.finish(result.mData[0]);
                    break;
                case MESSAGE_POST_PROGRESS:
                    // если задача выдаёт прогресс
                    // то вызываем метод OnProgressUpdate
                    Integer a = (Integer) result.mData[0];
                    result.mTask.onProgressUpdate(result.mData);
                    break;
            }
        }
    }

    // наш Callable в котором надо реализовать call и который имеет ссылку на параметры, наш callable
    // будет работать с параметрами и давать результат и в нём выполняется mFutureTask
    private static abstract class WorkerRunnable<Params, Result> implements Callable<Result> {
        Params[] mParams;
    }

    // класс результата выполнения задачи или промежуточного результата
    private static class AsyncTaskResult<Data> {
        final MyAsyncTask mTask;
        final Data[] mData;

        AsyncTaskResult(MyAsyncTask task, Data... data) {
            mTask = task;
            mData = data;
        }
    }
}