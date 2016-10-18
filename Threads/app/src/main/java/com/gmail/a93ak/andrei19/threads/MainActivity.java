package com.gmail.a93ak.andrei19.threads;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.gmail.a93ak.andrei19.threads.MyAsync.MyAsyncTask;

import java.util.concurrent.ExecutionException;

import static com.gmail.a93ak.andrei19.threads.MyAsync.MyAsyncTask.Status.RUNNING;

public class MainActivity extends Activity {

    private static final String TAG = "THREADS_TAG";
    private MyTask myTask;


    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "create MainActivity: " + this.hashCode());

        myTask = (MyTask) getLastNonConfigurationInstance();
        if (myTask == null) {
            myTask = new MyTask();
            Log.d(TAG, "create MyTask: " + myTask.hashCode());
            myTask.execute();
        } else if (myTask.getmStatus() == RUNNING) {
            myTask.setActivity(null);
            myTask.cancel(true);
            myTask = new MyTask();
            Log.d(TAG, "ReCreate MyTask: " + myTask.hashCode());
            myTask.execute();
        }
        myTask.setActivity(this);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return myTask;
    }

    public void getResult(View view) {
        try {
            Toast.makeText(this, String.valueOf(myTask.get()), Toast.LENGTH_LONG).show();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

    }


    class MyTask extends MyAsyncTask<String, Integer, Integer> {

        @Override
        protected Integer doInBackground(String... params) {
            try {
                for (int i = 1; i <= 10; i++) {
                    Thread.sleep(1000);
                    publishProgress(i);
                    Log.d(TAG, "i = " + i
                            + ", MyTask: " + this.hashCode()
                            + ", MainActivity: " + MainActivity.this.hashCode());
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            return 100500;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            ((TextView) this.getActivity().findViewById(R.id.tv)).setText(String.valueOf(values[0]));
        }
    }
}
