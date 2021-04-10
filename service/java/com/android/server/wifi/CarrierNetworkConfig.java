/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.wifi.EAPConstants;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiFeaturesUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.Process;
import android.telephony.CarrierConfigManager;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class for maintaining/caching carrier Wi-Fi network configurations.
 */
public class CarrierNetworkConfig {
    private static final String TAG = "CarrierNetworkConfig";

    private static final String NETWORK_CONFIG_SEPARATOR = ",";
    private static final int ENCODED_SSID_INDEX = 0;
    private static final int EAP_TYPE_INDEX = 1;
    private static final int CONFIG_ELEMENT_SIZE = 2;

    private static final Uri CONTENT_URI = Uri.parse("content://carrier_information/carrier");

    private boolean mDbg = false;

    private final Map<String, NetworkInfo> mCarrierNetworkMap;
    private boolean mIsCarrierImsiEncryptionInfoAvailable = false;
    private ImsiEncryptionInfo mLastImsiEncryptionInfo = null; // used for dumpsys only


    private static final int PRESET_EAP_CONFIG_ELEMENT_SIZE = 10;
    private static final int PRESET_EAP_SSID_INDEX = 0;
    private static final int PRESET_EAP_WITHSIMLOAD_INDEX = 1;
    private static final int PRESET_EAP_METHOD_INDEX = 2;
    private static final int PRESET_EAP_METHOD_NOSIM_INDEX = 3;
    private static final int PRESET_EAP_IMSI_INDEX = 4;
    private static final int PRESET_EAP_SIMSLOT_INDEX = 5;
    private static final int PRESET_EAP_MAX_CONNECT_TIME_INDEX = 6;
    private static final int PRESET_EAP_AUTOJOIN_MEN_INDEX = 7;
    private static final int PRESET_EAP_MODIFY_INDEX = 8;
    private static final int PRESET_EAP_FORGET_INDEX = 9;

    /**
     * Enable/disable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mDbg = verbose > 0;
    }

    public CarrierNetworkConfig(@NonNull Context context, @NonNull Looper looper,
            @NonNull FrameworkFacade framework) {
        mCarrierNetworkMap = new HashMap<>();
        updateNetworkConfig(context);

        // Monitor for carrier config changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateNetworkConfig(context);
            }
        }, filter);

        framework.registerContentObserver(context, CONTENT_URI, false,
                new ContentObserver(new Handler(looper)) {
                @Override
                public void onChange(boolean selfChange) {
                    updateNetworkConfig(context);
                }
            });
    }

    /**
     * @return true if the given SSID is associated with a carrier network
     */
    public boolean isCarrierNetwork(String ssid) {
        return mCarrierNetworkMap.containsKey(ssid);
    }

    /**
     * @return the EAP type associated with a carrier AP, or -1 if the specified AP
     * is not associated with a carrier network
     */
    public int getNetworkEapType(String ssid) {
        NetworkInfo info = mCarrierNetworkMap.get(ssid);
        return info == null ? -1 : info.mEapType;
    }

    /**
     * @return the name of carrier associated with a carrier AP, or null if the specified AP
     * is not associated with a carrier network.
     */
    public String getCarrierName(String ssid) {
        NetworkInfo info = mCarrierNetworkMap.get(ssid);
        return info == null ? null : info.mCarrierName;
    }

    /**
     * @return True if carrier IMSI encryption info is available, False otherwise.
     */
    public boolean isCarrierEncryptionInfoAvailable() {
        return mIsCarrierImsiEncryptionInfoAvailable;
    }

