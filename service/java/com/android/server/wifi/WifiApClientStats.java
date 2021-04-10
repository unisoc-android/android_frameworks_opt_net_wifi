/*
 * Copyright (C) 2015 Spreadtrum.com
 *
 */

package com.android.server.wifi;

import static android.os.Process.WIFI_UID;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.wifi.WifiManager;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;


import java.nio.charset.StandardCharsets;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provides API to the WifiStateMachine for getting information of softap client
 */
class WifiApClientStats {

    private static final String TAG = "WifiApClientStats";
    private static boolean DBG = true; //Debug.isDebug();

    private final int BUFFER_SIZE = 1024;

    private static final String AP_BLOCKLIST_FILE = Environment.getDataDirectory() +
        "/misc/wifi/softapblocklist.conf";

    private static final String AP_WHITELIST_FILE = Environment.getDataDirectory() +
        "/misc/wifi/hostapd.accept";

    private static final String CLIENT_BLOCKMODE_PREFIX_STR = "#!macaddr_acl=";
    private static final int CLIENT_BLOCKMODE_PREFIX_STR_LEN = CLIENT_BLOCKMODE_PREFIX_STR.length();

    private static final String CLIENT_BLOCKMODE_BLACKLIST = "0";
    private static final String CLIENT_BLOCKMODE_WHITELIST = "1";
    private static final String patternStr = "^[A-Fa-f0-9]{2}(:[A-Fa-f0-9]{2}){5}$";
    //Consistent with ACTION_SOFTAP_CLIENT_CONNECT in frameworks/base/packages/NetworkStack/src/com/android/server/NetworkStackService.java
    private static final String ACTION_SOFTAP_CLIENT_CONNECT = "android.net.conn.SOFTAP_CLIENT_CONNECT";

    private static final String EXTRA_HOST_NAME = "hostName";
    private static final String EXTRA_MAC_ADDR = "macAddr";
    private static final String EXTRA_IP_ADDR = "ipAddr";


    private Object mLocker = new Object();
    private Object mWaiter = new Object();

    private HashMap<String, String> mClientInfoCache = new HashMap<String, String>();
    private HashMap<String, String> mBlockedClientInfoCache = new HashMap<String, String>();
    private HashMap<String, String> mWhiteClientInfoCache = new HashMap<String, String>();

    private boolean mBlockedClientChanged = false;
    private boolean mWhiteClientChanged = false;

    private String mStaName;
    private String mMacAddr;
    private String mIpAddr;
    private String mNewMacList;
    private Context mContext;
    private WifiApClientStatsThread mWifiApClientStatsThread;

    private Object mClientBlockedModeLocker = new Object();
    private String mClientBlockedMode = CLIENT_BLOCKMODE_BLACKLIST;

