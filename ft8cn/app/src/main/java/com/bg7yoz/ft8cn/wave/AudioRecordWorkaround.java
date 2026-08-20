package com.bg7yoz.ft8cn.wave;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 解决 vivo（及部分其它国产 ROM）手机上无法创建 AudioRecord 的问题。
 * <p>
 * 背景：vivo 在 Android 13 等系统上修改了 {@link AudioRecord} 的构造函数，内部会调用
 * {@code VivoAudioRecordImpl.isSupportSubMixRecording()} 去访问 Context，从而抛出
 * NullPointerException，导致 AudioRecord 创建失败、无法采集音频。
 * 参考：
 * - https://github.com/Genymobile/scrcpy/issues/3805
 * - https://github.com/Genymobile/scrcpy/pull/3862
 * <p>
 * 解决方案（与 scrcpy 的 Workarounds.createAudioRecord 相同思路）：
 * 先用反射调用私有构造器 {@code new AudioRecord(0L)} 创建一个空的 AudioRecord 实例，
 * 再反射初始化其内部字段并直接调用原生 {@code native_setup}，
 * 从而绕过 vivo 修改过的构造代码。
 *
 * @author BG7YOZ
 * @date 2026-08-20
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
public class AudioRecordWorkaround {
    private static final String TAG = "AudioRecordWorkaround";

    private AudioRecordWorkaround() {
    }