    /**
     * Verify whether carrier IMSI encryption info is available.
     *
     * @param context Current application context
     *
     * @return True if carrier IMSI encryption info is available, False otherwise.
     */
    private boolean verifyCarrierImsiEncryptionInfoIsAvailable(Context context) {
        // TODO(b/132188983): Inject this using WifiInjector
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) {
            return false;
        }
        try {
            mLastImsiEncryptionInfo = telephonyManager
                    .createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
                    .getCarrierInfoForImsiEncryption(TelephonyManager.KEY_TYPE_WLAN);
            if (mLastImsiEncryptionInfo == null) {
                return false;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to get imsi encryption info: " + e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Utility class for storing carrier network information.
     */
    private static class NetworkInfo {
        final int mEapType;
        final String mCarrierName;

        NetworkInfo(int eapType, String carrierName) {
            mEapType = eapType;
            mCarrierName = carrierName;
        }

        @Override
        public String toString() {
            return new StringBuffer("NetworkInfo: eap=").append(mEapType).append(
                    ", carrier=").append(mCarrierName).toString();
        }
    }

    /**
     * Update the carrier network map based on the current carrier configuration of the active
     * subscriptions.
     *
     * @param context Current application context
     */
    private void updateNetworkConfig(Context context) {
        mIsCarrierImsiEncryptionInfoAvailable = verifyCarrierImsiEncryptionInfoIsAvailable(context);

        // Reset network map.
        mCarrierNetworkMap.clear();

        CarrierConfigManager carrierConfigManager =
                (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager == null) {
            return;
        }

        SubscriptionManager subscriptionManager = (SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subscriptionManager == null) {
            return;
        }
        List<SubscriptionInfo> subInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        if (subInfoList == null) {
            return;
        }

        // Process the carrier config for each active subscription.
        for (SubscriptionInfo subInfo : subInfoList) {
            CharSequence displayNameCs = subInfo.getDisplayName();
            String displayNameStr = displayNameCs == null ? "" : displayNameCs.toString();
            PersistableBundle bundle = carrierConfigManager.getConfigForSubId(
                    subInfo.getSubscriptionId());
            processNetworkConfig(bundle, displayNameStr);
        }
    }

    /**
     * Process the carrier network config, the network config string is formatted as follow:
     *
     * "[Base64 Encoded SSID],[EAP Type]"
     * Where EAP Type is the standard EAP method number, refer to
     * http://www.iana.org/assignments/eap-numbers/eap-numbers.xhtml for more info.

     * @param carrierConfig The bundle containing the carrier configuration
     * @param carrierName The display name of the associated carrier
     */
    private void processNetworkConfig(PersistableBundle carrierConfig, String carrierName) {
        if (carrierConfig == null) {
            return;
        }
        String[] networkConfigs = carrierConfig.getStringArray(
                CarrierConfigManager.KEY_CARRIER_WIFI_STRING_ARRAY);
        if (mDbg) {
            Log.v(TAG, "processNetworkConfig: networkConfigs="
                    + Arrays.deepToString(networkConfigs));
        }
        if (networkConfigs == null) {
            return;
        }

        for (String networkConfig : networkConfigs) {
            String[] configArr = networkConfig.split(NETWORK_CONFIG_SEPARATOR);
            if (configArr.length != CONFIG_ELEMENT_SIZE) {
                Log.e(TAG, "Ignore invalid config: " + networkConfig);
                continue;
            }
            try {

                String ssid = new String(Base64.decode(
                        configArr[ENCODED_SSID_INDEX], Base64.NO_WRAP));
                int eapType = parseEapType(Integer.parseInt(configArr[EAP_TYPE_INDEX]));

                // Verify EAP type, must be a SIM based EAP type.
                if (eapType == -1) {
                    Log.e(TAG, "Invalid EAP type: " + configArr[EAP_TYPE_INDEX]);
                    continue;
                }
                mCarrierNetworkMap.put(ssid, new NetworkInfo(eapType, carrierName));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse EAP type: '" + configArr[EAP_TYPE_INDEX] + "' "
                        + e.getMessage());
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to decode SSID: '" + configArr[ENCODED_SSID_INDEX] + "' "
                        + e.getMessage());
            }
        }
    }

