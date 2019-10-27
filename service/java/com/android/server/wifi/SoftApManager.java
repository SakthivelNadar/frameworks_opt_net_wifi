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

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
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
import com.android.internal.util.Preconditions;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.wificond.NativeWifiClient;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    private final String mCountryCode;

    private final SoftApStateMachine mStateMachine;

    private final Listener mModeListener;
    private final WifiManager.SoftApCallback mSoftApCallback;

    private String mApInterfaceName;
    private boolean mIfaceIsUp;
    private boolean mIfaceIsDestroyed;

    private final WifiApConfigStore mWifiApConfigStore;

    private final WifiMetrics mWifiMetrics;

    @NonNull
    private SoftApModeConfiguration mApConfig;

    private int mReportedFrequency = -1;
    private int mReportedBandwidth = -1;

    private List<WifiClient> mConnectedClients = new ArrayList<>();
    private boolean mTimeoutEnabled = false;

    private final SarManager mSarManager;

    private String mStartTimestamp;

    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private BaseWifiDiagnostics mWifiDiagnostics;

    private @Role int mRole = ROLE_UNSPECIFIED;

    /**
     * Listener for soft AP events.
     */
    private final SoftApListener mSoftApListener = new SoftApListener() {

        @Override
        public void onFailure() {
            mStateMachine.sendMessage(SoftApStateMachine.CMD_FAILURE);
        }

        @Override
        public void onConnectedClientsChanged(List<NativeWifiClient> clients) {
            mStateMachine.sendMessage(SoftApStateMachine.CMD_ASSOCIATED_STATIONS_CHANGED,
                    clients);
        }

        @Override
        public void onSoftApChannelSwitched(int frequency, int bandwidth) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_SOFT_AP_CHANNEL_SWITCHED, frequency, bandwidth);
        }
    };

    public SoftApManager(@NonNull Context context,
                         @NonNull Looper looper,
                         @NonNull FrameworkFacade framework,
                         @NonNull WifiNative wifiNative,
                         String countryCode,
                         @NonNull Listener listener,
                         @NonNull WifiManager.SoftApCallback callback,
                         @NonNull WifiApConfigStore wifiApConfigStore,
                         @NonNull SoftApModeConfiguration apConfig,
                         @NonNull WifiMetrics wifiMetrics,
                         @NonNull SarManager sarManager,
                         @NonNull BaseWifiDiagnostics wifiDiagnostics) {
        mContext = context;
        mFrameworkFacade = framework;
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mModeListener = listener;
        mSoftApCallback = callback;
        mWifiApConfigStore = wifiApConfigStore;
        mApConfig = apConfig;
        if (mApConfig.getWifiConfiguration() == null) {
            WifiConfiguration wifiConfig = mWifiApConfigStore.getApConfiguration();
            mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(), wifiConfig);
        }
        mWifiMetrics = wifiMetrics;
        mSarManager = sarManager;
        mWifiDiagnostics = wifiDiagnostics;
        mStateMachine = new SoftApStateMachine(looper);
    }

    /**
     * Start soft AP, as configured in the constructor.
     */
    @Override
    public void start() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START);
    }

    /**
     * Stop soft AP.
     */
    @Override
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

    @Override
    public @Role int getRole() {
        return mRole;
    }

    @Override
    public void setRole(@Role int role) {
        // softap does not allow in-place switching of roles.
        Preconditions.checkState(mRole == ROLE_UNSPECIFIED);
        Preconditions.checkState(SOFTAP_ROLES.contains(role));
        mRole = role;
    }

    /**
     * Dump info about this softap manager.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of SoftApManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mRole: " + mRole);
        pw.println("mApInterfaceName: " + mApInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        pw.println("mSoftApCountryCode: " + mCountryCode);
        pw.println("mApConfig.targetMode: " + mApConfig.getTargetMode());
        WifiConfiguration wifiConfig = mApConfig.getWifiConfiguration();
        pw.println("mApConfig.wifiConfiguration.SSID: " + wifiConfig.SSID);
        pw.println("mApConfig.wifiConfiguration.apBand: " + wifiConfig.apBand);
        pw.println("mApConfig.wifiConfiguration.hiddenSSID: " + wifiConfig.hiddenSSID);
        pw.println("mConnectedClients.size(): " + mConnectedClients.size());
        pw.println("mTimeoutEnabled: " + mTimeoutEnabled);
        pw.println("mReportedFrequency: " + mReportedFrequency);
        pw.println("mReportedBandwidth: " + mReportedBandwidth);
        pw.println("mStartTimestamp: " + mStartTimestamp);
        mStateMachine.dump(fd, pw, args);
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
     *
     * @param newState     new AP state
     * @param currentState current AP state
     * @param reason       Failure reason if the new AP state is in failure state
     */
    private void updateApState(int newState, int currentState, int reason) {
        mSoftApCallback.onStateChanged(newState, reason);

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
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_MODE, mApConfig.getTargetMode());
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int setMacAddress() {
        boolean randomize = mContext.getResources().getBoolean(
                R.bool.config_wifi_ap_mac_randomization_supported);
        if (!randomize) {
            MacAddress mac = mWifiNative.getFactoryMacAddress(mApInterfaceName);
            if (mac == null) {
                Log.e(TAG, "failed to get factory MAC address");
                return ERROR_GENERIC;
            }

            // We're (re-)configuring the factory MAC address. Some drivers may not support setting
            // the MAC at all, so fail soft in this case.
            if (!mWifiNative.setMacAddress(mApInterfaceName, mac)) {
                Log.w(TAG, "failed to reset to factory MAC address; continuing with current MAC");
            }
            return SUCCESS;
        }

        // We're configuring a random MAC address. In this case, driver support is mandatory.
        MacAddress mac = MacAddress.createRandomUnicastAddress();
        if (!mWifiNative.setMacAddress(mApInterfaceName, mac)) {
            Log.e(TAG, "failed to set random MAC address");
            return ERROR_GENERIC;
        }
        return SUCCESS;
    }

    private int setCountryCode() {
        int band = mApConfig.getWifiConfiguration().apBand;
        if (TextUtils.isEmpty(mCountryCode)) {
            if (band == WifiConfiguration.AP_BAND_5GHZ) {
                // Country code is mandatory for 5GHz band.
                Log.e(TAG, "Invalid country code, required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Absence of country code is not fatal for 2Ghz & Any band options.
            return SUCCESS;
        }

        if (!mWifiNative.setCountryCodeHal(
                mApInterfaceName, mCountryCode.toUpperCase(Locale.ROOT))) {
            if (band == WifiConfiguration.AP_BAND_5GHZ) {
                // Return an error if failed to set country code when AP is configured for
                // 5GHz band.
                Log.e(TAG, "Failed to set country code, required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Failure to set country code is not fatal for 2Ghz & Any band options.
        }
        return SUCCESS;
    }

    /**
     * Start a soft AP instance as configured.
     *
     * @return integer result code
     */
    private int startSoftAp() {
        WifiConfiguration config = mApConfig.getWifiConfiguration();
        if (config == null || config.SSID == null) {
            Log.e(TAG, "Unable to start soft AP without valid configuration");
            return ERROR_GENERIC;
        }

        Log.d(TAG, "band " + config.apBand + " iface "
                + mApInterfaceName + " country " + mCountryCode);

        int result = setMacAddress();
        if (result != SUCCESS) {
            return result;
        }

        result = setCountryCode();
        if (result != SUCCESS) {
            return result;
        }

        // Make a copy of configuration for updating AP band and channel.
        WifiConfiguration localConfig = new WifiConfiguration(config);

        result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mCountryCode,
                mWifiApConfigStore.getAllowed2GChannel(), localConfig);

        if (result != SUCCESS) {
            Log.e(TAG, "Failed to update AP band and channel");
            return result;
        }

        if (localConfig.hiddenSSID) {
            Log.d(TAG, "SoftAP is a hidden network");
        }

        if (!mWifiNative.startSoftAp(mApInterfaceName, localConfig, mSoftApListener)) {
            Log.e(TAG, "Soft AP start failed");
            return ERROR_GENERIC;
        }
        mWifiDiagnostics.startLogging(mApInterfaceName);
        mStartTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
        Log.d(TAG, "Soft AP is started ");

        return SUCCESS;
    }

    /**
     * Teardown soft AP and teardown the interface.
     */
    private void stopSoftAp() {
        mWifiDiagnostics.stopLogging(mApInterfaceName);
        mWifiNative.teardownInterface(mApInterfaceName);
        Log.d(TAG, "Soft AP is stopped");
    }

    private class SoftApStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_FAILURE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_ASSOCIATED_STATIONS_CHANGED = 4;
        public static final int CMD_NO_ASSOCIATED_STATIONS_TIMEOUT = 5;
        public static final int CMD_TIMEOUT_TOGGLE_CHANGED = 6;
        public static final int CMD_INTERFACE_DESTROYED = 7;
        public static final int CMD_INTERFACE_DOWN = 8;
        public static final int CMD_SOFT_AP_CHANNEL_SWITCHED = 9;

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
                            mModeListener.onStartFailure();
                            break;
                        }
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                                WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        int result = startSoftAp();
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
                            mModeListener.onStartFailure();
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
             * Set stations associated with this soft AP
             * @param clients The connected stations
             */
            private void setConnectedClients(List<NativeWifiClient> clients) {
                if (clients == null) {
                    return;
                }

                List<WifiClient> convertedClients = createWifiClients(clients);
                if (mConnectedClients.equals(convertedClients)) {
                    return;
                }

                mConnectedClients = new ArrayList<>(convertedClients);
                Log.d(TAG, "The connected wifi stations have changed with count: "
                        + clients.size());

                if (mSoftApCallback != null) {
                    mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                } else {
                    Log.e(TAG,
                            "SoftApCallback is null. Dropping ConnectedClientsChanged event."
                    );
                }

                mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(
                        mConnectedClients.size(), mApConfig.getTargetMode());

                if (mConnectedClients.size() == 0) {
                    scheduleTimeoutMessage();
                } else {
                    cancelTimeoutMessage();
                }
            }

            private List<WifiClient> createWifiClients(List<NativeWifiClient> nativeClients) {
                List<WifiClient> clients = new ArrayList<>();
                if (nativeClients == null || nativeClients.size() == 0) {
                    return clients;
                }

                for (int i = 0; i < nativeClients.size(); i++) {
                    MacAddress macAddress = MacAddress.fromBytes(nativeClients.get(i).macAddress);
                    WifiClient client = new WifiClient(macAddress);
                    clients.add(client);
                }

                return clients;
            }

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }

                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "SoftAp is ready for use");
                    updateApState(WifiManager.WIFI_AP_STATE_ENABLED,
                            WifiManager.WIFI_AP_STATE_ENABLING, 0);
                    mModeListener.onStarted();
                    mWifiMetrics.incrementSoftApStartResult(true, 0);
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                    }
                } else {
                    // the interface was up, but goes down
                    sendMessage(CMD_INTERFACE_DOWN);
                }
                mWifiMetrics.addSoftApUpChangedEvent(isUp, mApConfig.getTargetMode());
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

                Log.d(TAG, "Resetting connected clients on start");
                mConnectedClients = new ArrayList<>();
                scheduleTimeoutMessage();
            }

            @Override
            public void exit() {
                if (!mIfaceIsDestroyed) {
                    stopSoftAp();
                }

                if (mSettingObserver != null) {
                    mSettingObserver.unregister();
                }
                Log.d(TAG, "Resetting num stations on stop");
                setConnectedClients(new ArrayList<>());
                cancelTimeoutMessage();
                // Need this here since we are exiting |Started| state and won't handle any
                // future CMD_INTERFACE_STATUS_CHANGED events after this point
                mWifiMetrics.addSoftApUpChangedEvent(false, mApConfig.getTargetMode());
                updateApState(WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.WIFI_AP_STATE_DISABLING, 0);

                mSarManager.setSapWifiState(WifiManager.WIFI_AP_STATE_DISABLED);
                mApInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                mRole = ROLE_UNSPECIFIED;
                mStateMachine.quitNow();
                mModeListener.onStopped();
            }

            private void updateUserBandPreferenceViolationMetricsIfNeeded() {
                int band = mApConfig.getWifiConfiguration().apBand;
                boolean bandPreferenceViolated =
                        (band == WifiConfiguration.AP_BAND_2GHZ
                                && ScanResult.is5GHz(mReportedFrequency))
                                || (band == WifiConfiguration.AP_BAND_5GHZ
                                && ScanResult.is24GHz(mReportedFrequency));
                if (bandPreferenceViolated) {
                    Log.e(TAG, "Channel does not satisfy user band preference: "
                            + mReportedFrequency);
                    mWifiMetrics.incrementNumSoftApUserBandPreferenceUnsatisfied();
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_ASSOCIATED_STATIONS_CHANGED:
                        if (!(message.obj instanceof List)) {
                            Log.e(TAG, "Invalid type returned for"
                                    + " CMD_ASSOCIATED_STATIONS_CHANGED");
                            break;
                        }

                        Log.d(TAG, "Setting connected stations on"
                                + " CMD_ASSOCIATED_STATIONS_CHANGED");
                        setConnectedClients((List<NativeWifiClient>) message.obj);
                        break;
                    case CMD_SOFT_AP_CHANNEL_SWITCHED:
                        mReportedFrequency = message.arg1;
                        mReportedBandwidth = message.arg2;
                        Log.d(TAG, "Channel switched. Frequency: " + mReportedFrequency
                                + " Bandwidth: " + mReportedBandwidth);
                        mWifiMetrics.addSoftApChannelSwitchedEvent(mReportedFrequency,
                                mReportedBandwidth, mApConfig.getTargetMode());
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
                        if (mTimeoutEnabled && mConnectedClients.size() == 0) {
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
                        if (mConnectedClients.size() != 0) {
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
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
