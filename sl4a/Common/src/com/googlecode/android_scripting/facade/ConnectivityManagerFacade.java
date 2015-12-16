/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.android_scripting.facade;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.ConnectivityManager.PacketKeepaliveCallback;
import android.net.ConnectivityManager.PacketKeepalive;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkInfo;
import android.provider.Settings.SettingNotFoundException;
import android.os.Bundle;

import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.facade.telephony.TelephonyConstants;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcOptional;
import com.googlecode.android_scripting.rpc.RpcParameter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;

/**
 * Access ConnectivityManager functions.
 */
public class ConnectivityManagerFacade extends RpcReceiver {

    public static int AIRPLANE_MODE_OFF = 0;
    public static int AIRPLANE_MODE_ON = 1;

    class ConnectivityReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.e("ConnectivityReceiver received non-connectivity action!");
                return;
            }

            Bundle b = intent.getExtras();

            if (b == null) {
                Log.e("ConnectivityReceiver failed to receive extras!");
                return;
            }

            int netType =
                    b.getInt(ConnectivityManager.EXTRA_NETWORK_TYPE,
                            ConnectivityManager.TYPE_NONE);

            if (netType == ConnectivityManager.TYPE_NONE) {
                Log.i("ConnectivityReceiver received change to TYPE_NONE.");
                return;
            }

            /*
             * Technically there is a race condition here, but retrieving the NetworkInfo from the
             * bundle is deprecated. See ConnectivityManager.EXTRA_NETWORK_INFO
             */
            for (NetworkInfo info : mManager.getAllNetworkInfo()) {
                if (info.getType() == netType) {
                    mEventFacade.postEvent(TelephonyConstants.EventConnectivityChanged, info);
                }
            }
        }
    }

    class PacketKeepaliveReceiver extends PacketKeepaliveCallback {
        public static final int EVENT_INVALID = -1;
        public static final int EVENT_NONE = 0;
        public static final int EVENT_STARTED = 1 << 0;
        public static final int EVENT_STOPPED = 1 << 1;
        public static final int EVENT_ERROR = 1 << 2;
        public static final int EVENT_ALL = EVENT_STARTED |
                EVENT_STOPPED |
                EVENT_ERROR;
        private int mEvents;
        public String mId;
        public PacketKeepalive mPacketKeepalive;

        public PacketKeepaliveReceiver(int events) {
            super();
            mEvents = events;
            mId = this.toString();
        }

        public void startListeningForEvents(int events) {
            mEvents |= events & EVENT_ALL;
        }

        public void stopListeningForEvents(int events) {
            mEvents &= ~(events & EVENT_ALL);
        }

        @Override
        public void onStarted() {
            Log.d("PacketKeepaliveCallback on start!");
            if ((mEvents & EVENT_STARTED) == EVENT_STARTED) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getPacketKeepaliveReceiverEventString(EVENT_STARTED));
                mEventFacade.postEvent(TelephonyConstants.PacketKeepaliveCallBack, event);
            }
        }

        @Override
        public void onStopped() {
            Log.d("PacketKeepaliveCallback on stop!");
            if ((mEvents & EVENT_STOPPED) == EVENT_STOPPED) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getPacketKeepaliveReceiverEventString(EVENT_STOPPED));
                mEventFacade.postEvent(TelephonyConstants.PacketKeepaliveCallBack, event);
            }
        }

        @Override
        public void onError(int error) {
            Log.d("PacketKeepaliveCallback on error! - code:" + error);
            if ((mEvents & EVENT_ERROR) == EVENT_ERROR) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getPacketKeepaliveReceiverEventString(EVENT_ERROR));
                event.putInt("error", error);
                mEventFacade.postEvent(TelephonyConstants.PacketKeepaliveCallBack, event);
            }
        }
    }

    class NetworkCallback extends ConnectivityManager.NetworkCallback {
        public static final int EVENT_INVALID = -1;
        public static final int EVENT_NONE = 0;
        public static final int EVENT_PRECHECK = 1 << 0;
        public static final int EVENT_AVAILABLE = 1 << 1;
        public static final int EVENT_LOSING = 1 << 2;
        public static final int EVENT_LOST = 1 << 3;
        public static final int EVENT_UNAVAILABLE = 1 << 4;
        public static final int EVENT_CAPABILITIES_CHANGED = 1 << 5;
        public static final int EVENT_SUSPENDED = 1 << 6;
        public static final int EVENT_RESUMED = 1 << 7;
        public static final int EVENT_LINK_PROPERTIES_CHANGED = 1 << 8;
        public static final int EVENT_ALL = EVENT_PRECHECK |
                EVENT_AVAILABLE |
                EVENT_LOSING |
                EVENT_LOST |
                EVENT_UNAVAILABLE |
                EVENT_CAPABILITIES_CHANGED |
                EVENT_SUSPENDED |
                EVENT_RESUMED |
                EVENT_LINK_PROPERTIES_CHANGED;

        private int mEvents;
        public String mId;

        public NetworkCallback(int events) {
            super();
            mEvents = events;
            mId = this.toString();
        }

        public void startListeningForEvents(int events) {
            mEvents |= events & EVENT_ALL;
        }

        public void stopListeningForEvents(int events) {
            mEvents &= ~(events & EVENT_ALL);
        }

        @Override
        public void onPreCheck(Network network) {
            Log.d("NetworkCallback onPreCheck");
            if ((mEvents & EVENT_PRECHECK) == EVENT_PRECHECK) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_PRECHECK));
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onAvailable(Network network) {
            Log.d("NetworkCallback onAvailable");
            if ((mEvents & EVENT_AVAILABLE) == EVENT_AVAILABLE) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_AVAILABLE));
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            Log.d("NetworkCallback onLosing");
            if ((mEvents & EVENT_LOSING) == EVENT_LOSING) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_LOSING));
                event.putInt("maxMsToLive", maxMsToLive);
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onLost(Network network) {
            Log.d("NetworkCallback onLost");
            if ((mEvents & EVENT_LOST) == EVENT_LOST) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_LOST));
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onUnavailable() {
            Log.d("NetworkCallback onUnavailable");
            if ((mEvents & EVENT_UNAVAILABLE) == EVENT_UNAVAILABLE) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_UNAVAILABLE));
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            Log.d("NetworkCallback onCapabilitiesChanged. RSSI:" +
                    networkCapabilities.getSignalStrength());
            if ((mEvents & EVENT_CAPABILITIES_CHANGED) == EVENT_CAPABILITIES_CHANGED) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_CAPABILITIES_CHANGED));
                event.putInt("RSSI", networkCapabilities.getSignalStrength());
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onNetworkSuspended(Network network) {
            Log.d("NetworkCallback onNetworkSuspended");
            if ((mEvents & EVENT_SUSPENDED) == EVENT_SUSPENDED) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_SUSPENDED));
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network,
                LinkProperties linkProperties) {
            Log.d("NetworkCallback onLinkPropertiesChanged");
            if ((mEvents & EVENT_LINK_PROPERTIES_CHANGED) == EVENT_LINK_PROPERTIES_CHANGED) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_LINK_PROPERTIES_CHANGED));
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }

        @Override
        public void onNetworkResumed(Network network) {
            Log.d("NetworkCallback onNetworkResumed");
            if ((mEvents & EVENT_RESUMED) == EVENT_RESUMED) {
                Bundle event = new Bundle();
                event.putString("id", mId);
                event.putString("subEvent",
                        getNetworkCallbackEventString(EVENT_RESUMED));
                mEventFacade.postEvent(TelephonyConstants.NetworkCallBack, event);
            }
        }
    }

    private static int getNetworkCallbackEvent(String event) {
        switch (event) {
            case TelephonyConstants.NetworkCallBackPreCheck:
                return NetworkCallback.EVENT_PRECHECK;
            case TelephonyConstants.NetworkCallBackAvailable:
                return NetworkCallback.EVENT_AVAILABLE;
            case TelephonyConstants.NetworkCallBackLosing:
                return NetworkCallback.EVENT_LOSING;
            case TelephonyConstants.NetworkCallBackLost:
                return NetworkCallback.EVENT_LOST;
            case TelephonyConstants.NetworkCallBackUnavailable:
                return NetworkCallback.EVENT_UNAVAILABLE;
            case TelephonyConstants.NetworkCallBackCapabilitiesChanged:
                return NetworkCallback.EVENT_CAPABILITIES_CHANGED;
            case TelephonyConstants.NetworkCallBackSuspended:
                return NetworkCallback.EVENT_SUSPENDED;
            case TelephonyConstants.NetworkCallBackResumed:
                return NetworkCallback.EVENT_RESUMED;
            case TelephonyConstants.NetworkCallBackLinkPropertiesChanged:
                return NetworkCallback.EVENT_LINK_PROPERTIES_CHANGED;
        }
        return NetworkCallback.EVENT_INVALID;
    }

    private static String getNetworkCallbackEventString(int event) {
        switch (event) {
            case NetworkCallback.EVENT_PRECHECK:
                return TelephonyConstants.NetworkCallBackPreCheck;
            case NetworkCallback.EVENT_AVAILABLE:
                return TelephonyConstants.NetworkCallBackAvailable;
            case NetworkCallback.EVENT_LOSING:
                return TelephonyConstants.NetworkCallBackLosing;
            case NetworkCallback.EVENT_LOST:
                return TelephonyConstants.NetworkCallBackLost;
            case NetworkCallback.EVENT_UNAVAILABLE:
                return TelephonyConstants.NetworkCallBackUnavailable;
            case NetworkCallback.EVENT_CAPABILITIES_CHANGED:
                return TelephonyConstants.NetworkCallBackCapabilitiesChanged;
            case NetworkCallback.EVENT_SUSPENDED:
                return TelephonyConstants.NetworkCallBackSuspended;
            case NetworkCallback.EVENT_RESUMED:
                return TelephonyConstants.NetworkCallBackResumed;
            case NetworkCallback.EVENT_LINK_PROPERTIES_CHANGED:
                return TelephonyConstants.NetworkCallBackLinkPropertiesChanged;
        }
        return TelephonyConstants.NetworkCallBackInvalid;
    }

    private static int getPacketKeepaliveReceiverEvent(String event) {
        switch (event) {
            case TelephonyConstants.PacketKeepaliveCallBackStarted:
                return PacketKeepaliveReceiver.EVENT_STARTED;
            case TelephonyConstants.PacketKeepaliveCallBackStopped:
                return PacketKeepaliveReceiver.EVENT_STOPPED;
            case TelephonyConstants.PacketKeepaliveCallBackError:
                return PacketKeepaliveReceiver.EVENT_ERROR;
        }
        return PacketKeepaliveReceiver.EVENT_INVALID;
    }

    private static String getPacketKeepaliveReceiverEventString(int event) {
        switch (event) {
            case PacketKeepaliveReceiver.EVENT_STARTED:
                return TelephonyConstants.PacketKeepaliveCallBackStarted;
            case PacketKeepaliveReceiver.EVENT_STOPPED:
                return TelephonyConstants.PacketKeepaliveCallBackStopped;
            case PacketKeepaliveReceiver.EVENT_ERROR:
                return TelephonyConstants.PacketKeepaliveCallBackError;
        }
        return TelephonyConstants.PacketKeepaliveCallBackInvalid;
    }

    private final ConnectivityManager mManager;
    private final Service mService;
    private final Context mContext;
    private final ConnectivityReceiver mConnectivityReceiver;
    private final EventFacade mEventFacade;
    private PacketKeepalive mPacketKeepalive;
    private NetworkCallback mNetworkCallback;
    private static HashMap<String, PacketKeepaliveReceiver> mPacketKeepaliveReceiverMap =
            new HashMap<String, PacketKeepaliveReceiver>();
    private static HashMap<String, NetworkCallback> mNetworkCallbackMap =
            new HashMap<String, NetworkCallback>();
    private boolean mTrackingConnectivityStateChange;

    public ConnectivityManagerFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();
        mContext = mService.getBaseContext();
        mManager = (ConnectivityManager) mService.getSystemService(Context.CONNECTIVITY_SERVICE);
        mEventFacade = manager.getReceiver(EventFacade.class);
        mConnectivityReceiver = new ConnectivityReceiver();
        mTrackingConnectivityStateChange = false;
    }

    @Rpc(description = "Listen for connectivity changes")
    public void connectivityStartTrackingConnectivityStateChange() {
        if (!mTrackingConnectivityStateChange) {
            mTrackingConnectivityStateChange = true;
            mContext.registerReceiver(mConnectivityReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    @Rpc(description = "start natt keep alive")
    public String connectivityStartNattKeepalive(Integer intervalSeconds, String srcAddrString,
            Integer srcPort, String dstAddrString) throws UnknownHostException {
        try {
            Network mNetwork = mManager.getActiveNetwork();
            InetAddress srcAddr = InetAddress.getByName(srcAddrString);
            InetAddress dstAddr = InetAddress.getByName(dstAddrString);
            Log.d("startNattKeepalive srcAddr:" + srcAddr.getHostAddress());
            Log.d("startNattKeepalive dstAddr:" + dstAddr.getHostAddress());
            Log.d("startNattKeepalive srcPort:" + srcPort);
            Log.d("startNattKeepalive intervalSeconds:" + intervalSeconds);
            PacketKeepaliveReceiver mPacketKeepaliveReceiver = new PacketKeepaliveReceiver(
                    PacketKeepaliveReceiver.EVENT_ALL);
            mPacketKeepalive = mManager.startNattKeepalive(mNetwork, (int) intervalSeconds,
                    mPacketKeepaliveReceiver, srcAddr, (int) srcPort, dstAddr);
            if (mPacketKeepalive != null) {
                mPacketKeepaliveReceiver.mPacketKeepalive = mPacketKeepalive;
                String key = mPacketKeepaliveReceiver.mId;
                mPacketKeepaliveReceiverMap.put(key, mPacketKeepaliveReceiver);
                return key;
            } else {
                Log.e("startNattKeepalive fail, startNattKeepalive return null");
                return null;
            }
        } catch (UnknownHostException e) {
            Log.e("startNattKeepalive UnknownHostException");
            return null;
        }
    }

    @Rpc(description = "stop natt keep alive")
    public Boolean connectivityStopNattKeepalive(String key) {
        PacketKeepaliveReceiver mPacketKeepaliveReceiver =
                mPacketKeepaliveReceiverMap.get(key);
        if (mPacketKeepaliveReceiver != null) {
            mPacketKeepaliveReceiverMap.remove(key);
            mPacketKeepaliveReceiver.mPacketKeepalive.stop();
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "start listening for NattKeepalive Event")
    public Boolean connectivityNattKeepaliveStartListeningForEvent(String key, String eventString) {
        PacketKeepaliveReceiver mPacketKeepaliveReceiver =
                mPacketKeepaliveReceiverMap.get(key);
        if (mPacketKeepaliveReceiver != null) {
            int event = getPacketKeepaliveReceiverEvent(eventString);
            if (event == PacketKeepaliveReceiver.EVENT_INVALID) {
                return false;
            }
            mPacketKeepaliveReceiver.startListeningForEvents(event);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "stop listening for NattKeepalive Event")
    public Boolean connectivityNattKeepaliveStopListeningForEvent(String key, String eventString) {
        PacketKeepaliveReceiver mPacketKeepaliveReceiver =
                mPacketKeepaliveReceiverMap.get(key);
        if (mPacketKeepaliveReceiver != null) {
            int event = getPacketKeepaliveReceiverEvent(eventString);
            if (event == PacketKeepaliveReceiver.EVENT_INVALID) {
                return false;
            }
            mPacketKeepaliveReceiver.stopListeningForEvents(event);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "start listening for NetworkCallback Event")
    public Boolean connectivityNetworkCallbackStartListeningForEvent(String key, String eventString) {
        NetworkCallback mNetworkCallback = mNetworkCallbackMap.get(key);
        if (mNetworkCallback != null) {
            int event = getNetworkCallbackEvent(eventString);
            if (event == NetworkCallback.EVENT_INVALID) {
                return false;
            }
            mNetworkCallback.startListeningForEvents(event);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "stop listening for NetworkCallback Event")
    public Boolean connectivityNetworkCallbackStopListeningForEvent(String key, String eventString) {
        NetworkCallback mNetworkCallback = mNetworkCallbackMap.get(key);
        if (mNetworkCallback != null) {
            int event = getNetworkCallbackEvent(eventString);
            if (event == NetworkCallback.EVENT_INVALID) {
                return false;
            }
            mNetworkCallback.stopListeningForEvents(event);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "Set Rssi Threshold Monitor")
    public String connectivitySetRssiThresholdMonitor(Integer rssi) {
        Log.d("SL4A:setRssiThresholdMonitor rssi = " + rssi);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.setSignalStrength((int) rssi);
        builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        NetworkRequest networkRequest = builder.build();
        mNetworkCallback = new NetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.registerNetworkCallback(networkRequest, mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "Stop Rssi Threshold Monitor")
    public Boolean connectivityStopRssiThresholdMonitor(String key) {
        Log.d("SL4A:stopRssiThresholdMonitor key = " + key);
        return connectivityUnregisterNetworkCallback(key);
    }

    private NetworkRequest buildNetworkRequestFromJson(JSONObject configJson)
            throws JSONException {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();

        if (configJson.has("TransportType")) {
            Log.d("build TransportType" + configJson.getInt("TransportType"));
            builder.addTransportType(configJson.getInt("TransportType"));
        }
        if (configJson.has("SignalStrength")) {
            Log.d("build SignalStrength" + configJson.getInt("SignalStrength"));
            builder.setSignalStrength(configJson.getInt("SignalStrength"));
        }
        if (configJson.has("Capability")) {
            JSONArray capabilities = configJson.getJSONArray("Capability");
            for (int i = 0; i < capabilities.length(); i++) {
                Log.d("build Capability" + capabilities.getInt(i));
                builder.addCapability(capabilities.getInt(i));
            }
        }
        if (configJson.has("LinkUpstreamBandwidthKbps")) {
            Log.d("build LinkUpstreamBandwidthKbps" + configJson.getInt(
                    "LinkUpstreamBandwidthKbps"));
            builder.setLinkUpstreamBandwidthKbps(configJson.getInt(
                    "LinkUpstreamBandwidthKbps"));
        }
        if (configJson.has("LinkDownstreamBandwidthKbps")) {
            Log.d("build LinkDownstreamBandwidthKbps" + configJson.getInt(
                    "LinkDownstreamBandwidthKbps"));
            builder.setLinkDownstreamBandwidthKbps(configJson.getInt(
                    "LinkDownstreamBandwidthKbps"));
        }
        if (configJson.has("NetworkSpecifier")) {
            Log.d("build NetworkSpecifier" + configJson.getString("NetworkSpecifier"));
            builder.setNetworkSpecifier(configJson.getString(
                    "NetworkSpecifier"));
        }
        NetworkRequest networkRequest = builder.build();
        return networkRequest;
    }

    @Rpc(description = "register a network callback")
    public String connectivityRegisterNetworkCallback(@RpcParameter(name = "configJson")
    JSONObject configJson) throws JSONException {
        NetworkRequest networkRequest = buildNetworkRequestFromJson(configJson);
        mNetworkCallback = new NetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.registerNetworkCallback(networkRequest, mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "unregister a network callback")
    public Boolean connectivityUnregisterNetworkCallback(@RpcParameter(name = "key")
    String key) {
        mNetworkCallback = mNetworkCallbackMap.get(key);
        if (mNetworkCallback != null) {
            mNetworkCallbackMap.remove(key);
            mManager.unregisterNetworkCallback(mNetworkCallback);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "request a network")
    public String connectivityRequestNetwork(@RpcParameter(name = "configJson")
    JSONObject configJson) throws JSONException {
        NetworkRequest networkRequest = buildNetworkRequestFromJson(configJson);
        mNetworkCallback = new NetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.requestNetwork(networkRequest, mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "Stop listening for connectivity changes")
    public void connectivityStopTrackingConnectivityStateChange() {
        if (mTrackingConnectivityStateChange) {
            mTrackingConnectivityStateChange = false;
            mContext.unregisterReceiver(mConnectivityReceiver);
        }
    }

    @Rpc(description = "Get the extra information about the network state provided by lower network layers.")
    public String connectivityNetworkGetActiveConnectionExtraInfo() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return null;
        }
        return current.getExtraInfo();
    }

    @Rpc(description = "Return the subtype name of the current network, null if not connected")
    public String connectivityNetworkGetActiveConnectionSubtypeName() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return null;
        }
        return current.getSubtypeName();
    }

    @Rpc(description = "Return a human-readable name describe the type of the network, e.g. WIFI")
    public String connectivityNetworkGetActiveConnectionTypeName() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return null;
        }
        return current.getTypeName();
    }

    @Rpc(description = "Get connection status information about all network types supported by the device.")
    public NetworkInfo[] connectivityNetworkGetAllInfo() {
        return mManager.getAllNetworkInfo();
    }

    @Rpc(description = "Check whether the active network is connected to the Internet.")
    public Boolean connectivityNetworkIsConnected() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return false;
        }
        return current.isConnected();
    }

    @Rpc(description = "Checks the airplane mode setting.",
            returns = "True if airplane mode is enabled.")
    public Boolean connectivityCheckAirplaneMode() {
        try {
            return android.provider.Settings.System.getInt(mService.getContentResolver(),
                    android.provider.Settings.Global.AIRPLANE_MODE_ON) == AIRPLANE_MODE_ON;
        } catch (SettingNotFoundException e) {
            return false;
        }
    }

    @Rpc(description = "Toggles airplane mode on and off.",
            returns = "True if airplane mode is enabled.")
    public void connectivityToggleAirplaneMode(@RpcParameter(name = "enabled")
    @RpcOptional
    Boolean enabled) {
        if (enabled == null) {
            enabled = !connectivityCheckAirplaneMode();
        }
        mManager.setAirplaneMode(enabled);
    }

    @Override
    public void shutdown() {
        connectivityStopTrackingConnectivityStateChange();
    }
}