    /**
     * Convert a standard SIM-based EAP type (SIM, AKA, AKA') to the internal EAP type as defined in
     * {@link WifiEnterpriseConfig.Eap}. -1 will be returned if the given EAP type is not
     * SIM-based.
     *
     * @return SIM-based EAP type as defined in {@link WifiEnterpriseConfig.Eap}, or -1 if not
     * SIM-based EAP type
     */
    private static int parseEapType(int eapType) {
        if (eapType == EAPConstants.EAP_SIM) {
            return WifiEnterpriseConfig.Eap.SIM;
        } else if (eapType == EAPConstants.EAP_AKA) {
            return WifiEnterpriseConfig.Eap.AKA;
        } else if (eapType == EAPConstants.EAP_AKA_PRIME) {
            return WifiEnterpriseConfig.Eap.AKA_PRIME;
        }
        return -1;
    }

    /** Dump state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG + ": ");
        pw.println("mCarrierNetworkMap=" + mCarrierNetworkMap);
        pw.println("mIsCarrierImsiEncryptionInfoAvailable="
                + mIsCarrierImsiEncryptionInfoAvailable);
        pw.println("mLastImsiEncryptionInfo=" + mLastImsiEncryptionInfo);
    }

    public void presetWifiEapNetwork(String[] networks, WifiConfigManager wifiConfigManager, TelephonyManager telephonyManager) {
        if (networks == null || wifiConfigManager == null || telephonyManager == null) {
            return;
        }

        try {
            for (String network : networks) {
                String[] configArr = network.split(NETWORK_CONFIG_SEPARATOR);
                if (configArr.length != PRESET_EAP_CONFIG_ELEMENT_SIZE) {
                    Log.e(TAG, "Ignore Preset invalid config: " + network);
                    continue;
                }
                boolean needSimLoad = Boolean.parseBoolean(configArr[PRESET_EAP_WITHSIMLOAD_INDEX]);
                int maxConnectTime = Integer.parseInt(configArr[PRESET_EAP_MAX_CONNECT_TIME_INDEX]);
                String imsi = configArr[PRESET_EAP_IMSI_INDEX];

                /* if needSimload is true, imsi must be valid; if maxConnectTime > 0, imsi must be valid */
                if ((imsi == null || imsi.isEmpty()) && (needSimLoad || maxConnectTime > 0)) {
                    Log.e(TAG, "Ignore Preset, invalid imsi");
                    continue;
                }

                int eapMethod = Integer.parseInt(configArr[PRESET_EAP_METHOD_INDEX]);
                /* Now only Eap network with Eap.SIM/Eap.AKA/Eap.AKA' mehod can be preset */
                if (WifiEnterpriseConfig.Eap.SIM != eapMethod &&
                    WifiEnterpriseConfig.Eap.AKA != eapMethod &&
                    WifiEnterpriseConfig.Eap.AKA_PRIME != eapMethod) {
                    continue;
                }

                String ssid = configArr[PRESET_EAP_SSID_INDEX];
                String configKey = ScanResultUtil.createQuotedSSID(ssid) + KeyMgmt.strings[KeyMgmt.WPA_EAP];
                WifiConfiguration config = wifiConfigManager.getConfiguredNetwork(configKey);
                int simSlot = Integer.parseInt(configArr[PRESET_EAP_SIMSLOT_INDEX]);
                int slotEnabled = checkEapNetworkSlot(imsi, telephonyManager);

                if (config == null) {
                    /* if needSimload is true, phone should insert valid and right operator sim card (at least one) */
                    if (needSimLoad && slotEnabled == -1) {
                        continue;
                    }

                    config = new WifiConfiguration();
                    config.SSID = ScanResultUtil.createQuotedSSID(ssid);
                    config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
                    config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
                    config.enterpriseConfig = new WifiEnterpriseConfig();
                    config.enterpriseConfig.setEapMethod(eapMethod);

                    if (slotEnabled == telephonyManager.getPhoneCount() || slotEnabled == -1) {
                        /* first preset, if insert 2 valid and right sim cards or no one valid and right sim card, should use default sim slot */
                        config.enterpriseConfig.setSimNum(simSlot);
                    } else {
                        /* use current valid and right sim card slot */
                        config.enterpriseConfig.setSimNum(slotEnabled);
                    }
                } else if (slotEnabled >= 0) {
                    config.enterpriseConfig.setEapMethod(eapMethod);
                    /* non-first preset, when only insert one valid and right sim card, use this sim slot */
                    if (slotEnabled != telephonyManager.getPhoneCount()) {
                        config.enterpriseConfig.setSimNum(slotEnabled);
                    }
                } else {
                    eapMethod = Integer.parseInt(configArr[PRESET_EAP_METHOD_NOSIM_INDEX]);
                    config.enterpriseConfig.setEapMethod(eapMethod);
                }

