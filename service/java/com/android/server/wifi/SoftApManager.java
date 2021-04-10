/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import com.sprd.server.wifi.VoWifiAssistor;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;
import static com.android.server.wifi.WifiController.CMD_RESTART_AP;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.util.ApConfigUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiFeaturesUtils;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.net.wifi.WpsResult.Status;
import android.os.Message;
//import com.android.sprd.telephony.RadioInteractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.server.wifi.WifiApClientStats;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under the ClientModeImpl handler thread context.
 */
public class SoftApManager implements ActiveModeManager {
    private static final String TAG = "SoftApManager";

    // Minimum limit to use for timeout delay if the value from overlay setting is too small.
    private static final int MIN_SOFT_AP_TIMEOUT_DELAY_MS = 600_000;  // 10 minutes

    @VisibleForTesting
    public static final String SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG = TAG
            + " Soft AP Send Message Timeout";

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiNative mWifiNative;

    private String mCountryCode;

    private final SoftApStateMachine mStateMachine;
    //private RadioInteractor mRadioInteractor;
    private static boolean DBG = true;
    private String mConnectedStations = "";
    private WifiApClientStats mWifiApClientStats = null;
    private int mMacAddrAcl = 0;
    private BroadcastReceiver mSoftApBroadcastReceiver;
    static WifiInjector mWifiInjector = null;
    String mInterfaceName = null;
    private static final int HOSTAPD_UPDATE_5G_CHANNEL_DELAY = 2000;
    private static final int HOSTAPD_SUPPORT_5G_CHANNEL_MIN = 36;
    private static final int HOSTAPD_SUPPORT_5G_CHANNEL_MAX = 165;
    private static final int HOSTAPD_SUPPORT_CHANNEL_INIT = 0;

    private final WifiManager.SoftApCallback mCallback;

    private String mApInterfaceName;
    private boolean mIfaceIsUp;
    private boolean mIfaceIsDestroyed;

    private final WifiApConfigStore mWifiApConfigStore;

    private final WifiMetrics mWifiMetrics;

    private final int mMode;
    private WifiConfiguration mApConfig;

    private int mReportedFrequency = -1;
    private int mReportedBandwidth = -1;

    private int mNumAssociatedStations = 0;
    private boolean mTimeoutEnabled = false;

    private final SarManager mSarManager;
    private VoWifiAssistor mVoWifiAssistor;

    private long mStartTimestamp = -1;
    protected void log(String s) {
    Log.d(TAG, s);
    }
    protected void loge(String s) {
        Log.e(TAG, s);
    }

    private static class TetherStateChange {
        public ArrayList<String> available;
        public ArrayList<String> active;

