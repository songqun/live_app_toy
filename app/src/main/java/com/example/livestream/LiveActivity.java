package com.example.livestream;

import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Process;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

public class LiveActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    // Open Camera
    private Camera myCamera;
    private SurfaceView myView;
    private SurfaceHolder myHolder;
    private int myCameraId = 0;
    private int width = 320;
    private int height = 240;
    private Button ctrlBtn;

    // Push Stream
    //private String ffmpeg_link = Environment.getExternalStorageDirectory() + "/test.flv";
    private String ffmpeg_link = "rtmp://59.78.30.20:9090/hls/live";
    private FFmpegFrameRecorder recorder;
    private Frame yuvImage;
    boolean isRecording = false;
    long startTime = 0;
    private int sampleRate  = 44100;
    private int frameRate = 30;

    private Thread audioThread;
    boolean runAudioThread = true;
    private AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);

        ctrlBtn = (Button) findViewById(R.id.controlButton);
        ctrlBtn.setText("start");
        ctrlBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!isRecording) {
                    startRecord();
                    ctrlBtn.setText("Stop");
                } else {
                    stopRecord();
                    ctrlBtn.setText("Start");
                }
            }
        });

        myView = (SurfaceView) findViewById(R.id.liveView);
        myHolder = myView.getHolder();
        myHolder.setFixedSize(width, height);
        myHolder.addCallback(this);
    }

    public void startRecord() {
        yuvImage = new Frame(width, height, Frame.DEPTH_UBYTE, 2);

        recorder = new FFmpegFrameRecorder(ffmpeg_link, width, height, 1);

        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("flv");
        recorder.setSampleRate(sampleRate);
        recorder.setFrameRate(frameRate);

        audioRecordRunnable = new AudioRecordRunnable();
        audioThread = new Thread(audioRecordRunnable);
        runAudioThread = true;
        try {
            recorder.start();
            startTime = System.currentTimeMillis();
            audioThread.start();
            isRecording = true;
        } catch (FFmpegFrameRecorder.Exception e){
            e.printStackTrace();
        }
    }

    public void stopRecord() {
        runAudioThread = false;
        try {
            audioThread.join();
        } catch (InterruptedException e){
            e.printStackTrace();
        }
        audioRecordRunnable = null;
        audioThread = null;

        if (recorder != null && isRecording) {
            try {
                recorder.stop();
                recorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            recorder = null;
            isRecording = false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Quit for back button
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isRecording) {
                stopRecord();
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onPreviewFrame(byte[] bytes, Camera camera) {
        if (yuvImage != null && isRecording) {
            long videoTimestamp = 1000 * (System.currentTimeMillis() - startTime);
            ((ByteBuffer)yuvImage.image[0].position(0)).put(bytes);
            try {
                recorder.setTimestamp(videoTimestamp);
                recorder.record(yuvImage);
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        initCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        destroyCamera();
    }

    private void initCamera() {
        try {
            myCamera = Camera.open(myCameraId);
            myCamera.setPreviewDisplay(myHolder);
            Camera.Parameters params = myCamera.getParameters();
            params.setPreviewSize(width, height);
            params.setPictureSize(width, height);
            myCamera.setDisplayOrientation(90);
            myCamera.setParameters(params);
            myCamera.setPreviewCallback(this);
            myCamera.startPreview();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void destroyCamera() {
        if (myCamera == null) {
            return;
        }

        myCamera.setPreviewCallback(null);
        myCamera.stopPreview();
        myCamera.release();
        myCamera = null;
    }

    private class AudioRecordRunnable implements Runnable {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            int bufferSize;
            short[] audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            audioData = new short[bufferSize];

            audioRecord.startRecording();

            while (runAudioThread) {
                bufferReadResult = audioRecord.read(audioData, 0, audioData.length);
                if (bufferReadResult > 0) {
                    if (isRecording) {
                        try {
                            recorder.recordSamples(ShortBuffer.wrap(audioData, 0,
                                    bufferReadResult));
                        } catch (FFmpegFrameRecorder.Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        }
    }

}