                // For compatiable to android P, if exist or not current config, set operater config again
                config.autoJoinMenu = Boolean.parseBoolean(configArr[PRESET_EAP_AUTOJOIN_MEN_INDEX]);
                config.canModify = Boolean.parseBoolean(configArr[PRESET_EAP_MODIFY_INDEX]);
                config.canForget = Boolean.parseBoolean(configArr[PRESET_EAP_FORGET_INDEX]);
                config.enterpriseConfig.setImsi(imsi);
                config.enterpriseConfig.setMaxConnectTime(maxConnectTime);

                Log.d(TAG, "addOrUpdateNetwork : " + config);
                NetworkUpdateResult result = wifiConfigManager.addOrUpdateNetwork(config, Process.WIFI_UID);
                if (!result.isSuccess()) {
                    Log.e(TAG, "Failed to add preset network: " + config);
                } else if (!wifiConfigManager.enableNetwork(result.getNetworkId(), false, Process.WIFI_UID)) {
                    Log.e(TAG, "Failed to enable preset network: " + config);
                }
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
    }

    /**
     * check current sim card valid and right
     * @param configImsi current operator imsi value
     * @param telephonyManager telephonyManager, to check sim card
     * @return if 2 sim cards are both valid and right, will return 2
     *         if only one sim card is valid and right, will return valid sim slot index
     *         if no sim card is inserted or not match operator sim card, will return -1
     */
    public int checkEapNetworkSlot(String configImsi, TelephonyManager telephonyManager) {
        int count = 0;
        int slotEnabledIndex = -1;
        int phoneCount = telephonyManager.getPhoneCount();
        for (int i = 0; i < phoneCount ; i++) {
            if (isSimSlotEnabledForEapNetwork(i, configImsi, telephonyManager)) {
                count++;
                slotEnabledIndex = i;
            }
        }
        Log.d(TAG, "Check Eap Network slot count : " + count + ", slotEnabledIndex = " + slotEnabledIndex);
        /* if insert 2 sim cards with valid and match operator imsi, return count, otherwise, return valid sim slot index */
        return count == phoneCount ? count : slotEnabledIndex;
    }

    /**
     * check current sim slot valid and right
     * @param slot current sim slot index value
     * @param configImsi operator imsi value
     * @param telephonyManager telephonyManager, to check sim card
     * @return if operaotr imsi is not set, when this sim slot is valid, will return true (it's valid)
     *         if current sim slot imsi is valid and match operator imsi, will return true, otherwise return false
     */
    private boolean isSimSlotEnabledForEapNetwork(int slot, String configImsi, TelephonyManager telephonyManager) {
        /* current sim slot with sim card */
        if (telephonyManager.hasIccCard(slot)) {
            /* operator imsi is not set, no need to check sim card imsi */
            if (configImsi == null || configImsi.isEmpty()) {
                return true;
            }
            int[] subId = SubscriptionManager.getSubId(slot);
            String imsi = telephonyManager.getSubscriberId(subId == null ? -1 : subId[0]);
            if (imsi == null || imsi.isEmpty()) {
                return false;
            }
            String imsiList[] = configImsi.split("-");
            for (String str : imsiList) {
                if (imsi.startsWith(str)) return true;
            }
        }
        return false;
    }
}