        TetherStateChange(ArrayList<String> av, ArrayList<String> ac) {
            available = av;
            active = ac;
        }
    }
    /**
     * Listener for soft AP events.
     */
    private final SoftApListener mSoftApListener = new SoftApListener() {

        @Override
        public void onFailure() {
            mStateMachine.sendMessage(SoftApStateMachine.CMD_FAILURE);
        }

        @Override
        public void onNumAssociatedStationsChanged(int numStations) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_NUM_ASSOCIATED_STATIONS_CHANGED, numStations);
        }

        @Override
        public void onSoftApChannelSwitched(int frequency, int bandwidth) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_SOFT_AP_CHANNEL_SWITCHED, frequency, bandwidth);
        }

        @Override
        public void onSoftApConnectionEvent(String mac) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.SOFTAP_STA_CONNECTED_EVENT, mac);
        }

        @Override
        public void onSoftApDisconnectionEvent(String mac) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.SOFTAP_STA_DISCONNECTED_EVENT, mac);
        }
    };

    public SoftApManager(@NonNull Context context,
                         @NonNull Looper looper,
                         @NonNull FrameworkFacade framework,
                         @NonNull WifiNative wifiNative,
                         String countryCode,
                         @NonNull WifiManager.SoftApCallback callback,
                         @NonNull WifiApConfigStore wifiApConfigStore,
                         @NonNull SoftApModeConfiguration apConfig,
                         @NonNull WifiMetrics wifiMetrics,
                         @NonNull SarManager sarManager) {
        mContext = context;
        mFrameworkFacade = framework;
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mCallback = callback;
        mWifiInjector = WifiInjector.getInstance();
        if (mWifiInjector != null) {
            mWifiApClientStats = mWifiInjector.getWifiApClientStats();
        }
        mWifiApConfigStore = wifiApConfigStore;
        mMode = apConfig.getTargetMode();
        WifiConfiguration config = apConfig.getWifiConfiguration();
        if (config == null) {
            mApConfig = mWifiApConfigStore.getApConfiguration();
        } else {
            mApConfig = config;
        }
        mWifiMetrics = wifiMetrics;
        mSarManager = sarManager;
        mStateMachine = new SoftApStateMachine(looper);

        Log.d(TAG, "Using SPRD softap features : " + WifiFeaturesUtils.FeatureProperty.SUPPORT_SPRD_SOFTAP_FEATURES
                + " maxstanum = " + WifiFeaturesUtils.FeatureProperty.SUPPORT_SPRD_SOFTAP_MAX_NUMBER);

    }

    public SoftApManager(@NonNull Context context,
                         @NonNull Looper looper,
                         @NonNull FrameworkFacade framework,
                         @NonNull WifiNative wifiNative,
                         String countryCode,
                         @NonNull WifiManager.SoftApCallback callback,
                         @NonNull WifiApConfigStore wifiApConfigStore,
                         @NonNull SoftApModeConfiguration apConfig,
                         @NonNull WifiMetrics wifiMetrics,
                         @NonNull SarManager sarManager,
                         @NonNull VoWifiAssistor voWifiAssistor) {
        mContext = context;
        mFrameworkFacade = framework;
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mCallback = callback;
        mWifiInjector = WifiInjector.getInstance();
        if (mWifiInjector != null) {
            mWifiApClientStats = mWifiInjector.getWifiApClientStats();
        }
        mWifiApConfigStore = wifiApConfigStore;
        mMode = apConfig.getTargetMode();
        WifiConfiguration config = apConfig.getWifiConfiguration();
        if (config == null) {
            mApConfig = mWifiApConfigStore.getApConfiguration();
        } else {
            mApConfig = config;
        }
        mWifiMetrics = wifiMetrics;
        mSarManager = sarManager;
        mStateMachine = new SoftApStateMachine(looper);
        mVoWifiAssistor = voWifiAssistor;

        Log.d(TAG, "Using SPRD softap features : " + WifiFeaturesUtils.FeatureProperty.SUPPORT_SPRD_SOFTAP_FEATURES
                + " maxstanum = " + WifiFeaturesUtils.FeatureProperty.SUPPORT_SPRD_SOFTAP_MAX_NUMBER);
    }

    /**
     * Start soft AP with the supplied config.
     */
    public void start() {
        if (mVoWifiAssistor.isVoWifiAttached()) {
            mVoWifiAssistor.delayStartSoftAP(this);
        } else {
            startImmediately();
        }
    }

    public void startImmediately() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START, mApConfig);
    }

    /**
     * Stop soft AP.
     */
    public void stop() {
        Log.d(TAG, " currentstate: " + getCurrentStateName());
        if (mApInterfaceName != null) {
            if (mIfaceIsUp) {
                updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                        WifiManager.WIFI_AP_STATE_ENABLED, 0);
            } else {
                updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                        WifiManager.WIFI_AP_STATE_ENABLING, 0);
            }
        }
        mStateMachine.quitNow();
    }

    public @ScanMode int getScanMode() {
        return SCAN_NONE;
    }

    public int getIpMode() {
        return mMode;
    }

    /**
     * Dump info about this softap manager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of SoftApManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mApInterfaceName: " + mApInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        pw.println("mMode: " + mMode);
        pw.println("mCountryCode: " + mCountryCode);
        if (mApConfig != null) {
            pw.println("mApConfig.SSID: " + mApConfig.SSID);
            pw.println("mApConfig.apBand: " + mApConfig.apBand);
            pw.println("mApConfig.hiddenSSID: " + mApConfig.hiddenSSID);
        } else {
            pw.println("mApConfig: null");
        }
        pw.println("mNumAssociatedStations: " + mNumAssociatedStations);
        pw.println("mTimeoutEnabled: " + mTimeoutEnabled);
        pw.println("mReportedFrequency: " + mReportedFrequency);
        pw.println("mReportedBandwidth: " + mReportedBandwidth);
        pw.println("mStartTimestamp: " + mStartTimestamp);
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update AP state.
     * @param newState new AP state
     * @param currentState current AP state
     * @param reason Failure reason if the new AP state is in failure state
     */
    private void updateApState(int newState, int currentState, int reason) {
        mCallback.onStateChanged(newState, reason);

        //send the AP state change broadcast
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, currentState);
        if (newState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }

        intent.putExtra(WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME, mApInterfaceName);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_MODE, mMode);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Start a soft AP instance with the given configuration.
     * @param config AP configuration
     * @return integer result code
     */
    private int startSoftAp(WifiConfiguration config) {
        if (config == null || config.SSID == null) {
            Log.e(TAG, "Unable to start soft AP without valid configuration");
            return ERROR_GENERIC;
        }
        if (mWifiApClientStats != null && mWifiApClientStats.isWhiteListEnabled()) {
             config.macAddrAcl = 1;
        } else {
             config.macAddrAcl = 0;
        }

        // UNISOC: Try to get saved countrycode if current country code empty
        if (TextUtils.isEmpty(mCountryCode)) {
            mCountryCode = mFrameworkFacade.getStringSetting(mContext,
                Settings.Global.WIFI_COUNTRY_CODE);
        }

        // Setup country code
        if (TextUtils.isEmpty(mCountryCode)) {
            if (config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                // Country code is mandatory for 5GHz band.
                Log.e(TAG, "Invalid country code, required for setting up "
                        + "soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Absence of country code is not fatal for 2Ghz & Any band options.
        } else if (!mWifiNative.setCountryCodeHal(
                mApInterfaceName, mCountryCode.toUpperCase(Locale.ROOT))) {
            if (config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                // Return an error if failed to set country code when AP is configured for
                // 5GHz band.
                Log.e(TAG, "Failed to set country code, required for setting up "
                        + "soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Failure to set country code is not fatal for 2Ghz & Any band options.
        }

        // Make a copy of configuration for updating AP band and channel.
        WifiConfiguration localConfig = new WifiConfiguration(config);

        int result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mCountryCode,
                mWifiApConfigStore.getAllowed2GChannel(), localConfig);

        if (result != SUCCESS) {
            Log.e(TAG, "Failed to update AP band and channel");
            return result;
        } else {
            if (localConfig.apBand == WifiConfiguration.AP_BAND_5GHZ && mCountryCode != null) {
                getSoftApSupportChannels();
            }
        }

        if (localConfig.hiddenSSID) {
            Log.d(TAG, "SoftAP is a hidden network");
        }
        if (!mWifiNative.startSoftAp(mApInterfaceName, localConfig, mSoftApListener)) {
            Log.e(TAG, "Soft AP start failed");
            return ERROR_GENERIC;
        }
        mStartTimestamp = SystemClock.elapsedRealtime();
        Log.d(TAG, "Soft AP is started");

        return SUCCESS;
    }

    /**
     * Teardown soft AP and teardown the interface.
     */
    private void stopSoftAp() {
        //SPRD: Add for unregister SoftApBroadcastReceiver When softap is disabling BEG-->
        if (mContext != null && mSoftApBroadcastReceiver != null) {
            mContext.unregisterReceiver(mSoftApBroadcastReceiver);
        }
        //<-- Add for unregister SoftApBroadcastReceiver When softap is disabling END

        mWifiNative.teardownInterface(mApInterfaceName);
        //mRadioInteractor.enableRadioPowerFallback(false,0);
        Log.d(TAG, "Soft AP is stopped");
    }

    private class SoftApStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_FAILURE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_NUM_ASSOCIATED_STATIONS_CHANGED = 4;
        public static final int CMD_NO_ASSOCIATED_STATIONS_TIMEOUT = 5;
        public static final int CMD_TIMEOUT_TOGGLE_CHANGED = 6;
        public static final int CMD_INTERFACE_DESTROYED = 7;
        public static final int CMD_INTERFACE_DOWN = 8;
        public static final int CMD_SOFT_AP_CHANNEL_SWITCHED = 9;
        public static final int CMD_TETHER_STATE_CHANGE = 10;
        public static final int SOFTAP_STA_CONNECTED_EVENT = 12;
        public static final int SOFTAP_STA_DISCONNECTED_EVENT = 13;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                mApInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        mApInterfaceName = mWifiNative.setupInterfaceForSoftApMode(
                                mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mApInterfaceName)) {
                            Log.e(TAG, "setup failure when creating ap interface.");
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_DISABLED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            break;
                        }
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                                WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        int result = startSoftAp((WifiConfiguration) message.obj);
                        if (result != SUCCESS) {
                            int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            }
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                          WifiManager.WIFI_AP_STATE_ENABLING,
                                          failureReason);
                            stopSoftAp();
                            mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                            break;
                        }
                        transitionTo(mStartedState);
                        break;
                    default:
                        // Ignore all other commands.
                        break;
                }

                return HANDLED;
            }
        }

        private class StartedState extends State {
            private int mTimeoutDelay;
            private WakeupMessage mSoftApTimeoutMessage;
            private SoftApTimeoutEnabledSettingObserver mSettingObserver;

            /**
            * Observer for timeout settings changes.
            */
            private class SoftApTimeoutEnabledSettingObserver extends ContentObserver {
                SoftApTimeoutEnabledSettingObserver(Handler handler) {
                    super(handler);
                }

                public void register() {
                    mFrameworkFacade.registerContentObserver(mContext,
                            Settings.Global.getUriFor(Settings.Global.SOFT_AP_TIMEOUT_ENABLED),
                            true, this);
                    mTimeoutEnabled = getValue();
                }

                public void unregister() {
                    mFrameworkFacade.unregisterContentObserver(mContext, this);
                }

                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    mStateMachine.sendMessage(SoftApStateMachine.CMD_TIMEOUT_TOGGLE_CHANGED,
                            getValue() ? 1 : 0);
                }

                private boolean getValue() {
                    boolean enabled = mFrameworkFacade.getIntegerSetting(mContext,
                            Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1) == 1;
                    return enabled;
                }
            }

            private int getConfigSoftApTimeoutDelay() {
                int delay = mContext.getResources().getInteger(
                        R.integer.config_wifi_framework_soft_ap_timeout_delay);
                if (delay < MIN_SOFT_AP_TIMEOUT_DELAY_MS) {
                    delay = MIN_SOFT_AP_TIMEOUT_DELAY_MS;
                    Log.w(TAG, "Overriding timeout delay with minimum limit value");
                }
                Log.d(TAG, "Timeout delay: " + delay);
                return delay;
            }

            private void scheduleTimeoutMessage() {
                if (!mTimeoutEnabled) {
                    return;
                }
                mSoftApTimeoutMessage.schedule(SystemClock.elapsedRealtime() + mTimeoutDelay);
                Log.d(TAG, "Timeout message scheduled");
            }

            private void cancelTimeoutMessage() {
                mSoftApTimeoutMessage.cancel();
                Log.d(TAG, "Timeout message canceled");
            }

            /**
             * Set number of stations associated with this soft AP
             * @param numStations Number of connected stations
             */
            private void setNumAssociatedStations(int numStations) {
                if (mNumAssociatedStations == numStations) {
                    return;
                }
                mNumAssociatedStations = numStations;
                Log.d(TAG, "Number of associated stations changed: " + mNumAssociatedStations);

                if (mCallback != null) {
                    mCallback.onNumClientsChanged(mNumAssociatedStations);
                } else {
                    Log.e(TAG, "SoftApCallback is null. Dropping NumClientsChanged event.");
                }
                mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(mNumAssociatedStations,
                        mMode);

                if (mNumAssociatedStations == 0) {
                    scheduleTimeoutMessage();
                } else {
                    cancelTimeoutMessage();
                }
            }

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "SoftAp is ready for use");
                    if (mApInterfaceName != null) {
                        syncSoftApWhiteList();
                        if (mWifiApClientStats != null) {
                            boolean whiteMode = mWifiApClientStats.isWhiteListEnabled();
                            syncSoftApSetClientWhiteListEnabled(whiteMode);
                        }
                    }
                    if (mWifiApClientStats != null) {
                        mWifiApClientStats.start();
                    }
                    updateApState(WifiManager.WIFI_AP_STATE_ENABLED,
                            WifiManager.WIFI_AP_STATE_ENABLING, 0);
                    if (mCallback != null) {
                        mCallback.onNumClientsChanged(mNumAssociatedStations);
                    }
                    mWifiMetrics.incrementSoftApStartResult(true, 0);
                } else {
                    // the interface was up, but goes down
                    sendMessage(CMD_INTERFACE_DOWN);
                }
                mWifiMetrics.addSoftApUpChangedEvent(isUp, mMode);
            }

            @Override
            public void enter() {
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                onUpChanged(mWifiNative.isInterfaceUp(mApInterfaceName));

                mTimeoutDelay = getConfigSoftApTimeoutDelay();
                Handler handler = mStateMachine.getHandler();
                mSoftApTimeoutMessage = new WakeupMessage(mContext, handler,
                        SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG,
                        SoftApStateMachine.CMD_NO_ASSOCIATED_STATIONS_TIMEOUT);
                mSettingObserver = new SoftApTimeoutEnabledSettingObserver(handler);

                if (mSettingObserver != null) {
                    mSettingObserver.register();
                }

                mSarManager.setSapWifiState(WifiManager.WIFI_AP_STATE_ENABLED);

                Log.d(TAG, "Resetting num stations on start");
                mNumAssociatedStations = 0;
                scheduleTimeoutMessage();
            }

            @Override
            public void exit() {
                if (mWifiApClientStats != null) {
                    mWifiApClientStats.stop();
                }
                if (!mIfaceIsDestroyed) {
                    stopSoftAp();
                }

                if (mSettingObserver != null) {
                    mSettingObserver.unregister();
                }
                Log.d(TAG, "Resetting num stations on stop");
                mConnectedStations = "";
                setNumAssociatedStations(0);
                cancelTimeoutMessage();
                // Need this here since we are exiting |Started| state and won't handle any
                // future CMD_INTERFACE_STATUS_CHANGED events after this point
                mWifiMetrics.addSoftApUpChangedEvent(false, mMode);
                updateApState(WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.WIFI_AP_STATE_DISABLING, 0);

                mSarManager.setSapWifiState(WifiManager.WIFI_AP_STATE_DISABLED);
                mApInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                mStateMachine.quitNow();
            }

            private void updateUserBandPreferenceViolationMetricsIfNeeded() {
                boolean bandPreferenceViolated = false;
                if (mApConfig.apBand == WifiConfiguration.AP_BAND_2GHZ
                        && ScanResult.is5GHz(mReportedFrequency)) {
                    bandPreferenceViolated = true;
                } else if (mApConfig.apBand == WifiConfiguration.AP_BAND_5GHZ
                        && ScanResult.is24GHz(mReportedFrequency)) {
                    bandPreferenceViolated = true;
                }
                if (bandPreferenceViolated) {
                    Log.e(TAG, "Channel does not satisfy user band preference: "
                            + mReportedFrequency);
                    mWifiMetrics.incrementNumSoftApUserBandPreferenceUnsatisfied();
                }
            }

            @Override
            public boolean processMessage(Message message) {
                String macStr = "";
                switch (message.what) {
                    case CMD_NUM_ASSOCIATED_STATIONS_CHANGED:
                        if (message.arg1 < 0) {
                            Log.e(TAG, "Invalid number of associated stations: " + message.arg1);
                            break;
                        }
                        //Log.d(TAG, "Setting num stations on CMD_NUM_ASSOCIATED_STATIONS_CHANGED");
                        //setNumAssociatedStations(message.arg1);
                        break;
                    case CMD_SOFT_AP_CHANNEL_SWITCHED:
                        mReportedFrequency = message.arg1;
                        mReportedBandwidth = message.arg2;
                        Log.d(TAG, "Channel switched. Frequency: " + mReportedFrequency
                                + " Bandwidth: " + mReportedBandwidth);
                        mWifiMetrics.addSoftApChannelSwitchedEvent(mReportedFrequency,
                                mReportedBandwidth, mMode);
                        updateUserBandPreferenceViolationMetricsIfNeeded();
                        break;
                    case CMD_TIMEOUT_TOGGLE_CHANGED:
                        boolean isEnabled = (message.arg1 == 1);
                        if (mTimeoutEnabled == isEnabled) {
                            break;
                        }
                        mTimeoutEnabled = isEnabled;
                        if (!mTimeoutEnabled) {
                            cancelTimeoutMessage();
                        }
                        if (mTimeoutEnabled && mNumAssociatedStations == 0) {
                            scheduleTimeoutMessage();
                        }
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_NO_ASSOCIATED_STATIONS_TIMEOUT:
                        if (!mTimeoutEnabled) {
                            Log.wtf(TAG, "Timeout message received while timeout is disabled."
                                    + " Dropping.");
                            break;
                        }
                        if (mNumAssociatedStations != 0) {
                            Log.wtf(TAG, "Timeout message received but has clients. Dropping.");
                            break;
                        }
                        Log.i(TAG, "Timeout message received. Stopping soft AP.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(TAG, "Interface was cleanly destroyed.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        mIfaceIsDestroyed = true;
                        transitionTo(mIdleState);
                        break;
                    case CMD_FAILURE:
                        Log.w(TAG, "hostapd failure, stop and report failure");
                        /* fall through */
                    case CMD_INTERFACE_DOWN:
                        Log.w(TAG, "interface error, stop and report failure");
                        updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                WifiManager.WIFI_AP_STATE_ENABLED,
                                WifiManager.SAP_START_FAILURE_GENERAL);
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_FAILED, 0);
                        mWifiInjector.getWifiController().sendMessage(CMD_RESTART_AP);
                        transitionTo(mIdleState);
                        break;
                   case SOFTAP_STA_CONNECTED_EVENT:
                        macStr = (String)message.obj;
                        //showToast("Station: " + macStr + " now is connected!");
                        if (DBG) log("Soft AP Connected Station: " + macStr);
                        if(mConnectedStations.equals("") ) {
                            mConnectedStations = macStr;
                        } else {
                            if (mConnectedStations.contains(macStr)) {
                                loge("Soft AP Connected Station: " + macStr + " already saved!");
                            } else {
                                mConnectedStations += (" " + macStr);
                            }
                        }
                        if (mWifiApClientStats != null) {
                            mWifiApClientStats.addOrUpdateClientInfoList(mConnectedStations);
                        }
                        List<String> mConnectedStationsDetail = mWifiApClientStats.getStaClientInfoList();
                        setNumAssociatedStations(mConnectedStationsDetail.size());
                        sendSoftApConnectionChangedBroadcast();
                        break;
                    case SOFTAP_STA_DISCONNECTED_EVENT:
                        macStr = (String)message.obj;
                        //showToast("Station: " + macStr + " now is disconnected!");
                        if (DBG) log("Soft AP Disconnected Station: "+ macStr);
                        if (mConnectedStations.contains(macStr) ) {
                            String[] dataTokens = mConnectedStations.split(" ");
                            mConnectedStations = "";
                            for (String token : dataTokens) {
                                if (token.equals(macStr)) {
                                    continue;
                                }
                                if(mConnectedStations.equals("") ) {
                                    mConnectedStations = token;
                                } else {
                                    mConnectedStations +=  (" " + token);
                                }
                            }
                        }
                        if (mWifiApClientStats != null) {
                            mWifiApClientStats.addOrUpdateClientInfoList(mConnectedStations);
                        }
                        List<String> mConnectedStationsDetailList = mWifiApClientStats.getStaClientInfoList();
                        setNumAssociatedStations(mConnectedStationsDetailList.size());
                        sendSoftApConnectionChangedBroadcast();
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private void syncSoftApWhiteList() {
            if (mWifiApClientStats != null) {
                List<String> macList = mWifiApClientStats.getClientMacWhiteList();
                for (String mac : macList) {
                    mWifiNative.softApSetStationToWhiteList(mac, true);
                }
            }
        }

        //SPRD: Repeatedly open/close softap, it will cause phone restart BEG-->
        private void unregisterSoftapEventHandler() {
            //SPRD: Add for unregister SoftApBroadcastReceiver When softap is disabling BEG-->
            if (mContext != null && mSoftApBroadcastReceiver != null) {
                mContext.unregisterReceiver(mSoftApBroadcastReceiver);
            }
            //<-- Add for unregister SoftApBroadcastReceiver When softap is disabling END
        }
    }

    private void getSoftApSupportChannels() {
        StringBuffer buf = new StringBuffer();
        int[] allowed5GFreqList = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
        if (allowed5GFreqList != null && allowed5GFreqList.length > 0) {
            for (int i = 0; i < allowed5GFreqList.length; i++){
                int channel = ApConfigUtil.convertFrequencyToChannel(allowed5GFreqList[i]);
                buf.append(channel);
                buf.append(",");
            }
        }
        if (buf != null) {
            String allowed5GChannelList = buf.toString();
            Log.d(TAG,"SoftAp allowed5GChannelList = " + allowed5GChannelList);
            Settings.Global.putString(mContext.getContentResolver(), WifiFeaturesUtils.SOFTAP_SUPPORT_CHANNELS, allowed5GChannelList);
        }
}

    /**
      * To enable the white list or not
      * in enabled
      *      true: enable white list
      *      false: disable white list
      */
     public boolean syncSoftApSetClientWhiteListEnabled(boolean enabled) {
         return mWifiNative.softApSetWhiteListEnabled(enabled);
     }

    private void sendSoftApConnectionChangedBroadcast(){
        final Intent intent = new Intent(WifiManager.WIFI_AP_CONNECTION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }
    private void sendSoftApBlockListAvailableBroadcast() {
        Intent intent = new Intent(WifiManager.SOFTAP_BLOCKLIST_AVAILABLE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }
}
