package com.bg7yoz.ft8cn.wave;
/**
 * 使用Mic录音的操作。
 * 针对 vivo/iQOO 等手机无法采集音频的问题做了全面加固：
 * 1. 编码优先使用16位PCM（部分机型浮点采集会返回全0静音，如 iQOO Neo9 安卓14，
 *    参考官方 issue #160 / scrcpy issue #3805），读取后转换为float供解码使用；
 * 2. AudioRecord 采用惰性初始化，创建失败不再导致应用崩溃；
 * 3. 依次尝试多个音频源（DEFAULT/MIC/VOICE_RECOGNITION/UNPROCESSED）；
 * 4. 加入实时静音检测：若启动预热后持续采集到全0数据，自动切换到下一个
 *    音源/编码组合重新录音（某些机型特定音源会初始化成功但输出静音）；
 * 5. 常规构造失败时，尝试 AudioRecord.Builder + 应用Context，再失败则用反射
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

    //依次尝试的音频源
    private static final int[] SOURCES = {MediaRecorder.AudioSource.DEFAULT
            , MediaRecorder.AudioSource.MIC
            , MediaRecorder.AudioSource.VOICE_RECOGNITION
            , MediaRecorder.AudioSource.UNPROCESSED};
    //依次尝试的编码，16位优先（兼容性最好，避免部分机型浮点采集返回全0）
    private static final int[] ENCODINGS = {AudioFormat.ENCODING_PCM_16BIT
            , AudioFormat.ENCODING_PCM_FLOAT};

    //启动预热期（毫秒），预热期内不判定静音
    private static final long WARMUP_MS = 1000;
    //持续静音超过该时长（毫秒）则切换下一个音源/编码组合
    private static final long SILENT_MS = 2000;

    private AudioRecord audioRecord = null;//AudioRecord对象
    private boolean isRunning = false;//是否处于录音的状态。
    private boolean isFloatEncoding = false;//底层是否使用浮点编码，false时使用16位编码并做转换
    private int bufferSizeBytes = 0;//底层缓冲区大小（字节）
    private int comboIndex = 0;//当前使用的音源/编码组合序号
    private OnDataListener onDataListener;

    public interface OnDataListener {
        void onDataReceived(float[] data, int len);
    }

    public MicRecorder() {
    }

    private int currentSource() {
        return SOURCES[comboIndex % SOURCES.length];
    }

    private int currentEncoding() {
        return ENCODINGS[comboIndex / SOURCES.length];
    }

    /**
     * 获取当前使用的音频源序号，用于日志输出。
     */
    private String currentSourceName() {
        switch (currentSource()) {
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.UNPROCESSED:
                return "UNPROCESSED";
            default:
                return "DEFAULT";
        }
    }

    /**
     * 初始化 AudioRecord。按当前组合序号创建，多种方式依次尝试，失败不抛异常。
     *
     * @return 是否初始化成功
     */
    @SuppressLint("MissingPermission")
    private boolean init() {
        if (audioRecord != null) return true;

        Context context = GeneralVariables.getMainContext();

        //从当前组合开始依次尝试，直到创建成功
        while (comboIndex < SOURCES.length * ENCODINGS.length) {
            int source = currentSource();
            int encoding = currentEncoding();
            isFloatEncoding = (encoding == AudioFormat.ENCODING_PCM_FLOAT);

            int minBuffer = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, encoding);
            if (minBuffer <= 0) {
                //该编码不被支持，跳过当前编码对应的所有音源
                Log.d(TAG, String.format("getMinBufferSize失败（编码%d），跳过", encoding));
                if (isFloatEncoding) {
                    comboIndex = SOURCES.length * ENCODINGS.length;
                } else {
                    comboIndex = SOURCES.length;
                }
                continue;
            }
            bufferSizeBytes = minBuffer;

            AudioRecord rec = createByAllWays(context, source, encoding);
            if (rec != null) {
                audioRecord = rec;
                Log.d(TAG, String.format("AudioRecord创建成功：source=%s encoding=%s", currentSourceName()
                        , isFloatEncoding ? "FLOAT" : "16BIT"));
                return true;
            }
            comboIndex++;//创建失败，尝试下一个组合
        }

        Log.e(TAG, "所有方式均无法创建AudioRecord");
        return false;
    }

    /**
     * 使用多种方式创建 AudioRecord：经典构造 → Builder+Context → 反射绕过厂商构造逻辑。
     */
    private AudioRecord createByAllWays(Context context, int source, int encoding) {
        //方式一：经典构造函数
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, sampleRateInHz, channelConfig, encoding, bufferSizeBytes);
        } catch (Throwable e) {
            Log.d(TAG, String.format("经典构造AudioRecord失败 source=%d : %s", source, e.getMessage()));
        }
        if (isInitialized(rec)) return rec;
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
            if (isInitialized(rec)) return rec;
            rec = safeRelease(rec);
        }

        //方式三：反射绕过被厂商（vivo）修改过的AudioRecord构造函数
        try {
            rec = AudioRecordWorkaround.create(context, source, sampleRateInHz
                    , channelConfig, 1, encoding, bufferSizeBytes);
        } catch (Throwable e) {
            Log.d(TAG, String.format("反射创建AudioRecord失败 source=%d : %s", source, e.getMessage()));
        }
        if (isInitialized(rec)) return rec;
        return safeRelease(rec);
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

    /**
     * 启动录音。
     */
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

        if (!startRecording()) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record), "startRecording failed"));
            return;
        }

        isRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                runRecordLoop();
            }
        }).start();
    }

    /**
     * 尝试开始录音，并确认进入录音状态。
     *
     * @return 是否成功进入录音状态
     */
    private boolean startRecording() {
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.d(TAG, "startRecording: " + e.getMessage());
            return false;
        }
        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            Log.d(TAG, String.format("录音启动失败，状态码：%d", audioRecord.getRecordingState()));
            return false;
        }
        return true;
    }

    /**
     * 录音主循环。读取音频数据并派发，同时进行静音检测，若持续静音则自动切换到
     * 下一个音源/编码组合重新录音。
     */
    private void runRecordLoop() {
        long loopStart = System.currentTimeMillis();
        long lastNonZeroTime = loopStart;
        int lastCombo = -1;
        float[] floatBuffer = null;
        short[] shortBuffer = null;
        float[] convertBuffer = null;

        while (isRunning) {
            //判断是否处于录音状态，state!=3，说明没有处于录音的状态
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                isRunning = false;
                Log.d(TAG, String.format("录音失败，状态码：%d", audioRecord.getRecordingState()));
                break;
            }

            //切换组合后需要重建缓冲区
            if (lastCombo != comboIndex) {
                lastCombo = comboIndex;
                if (isFloatEncoding) {
                    floatBuffer = new float[getBufferFrames()];
                    shortBuffer = null;
                    convertBuffer = null;
                } else {
                    shortBuffer = new short[getBufferFrames()];
                    convertBuffer = new float[getBufferFrames()];
                    floatBuffer = null;
                }
            }

            int bufferReadResult;
            if (isFloatEncoding) {
                bufferReadResult = audioRecord.read(floatBuffer, 0, floatBuffer.length
                        , AudioRecord.READ_BLOCKING);
            } else {
                bufferReadResult = audioRecord.read(shortBuffer, 0, shortBuffer.length
                        , AudioRecord.READ_BLOCKING);
            }

            if (bufferReadResult < 0) {//读取错误
                isRunning = false;
                Log.d(TAG, String.format("录音读取错误，错误码：%d", bufferReadResult));
                break;
            }
            if (bufferReadResult == 0) continue;

            //16位数据转换为float，并顺带判断是否全0（静音）
            boolean allZero = true;
            if (isFloatEncoding) {
                for (int i = 0; i < bufferReadResult; i++) {
                    if (floatBuffer[i] != 0f) {
                        allZero = false;
                        break;
                    }
                }
            } else {
                for (int i = 0; i < bufferReadResult; i++) {
                    convertBuffer[i] = shortBuffer[i] / 32768.0f;
                    if (shortBuffer[i] != 0) allZero = false;
                }
            }

            if (!allZero) {
                lastNonZeroTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - loopStart > WARMUP_MS
                    && System.currentTimeMillis() - lastNonZeroTime > SILENT_MS) {
                //预热期后持续静音，判定该音源/编码组合采集不到有效音频，切换下一个
                Log.d(TAG, String.format("检测到持续静音，切换组合：%s/%s"
                        , currentSourceName(), isFloatEncoding ? "FLOAT" : "16BIT"));
                if (!switchToNextCombo()) {
                    isRunning = false;
                    ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                            R.string.recorder_cannot_record)
                            , "所有音源均无法采集到有效音频"));
                    break;
                }
                loopStart = System.currentTimeMillis();
                lastNonZeroTime = loopStart;
                continue;
            }

            if (onDataListener != null) {
                if (isFloatEncoding) {
                    onDataListener.onDataReceived(floatBuffer, bufferReadResult);
                } else {
                    onDataListener.onDataReceived(convertBuffer, bufferReadResult);
                }
            }
        }

        //停止录音
        try {
            if (audioRecord != null
                    && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();//停止录音
            }
        } catch (Exception e) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_stop_record_error), e.getMessage()));
            Log.d(TAG, "stopRecord: " + e.getMessage());
        }
    }

    /**
     * 切换到下一个音源/编码组合并重新开始录音。
     *
     * @return 是否切换成功
     */
    private boolean switchToNextCombo() {
        try {
            if (audioRecord != null) {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Exception e) {
            Log.d(TAG, "switchToNextCombo释放失败：" + e.getMessage());
        }

        comboIndex++;
        if (comboIndex >= SOURCES.length * ENCODINGS.length) {
            Log.e(TAG, "已尝试全部音源/编码组合，均无法采集到有效音频");
            return false;
        }

        if (!init()) return false;
        return startRecording();
    }

    /**
     * 停止录音。
     */
    public void stopRecord() {
        isRunning = false;
    }

    /**
     * 获取当前使用的音源/编码组合说明，用于界面显示或日志。
     */
    public String getComboDescription() {
        return currentSourceName() + "/" + (isFloatEncoding ? "FLOAT" : "16BIT");
    }

    public OnDataListener getOnDataListener() {
        return onDataListener;
    }

    public void setOnDataListener(OnDataListener onDataListener) {
        this.onDataListener = onDataListener;
    }
}
