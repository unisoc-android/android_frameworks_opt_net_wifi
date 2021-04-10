/*
 * Copyright (C) 2016 Spreadtrum.com
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

package com.sprd.server.wifi;

import android.content.Context;
import android.net.wifi.IWifiRssiLinkSpeedAndFrequencyObserver;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;


public class WifiRssiLinkSpeedAndFrequencyMonitor {

    private static String TAG = "WifiRssiLinkSpeedAndFrequencyMonitor";
    private static final int EVENT_WIFI_RSSI_LINKSPEED_FREQUENCY_CHANGED  = 0;

    private boolean DBG = true;
    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final InternalHandler mHandler;

    private final RemoteCallbackList<IWifiRssiLinkSpeedAndFrequencyObserver> mObservers =
            new RemoteCallbackList<IWifiRssiLinkSpeedAndFrequencyObserver>();


    public WifiRssiLinkSpeedAndFrequencyMonitor(Context context) {
        mContext = context;

        mHandlerThread = new HandlerThread("WifiRssiLinkSpeedAndFrequencyMonitor");
        mHandlerThread.start();
        mHandler = new InternalHandler(mHandlerThread.getLooper());
    }

    public void notifyWifiRssiLinkSpeedAndFrequencyChange(WifiInfo wifiInfo) {
        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_WIFI_RSSI_LINKSPEED_FREQUENCY_CHANGED, new WifiInfo(wifiInfo)));
    }

    public boolean registerObserver(IWifiRssiLinkSpeedAndFrequencyObserver observer) {
        logd("Register Obervers!!!");
        mObservers.register(observer);
        return true;
    }

    public boolean unregisterObserver(IWifiRssiLinkSpeedAndFrequencyObserver observer) {
        mObservers.unregister(observer);
        return true;
    }

    private void notifyRssiLinkSpeedAndFrequencyUpdated(WifiInfo wifiInfo) {
        final int length = mObservers.beginBroadcast();
        if (length == 0) logd("Not any Obervers!!!");

        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).onWifiRssiLinkSpeedAndFrequencyUpdated(wifiInfo);
                } catch (RemoteException e) {
                } catch (RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

   private class InternalHandler extends Handler {
        public InternalHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WIFI_RSSI_LINKSPEED_FREQUENCY_CHANGED:
                    logd("EVENT_WIFI_RSSI_LINKSPEED_FREQUENCY_CHANGED");
                    WifiInfo wifiInfo = (WifiInfo)msg.obj;
                    if (wifiInfo != null) {
                        notifyRssiLinkSpeedAndFrequencyUpdated(wifiInfo);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void logd(String log) {
        if (DBG) Log.d(TAG, log);
    }
}