    WifiApClientStats(Context context) {
        mContext = context;
        mWifiApClientStatsThread = new WifiApClientStatsThread();
        mWifiApClientStatsThread.start();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SOFTAP_CLIENT_CONNECT);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mStaName = intent.getStringExtra(EXTRA_HOST_NAME);
                mMacAddr = intent.getStringExtra(EXTRA_MAC_ADDR);
                mIpAddr = intent.getStringExtra(EXTRA_IP_ADDR);
                if (mMacAddr != null && mIpAddr != null) {
                    saveClientInfo(mMacAddr, mStaName, mIpAddr);
                }
            }
        }, filter);
    }

    public void sendDetailInfoAvailableBroadcast() {
        Intent intent = new Intent(WifiManager.WIFI_AP_CLIENT_DETAILINFO_AVAILABLE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     *  save block list:
     *  MAC IP DEV_NAME
     *  such as: 00:08:22:0e:2d:fc #android-9dfb76a944bd077a
     */
    private void writeBlockList() {
        if (!mBlockedClientChanged) {
            if (DBG) Log.d(TAG, "block list not changed, do not need to save again!");
        } else {
            writeToFile(AP_BLOCKLIST_FILE);
        }
    }

    /**
     *  load white list or block list:
     *  format is the same as hostapd.accept in hostapd.
     *  for block mode, its format is as below:
     *  #!macaddr_acl=1
     *
     *  for mac white list
     *  MAC #DEV_NAME
     *  such as: 00:08:22:0e:2d:fc #android-9dfb76a944bd077a
     */
    private void loadFromFile(String fileName) {
        BufferedReader reader = null;
        boolean isWhiteList = false;
        try {
            reader = new BufferedReader(new FileReader(fileName));

            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                //check #!macaddr_acl=1
                if (line.startsWith(CLIENT_BLOCKMODE_PREFIX_STR)) {
                    isWhiteList = true;
                    synchronized(mClientBlockedModeLocker) {
                        mClientBlockedMode = line.substring(CLIENT_BLOCKMODE_PREFIX_STR_LEN);
                        if (!CLIENT_BLOCKMODE_BLACKLIST.equals(mClientBlockedMode) && !CLIENT_BLOCKMODE_WHITELIST.equals(mClientBlockedMode)) {
                            if (DBG) Log.d(TAG, "Invalid client block mode: " + line);
                            mClientBlockedMode = CLIENT_BLOCKMODE_BLACKLIST;
                        }
                    }
                }

                if (line.startsWith("#")) continue;
                String[] tokens = line.split(" ");
                String mac = null;

                if (tokens.length == 0) continue;
                mac = tokens[0];

                String info = mac;

                //#DEV_NAME may not exist
                if (tokens.length > 1) {
                    info = mac + " " + tokens[1].substring(1);
                }

                if (isWhiteList) {
                    synchronized(mWhiteClientInfoCache) {
                        mWhiteClientInfoCache.put(mac, info);
                    }
                } else {
                    synchronized(mBlockedClientInfoCache) {
                        mBlockedClientInfoCache.put(mac, info);
                    }
                }
            }

        } catch (FileNotFoundException e) {
            Log.e(TAG, "Could not open " + fileName + ", " + e);
        } catch (IOException e) {
            Log.e(TAG, "Could not read " + fileName + ", " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Just ignore the fact that we couldn't close
            }
        }
    }

    /**
     *  write white list:
     *  format is the same as hostapd.accept in hostapd.
     *  for block mode, its format is as below:
     *  #!macaddr_acl=1
     *
     *  for mac white list
     *  MAC #DEV_NAME
     *  such as: 00:08:22:0e:2d:fc #android-9dfb76a944bd077a
     */
    private void writeWhiteList() {
        if (!mWhiteClientChanged) {
            if (DBG) Log.d(TAG, "white list not changed, do not need to save again!");
        } else {
            writeToFile(AP_WHITELIST_FILE);
        }
    }

    private void writeToFile(String fileName) {
        Log.d(TAG, "writeToFile " + fileName);
        File file = new File(fileName);
        if (!file.exists()) {
            Log.d(TAG, "path : " + fileName + ", not exist");
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(fileName));

            if (mWhiteClientChanged) {
                synchronized(mClientBlockedModeLocker) {
                    //write client blockmode first
                    out.write(CLIENT_BLOCKMODE_PREFIX_STR + mClientBlockedMode + "\n");
                }
                synchronized(mWhiteClientInfoCache) {
                    for (String s : mWhiteClientInfoCache.values()) {
                        if (s != null ) {
                            String[] tokens = s.split(" ");
                            if (tokens.length > 1)
                                out.write(tokens[0] + " #" + tokens[1] + "\n");
                            else
                                out.write(s + "\n");
                        }
                    }
                }
                mWhiteClientChanged = false;
            } else if (mBlockedClientChanged) {
                synchronized (mBlockedClientInfoCache) {
                    for (String s : mBlockedClientInfoCache.values()) {
                        if (s != null) {
                            String[] tokens = s.split(" ");
                            if (tokens.length > 1) {
                                out.write(tokens[0] + " #" + tokens[1] + "\n");
                            } else {
                                out.write(s + "\n");
                            }
                        }
                    }
                }
                mBlockedClientChanged = false;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing hotspot configuration" + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {}
            }
        }

        try {
            FileUtils.setPermissions(fileName, 0660, -1, WIFI_UID);
        } catch (Exception e1) {
            Log.e(TAG, "Error change permissions of hotspot configuration" + e1);
        }
    }

    private class WifiApClientStatsThread extends Thread {

        public WifiApClientStatsThread() {
            super("WifiApClientStatsThread");
        }

        public void run() {
            Log.d(TAG,"WifiApClientStatsThread run");
            loadFromFile(AP_BLOCKLIST_FILE);
            loadFromFile(AP_WHITELIST_FILE);
        }
    }


    public void start() {

        synchronized( mWaiter) {
            mWaiter.notifyAll();
        }
        if (DBG) Log.d(TAG, "START NOTIFY");

    }

    public void stop() {
        synchronized( mLocker){
            mNewMacList = null;
            mClientInfoCache.clear();
        }

        writeBlockList();
        writeWhiteList();
        if (DBG) Log.d(TAG, "STOP");

    }
    /**
     * Get the detail info of the connected client
     * in: macList
     *      contain the mac that want to get the detail info. Format: xx:xx:xx:xx:xx:xx xx:xx:xx:xx:xx:xx
     * return:
     *      return the detail info list.
     *      Format of each string info:
     *      MAC IP DEV_NAME
     * such as:
     *      00:08:22:0e:2d:fc 192.168.43.37 android-9dfb76a944bd077a
     */
    public List<String> getStaClientInfoList() {
        List<String> infoList = new ArrayList<String>();
        String[] macs = null;
        if (mNewMacList != null) {
            if (mNewMacList.equals(""))
                return infoList;
            macs = mNewMacList.split(" ");
        }
        try {
            synchronized( mLocker){
                if (macs == null) {
                    for (String s : mClientInfoCache.values()) {
                        if (s != null) infoList.add(s);
                    }
                } else {
                    for (String mac : macs) {
                        String info = mClientInfoCache.get(mac);
                        if (info != null) {
                            infoList.add(info);
                        } else {
                            if (DBG) Log.d(TAG, "getStaClientInfoList for " + mac + " is not got yet");
                            infoList.add(mac);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed getStaClientInfoList : " + e);
        }
        return infoList;
    }

    public void addOrUpdateClientInfoList(String macList) {
        mNewMacList = macList;
    }

    public String saveClientInfo(String macAddr, String staName, String ipAddr) {
        Log.d(TAG,"saveClientInfo macAddr= " + macAddr + ", ipAddr=" + ipAddr);
        StringBuffer buf = new StringBuffer();
        buf.append(macAddr);
        buf.append(" ");
        buf.append(ipAddr);
        buf.append(" ");
        if (staName != null) {
            buf.append(staName);
        } else {
            buf.append("*");
        }
        String clientInfo = buf.toString();
        mClientInfoCache.put(macAddr, clientInfo);
        sendDetailInfoAvailableBroadcast();
        return clientInfo;
    }

    /**
     * Get the detail info of the blocked client
     * in: macList
     *      contain the mac that want to get the detail info. Format: xx:xx:xx:xx:xx:xx xx:xx:xx:xx:xx:xx
     * return:
     *      return the detail info list.
     *      Format of each string info:
     *      MAC IP DEV_NAME
     * such as:
     *      00:08:22:0e:2d:fc 192.168.43.37 android-9dfb76a944bd077a
     */
    public List<String> getBlockedClientInfoList(String macList) {
        List<String> infoList = new ArrayList<String>();
        String[] macs = null;
        String[] macstring = null;

        if (macList != null) {
            if (macList.equals(""))
                return infoList;
            macs = macList.split(" ");
        }

        try {
            if (macs == null) {
                for (String s : mBlockedClientInfoCache.values()) {
                    if (s != null) infoList.add(s);
                }
            } else {
                for (String mac : macs){
                    macstring =mac.split(" ");
                    if (checkMac(macstring[0].trim())) {
                        String info = mBlockedClientInfoCache.get(mac);
                        if (info != null) {
                            infoList.add(info);
                        } else {
                            if (DBG) Log.d(TAG, "getBlockedClientInfoList for " + mac + " is not got yet");
                            infoList.add(mac);
                        }
                    } else {
                        Log.d(TAG, "getBlockedClientInfoList checkMac fail : " + macstring[0]);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed getBlockedClientInfoList : " + e);
        }

        if (DBG) {
            Log.d(TAG, "getBlockedClientInfoList :");
            for (String info : infoList){
                Log.d(TAG, info);
            }
        }

        return infoList;
    }


    /**
     * unblock the client
     * in: mac
     *      contain the mac that want Unblocked. Format: xx:xx:xx:xx:xx:xx
     * return:
     *      return true for success.
     */
    public boolean unBlockClient(String mac) {
        try {
            mBlockedClientInfoCache.remove(mac);
        } catch (Exception e) {
            Log.e(TAG, "Failed to unblock client: " + e);
        }

        mBlockedClientChanged = true;
        writeBlockList();
        sendDetailInfoAvailableBroadcast();
        return true;
    }

    /**
     * block the client
     * in: mac
     *      contain the mac that want Unblocked. Format: xx:xx:xx:xx:xx:xx
     * return:
     *      return true for success.
     */
    public boolean blockClient(String mac) {
        try {
            String info = mClientInfoCache.get(mac);
            String[] tokens = info.split(" ");
            if (tokens.length > 2) {
                mBlockedClientInfoCache.put(mac, mac + " " + tokens[2]);
            } else {
                mBlockedClientInfoCache.put(mac, mac);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to block client: " + e);
        }

        mBlockedClientChanged = true;
        writeBlockList();
        sendDetailInfoAvailableBroadcast();
        return true;
    }


    /**
     * add the client to white list
     * in: mac
     *      contain the mac that want to add to white list. Format: xx:xx:xx:xx:xx:xx
     * in: name
     *      the name of the client, may be null
     * in softapStarted
     *      tell if the softap has started or not
     * return:
     *      return true for success.
     */
    public boolean addClientToWhiteList(String mac, String name, boolean softapStarted) {

        if (mac == null) return false;
        if (!checkMac(mac)) {
            Log.e(TAG, "addClientToWhiteList checkMac false, mac: " + mac);
            return false;
        }

        try {
            String info = mac;
            if (name != null) info = mac + " " + name;

            synchronized(mWhiteClientInfoCache) {
                mWhiteClientInfoCache.put(mac, info);
            }
            mWhiteClientChanged = true;
            writeWhiteList();
        } catch (Exception e) {
            Log.e(TAG, "Failed addClientToWhiteList : " + e);
        }
        sendDetailInfoAvailableBroadcast();

        return true;
    }

    /**
     * remove the client from white list
     * in: mac
     *      contain the mac that want to remove from white list. Format: xx:xx:xx:xx:xx:xx
     * in: name
     *      the name of the client, may be null
     * in softapStarted
     *      tell if the softap has started or not
     * return:
     *      return true for success.
     */
    public boolean delClientFromWhiteList(String mac, String name, boolean softapStarted) {

        try {
            synchronized(mWhiteClientInfoCache) {
                mWhiteClientInfoCache.remove(mac);
            }
            mWhiteClientChanged = true;
            writeWhiteList();
        } catch (Exception e) {
            Log.e(TAG, "Failed delClientFromWhiteList : " + e);
        }
        sendDetailInfoAvailableBroadcast();
        return true;
    }

    /**
     * To enable the white list or not
     * in enabled
     *      true: enable white list
     *      false: disable white list
     */
    public boolean setClientWhiteListEnabled(boolean enabled, boolean softapStarted) {

        try {
            synchronized(mClientBlockedModeLocker) {
                if (enabled)
                    mClientBlockedMode = CLIENT_BLOCKMODE_WHITELIST;
                else
                    mClientBlockedMode = CLIENT_BLOCKMODE_BLACKLIST;
            }
            mWhiteClientChanged = true;
            writeWhiteList();
        } catch (Exception e) {
            Log.e(TAG, "Failed setClientWhiteListEnabled : " + e);
        }
        return true;
    }
    private boolean checkMac(String str) {
        if (str == null) {
            Log.e(TAG, "checkMac error: str == null");
            return false;
        }
        return Pattern.matches(patternStr, str);
    }
    /**
     * Get the detail info of the white client list
     * return:
     *      return the detail info list.
     *      Format of each string info:
     *      MAC DEV_NAME
     * such as:
     *      00:08:22:0e:2d:fc android-9dfb76a944bd077a
     */
    public List<String> getClientWhiteList() {
        List<String> infoList = new ArrayList<String>();
        String macs[] = null;

        try {
            synchronized(mWhiteClientInfoCache) {
                for (String s : mWhiteClientInfoCache.values()) {
                    if(s != null) {
                        macs = s.split(" ");
                        if (checkMac(macs[0].trim())) {
                            infoList.add(s);
                        } else {
                            Log.d(TAG, "getClientWhiteList checkMac error : " + macs[0]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed getClientWhiteList : " + e);
        }

        if (DBG) {
            Log.d(TAG, "getClientWhiteList :");
            for (String info : infoList){
                Log.d(TAG, info);
            }
        }

        return infoList;
    }

    /**
     * Get the MAC info of the white client list
     * return:
     *      return the MAC info list.
     *      Format of each string info:
     *      MAC
     * such as:
     *      00:08:22:0e:2d:fc
     */
    public List<String> getClientMacWhiteList() {
        List<String> infoList = new ArrayList<String>();
        String macs[] = null;

        try {
            synchronized(mWhiteClientInfoCache) {
                for (String s : mWhiteClientInfoCache.values()) {
                    if (s != null) {
                        macs = s.split(" ");
                        if (checkMac(macs[0].trim())) {
                            infoList.add(macs[0].trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed getClientMacWhiteList : " + e);
        }

        return infoList;
    }

    public boolean isWhiteListEnabled() {
        synchronized(mClientBlockedModeLocker) {
            if (CLIENT_BLOCKMODE_WHITELIST.equals(mClientBlockedMode))
                return true;
            else
                return false;
        }
    }

}
