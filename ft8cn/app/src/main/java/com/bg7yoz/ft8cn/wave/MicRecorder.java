package com.bg7yoz.ft8cn.wave;
/**
 * 使用Mic录音的操作。
 * 针对 vivo 等手机无法采集音频的问题做了加固（参考 scrcpy issue #3805）：
 * 1. AudioRecord 采用惰性初始化，创建失败不再导致应用崩溃；
 * 2. 依次尝试多个音频源（DEFAULT/MIC/VOICE_RECOGNITION/UNPROCESSED）；
 * 3. 优先浮点编码，不支持时自动回退到 16 位编码并在读取时转换；
 * 4. 常规构造失败时，尝试 AudioRecord.Builder + 应用Context，再失败则用反射
 *    绕过 vivo 修改过的 AudioRecord 构造逻辑（AudioRecordWorkaround）。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ui.ToastMessage;

public class MicRecorder {
    private static final String TAG = "MicRecorder";
    private static final int sampleRateInHz = 12000;//采样率
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO; //单声道

    private AudioRecord audioRecord = null;//AudioRecord对象
    private boolean isRunning = false;//是否处于录音的状态。
    private boolean isFloatEncoding = true;//底层是否使用浮点编码，false时使用16位编码并做转换
    private int bufferSizeBytes = 0;//底层缓冲区大小（字节）
    private OnDataListener onDataListener;

    public interface OnDataListener {
        void onDataReceived(float[] data, int len);
    }

    public MicRecorder() {
    }

    /**
     * 初始化 AudioRecord。采用惰性创建，并按多种策略依次尝试，失败不抛异常。
     *
     * @return 是否初始化成功
     */
    @SuppressLint("MissingPermission")
    private boolean init() {
        if (audioRecord != null) return true;

        Context context = GeneralVariables.getMainContext();

        //选择编码：优先浮点（下游解码直接使用float），不支持时回退到16位
        int encoding = AudioFormat.ENCODING_PCM_FLOAT;
        int minBuffer = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, encoding);
        isFloatEncoding = minBuffer > 0;
        if (!isFloatEncoding) {
            encoding = AudioFormat.ENCODING_PCM_16BIT;
            minBuffer = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, encoding);
        }
        //getMinBufferSize返回负数（如ERROR_BAD_VALUE）时，给一个保守的兜底大小
        bufferSizeBytes = minBuffer > 0 ? minBuffer : sampleRateInHz * 4;

        for (int source : getAudioSources()) {
            //方式一：经典构造函数
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(source, sampleRateInHz, channelConfig, encoding, bufferSizeBytes);
            } catch (Throwable e) {
                Log.d(TAG, String.format("经典构造AudioRecord失败 source=%d : %s", source, e.getMessage()));
            }
            if (isInitialized(rec)) {
                audioRecord = rec;
                Log.d(TAG, String.format("使用经典构造创建AudioRecord成功 source=%d", source));
                return true;
            }
            rec = safeRelease(rec);

            //方式二：AudioRecord.Builder + 应用Context（Android 12+，Context有效可规避vivo的NPE）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context != null) {
                try {
                    AudioFormat audioFormat = new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRateInHz)
                            .setChannelMask(channelConfig)
                            .build();
                    AudioRecord.Builder builder = new AudioRecord.Builder();
                    builder.setAudioSource(source);
                    builder.setAudioFormat(audioFormat);
                    builder.setBufferSizeInBytes(bufferSizeBytes);
                    builder.setContext(context);
                    rec = builder.build();
                } catch (Throwable e) {
                    Log.d(TAG, String.format("Builder创建AudioRecord失败 source=%d : %s", source, e.getMessage()));
                }
                if (isInitialized(rec)) {
                    audioRecord = rec;
                    Log.d(TAG, String.format("使用Builder创建AudioRecord成功 source=%d", source));
                    return true;
                }
                rec = safeRelease(rec);
            }

            //方式三：反射绕过被厂商（vivo）修改过的AudioRecord构造函数
            try {
                rec = AudioRecordWorkaround.create(context, source, sampleRateInHz
                        , channelConfig, 1, encoding, bufferSizeBytes);
            } catch (Throwable e) {
                Log.d(TAG, String.format("反射创建AudioRecord失败 source=%d : %s", source, e.getMessage()));
            }
            if (isInitialized(rec)) {
                audioRecord = rec;
                Log.d(TAG, String.format("使用反射创建AudioRecord成功 source=%d", source));
                return true;
            }
            rec = safeRelease(rec);
        }

        Log.e(TAG, "所有方式均无法创建AudioRecord");
        return false;
    }

    /**
     * 获取依次尝试的音频源列表。
     */
    private int[] getAudioSources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new int[]{MediaRecorder.AudioSource.DEFAULT
                    , MediaRecorder.AudioSource.MIC
                    , MediaRecorder.AudioSource.VOICE_RECOGNITION
                    , MediaRecorder.AudioSource.UNPROCESSED};
        } else {
            return new int[]{MediaRecorder.AudioSource.DEFAULT
                    , MediaRecorder.AudioSource.MIC
                    , MediaRecorder.AudioSource.VOICE_RECOGNITION};
        }
    }

    private boolean isInitialized(AudioRecord rec) {
        return rec != null && rec.getState() == AudioRecord.STATE_INITIALIZED;
    }

    private AudioRecord safeRelease(AudioRecord rec) {
        if (rec != null) {
            try {
                rec.release();
            } catch (Exception e) {
                Log.d(TAG, "release失败：" + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 每次读取的数据帧数（float个数或short个数）。
     */
    private int getBufferFrames() {
        return bufferSizeBytes / (isFloatEncoding ? 4 : 2);
    }

    public void start() {
        if (isRunning) return;

        //惰性初始化，创建失败时给出提示而不是崩溃
        if (!isInitialized(audioRecord)) {
            if (!init()) {
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                        R.string.recorder_cannot_record), "AudioRecord init failed"));
                return;
            }
        }

        try {
            audioRecord.startRecording();//开始录音
        } catch (Exception e) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record), e.getMessage()));
            Log.d(TAG, "startRecord: " + e.getMessage());
            return;
        }

        //确认进入录音状态，避免权限未授予等情况下静默失败
        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record)
                    , String.format("state=%d", audioRecord.getRecordingState())));
            Log.d(TAG, String.format("录音启动失败，状态码：%d", audioRecord.getRecordingState()));
            return;
        }

        isRunning = true;

        final float[] floatBuffer = isFloatEncoding ? new float[getBufferFrames()] : null;
        final short[] shortBuffer = isFloatEncoding ? null : new short[getBufferFrames()];
        final float[] convertBuffer = isFloatEncoding ? null : new float[getBufferFrames()];

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    //判断是否处于录音状态，state!=3，说明没有处于录音的状态
                    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        isRunning = false;
                        Log.d(TAG, String.format("录音失败，状态码：%d", audioRecord.getRecordingState()));
                        break;
                    }

                    int bufferReadResult;
                    if (isFloatEncoding) {
                        //读录音的数据
                        bufferReadResult = audioRecord.read(floatBuffer, 0, floatBuffer.length
                                , AudioRecord.READ_BLOCKING);
                    } else {
                        //16位编码，读取后转换为float
                        bufferReadResult = audioRecord.read(shortBuffer, 0, shortBuffer.length
                                , AudioRecord.READ_BLOCKING);
                        for (int i = 0; i < bufferReadResult && i < convertBuffer.length; i++) {
                            convertBuffer[i] = shortBuffer[i] / 32768.0f;
                        }
                    }

                    if (bufferReadResult < 0) {//读取错误
                        isRunning = false;
                        Log.d(TAG, String.format("录音读取错误，错误码：%d", bufferReadResult));
                        break;
                    }
                    if (bufferReadResult == 0) continue;

                    if (onDataListener != null) {
                        if (isFloatEncoding) {
                            onDataListener.onDataReceived(floatBuffer, bufferReadResult);
                        } else {
                            onDataListener.onDataReceived(convertBuffer, bufferReadResult);
                        }
                    }
                }
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();//停止录音
                    }
                } catch (Exception e) {
                    ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                            R.string.recorder_stop_record_error), e.getMessage()));
                    Log.d(TAG, "stopRecord: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 停止录音。
     */
    public void stopRecord() {
        isRunning = false;
    }

    public OnDataListener getOnDataListener() {
        return onDataListener;
    }

    public void setOnDataListener(OnDataListener onDataListener) {
        this.onDataListener = onDataListener;
    }
}