    /**
     * 反射创建 AudioRecord，绕过被厂商修改过的构造函数。
     *
     * @param context           应用上下文，用于提供 opPackageName / attributionSource
     * @param source            音频源，如 {@link android.media.MediaRecorder.AudioSource#MIC}
     * @param sampleRate        采样率
     * @param channelConfig     声道配置，如 {@link android.media.AudioFormat#CHANNEL_IN_MONO}
     * @param channelCount      声道数，单声道为 1
     * @param encoding          编码，如 {@link android.media.AudioFormat#ENCODING_PCM_FLOAT}
     * @param bufferSizeInBytes 缓冲区大小（字节）
     * @return 初始化成功的 AudioRecord
     * @throws Exception 反射失败或原生初始化失败时抛出
     */
    public static AudioRecord create(Context context, int source, int sampleRate,
                                     int channelConfig, int channelCount,
                                     int encoding, int bufferSizeInBytes) throws Exception {
        // AudioRecord audioRecord = new AudioRecord(0L);
        Constructor<AudioRecord> audioRecordConstructor =
                AudioRecord.class.getDeclaredConstructor(long.class);
        audioRecordConstructor.setAccessible(true);
        AudioRecord audioRecord = audioRecordConstructor.newInstance(0L);

        // audioRecord.mRecordingState = RECORDSTATE_STOPPED;
        Field mRecordingStateField = AudioRecord.class.getDeclaredField("mRecordingState");
        mRecordingStateField.setAccessible(true);
        mRecordingStateField.set(audioRecord, AudioRecord.RECORDSTATE_STOPPED);

        Looper looper = Looper.myLooper();
        if (looper == null) {
            looper = Looper.getMainLooper();
        }

        // audioRecord.mInitializationLooper = looper;
        Field mInitializationLooperField = AudioRecord.class.getDeclaredField("mInitializationLooper");
        mInitializationLooperField.setAccessible(true);
        mInitializationLooperField.set(audioRecord, looper);

        // 创建带采集预置的 AudioAttributes
        int capturePreset = source;
        AudioAttributes.Builder audioAttributesBuilder = new AudioAttributes.Builder();
        Method setInternalCapturePresetMethod = AudioAttributes.Builder.class
                .getMethod("setInternalCapturePreset", int.class);
        setInternalCapturePresetMethod.invoke(audioAttributesBuilder, capturePreset);
        AudioAttributes attributes = audioAttributesBuilder.build();

        // audioRecord.mAudioAttributes = attributes;
        Field mAudioAttributesField = AudioRecord.class.getDeclaredField("mAudioAttributes");
        mAudioAttributesField.setAccessible(true);
        mAudioAttributesField.set(audioRecord, attributes);

        // audioRecord.audioParamCheck(capturePreset, sampleRate, encoding);
        Method audioParamCheckMethod = AudioRecord.class
                .getDeclaredMethod("audioParamCheck", int.class, int.class, int.class);
        audioParamCheckMethod.setAccessible(true);
        audioParamCheckMethod.invoke(audioRecord, capturePreset, sampleRate, encoding);

        // audioRecord.mChannelCount = channelCount;
        Field mChannelCountField = AudioRecord.class.getDeclaredField("mChannelCount");
        mChannelCountField.setAccessible(true);
        mChannelCountField.set(audioRecord, channelCount);

        // audioRecord.mChannelMask = channelMask;
        Field mChannelMaskField = AudioRecord.class.getDeclaredField("mChannelMask");
        mChannelMaskField.setAccessible(true);
        mChannelMaskField.set(audioRecord, channelConfig);

        // audioRecord.audioBuffSizeCheck(bufferSizeInBytes);
        Method audioBuffSizeCheckMethod = AudioRecord.class.getDeclaredMethod("audioBuffSizeCheck", int.class);
        audioBuffSizeCheckMethod.setAccessible(true);
        audioBuffSizeCheckMethod.invoke(audioRecord, bufferSizeInBytes);

        final int channelIndexMask = 0;
        int[] sampleRateArray = new int[]{sampleRate};
        int[] session = new int[]{AudioManager.AUDIO_SESSION_ID_GENERATE};

        int initResult;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 11 (API 30) 及以下：native_setup 使用 String opPackageName
            // private native final int native_setup(Object audiorecord_this,
            // Object /*AudioAttributes*/ attributes,
            // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
            // int buffSizeInBytes, int[] sessionId, String opPackageName,
            // long nativeRecordInJavaObj);
            String opPackageName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//API 29起才有getOpPackageName
                opPackageName = context.getOpPackageName();
            } else {
                //API 23-28 没有 getOpPackageName，用包名代替
                opPackageName = context.getPackageName();
            }
            Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup",
                    Object.class, Object.class, int[].class, int.class, int.class,
                    int.class, int.class, int[].class, String.class, long.class);
            nativeSetupMethod.setAccessible(true);
            initResult = (int) nativeSetupMethod.invoke(audioRecord
                    , new WeakReference<>(audioRecord), attributes
                    , sampleRateArray, channelMask(channelConfig), channelIndexMask
                    , audioRecord.getAudioFormat(), bufferSizeInBytes, session
                    , opPackageName, 0L);
        } else {
            // Android 12 (API 31) 及以上：native_setup 使用 AttributionSource 的 Parcel
            AttributionSource attributionSource = context.getAttributionSource();
            if (attributionSource == null) {
                throw new NullPointerException("getAttributionSource() 返回 null");
            }

            // ScopedParcelState attributionSourceState = attributionSource.asScopedParcelState();
            Method asScopedParcelStateMethod = AttributionSource.class
                    .getDeclaredMethod("asScopedParcelState");
            asScopedParcelStateMethod.setAccessible(true);

            try (AutoCloseable attributionSourceState
                         = (AutoCloseable) asScopedParcelStateMethod.invoke(attributionSource)) {
                Method getParcelMethod = attributionSourceState.getClass().getDeclaredMethod("getParcel");
                Parcel attributionSourceParcel = (Parcel) getParcelMethod.invoke(attributionSourceState);

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 12/13 (API 31-33)
                    // private native int native_setup(Object audiorecordThis,
                    // Object /*AudioAttributes*/ attributes,
                    // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
                    // int buffSizeInBytes, int[] sessionId, @NonNull Parcel attributionSource,
                    // long nativeRecordInJavaObj, int maxSharedAudioHistoryMs);
                    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup",
                            Object.class, Object.class, int[].class, int.class, int.class,
                            int.class, int.class, int[].class, Parcel.class, long.class, int.class);
                    nativeSetupMethod.setAccessible(true);
                    initResult = (int) nativeSetupMethod.invoke(audioRecord
                            , new WeakReference<>(audioRecord), attributes
                            , sampleRateArray, channelMask(channelConfig), channelIndexMask
                            , audioRecord.getAudioFormat(), bufferSizeInBytes, session
                            , attributionSourceParcel, 0L, 0);
                } else {
                    // Android 14 (API 34) 及以上：增加了 int halInputFlags 参数
                    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup",
                            Object.class, Object.class, int[].class, int.class, int.class,
                            int.class, int.class, int[].class, Parcel.class, long.class,
                            int.class, int.class);
                    nativeSetupMethod.setAccessible(true);
                    initResult = (int) nativeSetupMethod.invoke(audioRecord
                            , new WeakReference<>(audioRecord), attributes
                            , sampleRateArray, channelMask(channelConfig), channelIndexMask
                            , audioRecord.getAudioFormat(), bufferSizeInBytes, session
                            , attributionSourceParcel, 0L, 0, 0);
                }
            }
        }

        if (initResult != AudioRecord.SUCCESS) {
            Log.e(TAG, String.format("native_setup 失败，错误码：%d", initResult));
            throw new RuntimeException("Cannot create AudioRecord");
        }

        // audioRecord.mSampleRate = sampleRate[0];
        Field mSampleRateField = AudioRecord.class.getDeclaredField("mSampleRate");
        mSampleRateField.setAccessible(true);
        mSampleRateField.set(audioRecord, sampleRateArray[0]);

        // audioRecord.mSessionId = session[0];
        Field mSessionIdField = AudioRecord.class.getDeclaredField("mSessionId");
        mSessionIdField.setAccessible(true);
        mSessionIdField.set(audioRecord, session[0]);

        // audioRecord.mState = AudioRecord.STATE_INITIALIZED;
        Field mStateField = AudioRecord.class.getDeclaredField("mState");
        mStateField.setAccessible(true);
        mStateField.set(audioRecord, AudioRecord.STATE_INITIALIZED);

        return audioRecord;
    }

    /**
     * 把声道配置常量转换为 AudioRecord 需要的 channelMask。
     * 单声道 CHANNEL_IN_MONO 本身就是 channelMask 语义，直接返回。
     */
    private static int channelMask(int channelConfig) {
        return channelConfig;
    }
}
