package com.example.sharedrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

/**
 * Listens for the four Wi-Fi Direct system broadcasts and delegates each to
 * MainActivity.  Registered/unregistered in onResume/onPause so it is only
 * active while the UI is visible.
 *
 * Broadcast → handler mapping:
 *   WIFI_P2P_STATE_CHANGED_ACTION       → setWifiDirectEnabled()
 *   WIFI_P2P_PEERS_CHANGED_ACTION       → requestPeers()          (triggers onPeersAvailable)
 *   WIFI_P2P_CONNECTION_CHANGED_ACTION  → requestConnectionInfo() (triggers onConnectionInfoAvailable)
 *   WIFI_P2P_THIS_DEVICE_CHANGED_ACTION → (logged only for now)
 */
public class WiFiDirectBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "WiFiDirectReceiver";

    private final WifiP2pManager         manager;
    private final WifiP2pManager.Channel channel;
    private final MainActivity           activity;

    public WiFiDirectBroadcastReceiver(WifiP2pManager manager,
                                       WifiP2pManager.Channel channel,
                                       MainActivity activity) {
        this.manager  = manager;
        this.channel  = channel;
        this.activity = activity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {

            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                // Tells us whether the Wi-Fi Direct radio is enabled
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                boolean enabled = (state == WifiP2pManager.WIFI_P2P_ENABLED);
                Log.d(TAG, "P2P state changed → " + (enabled ? "ENABLED" : "DISABLED"));
                activity.setWifiDirectEnabled(enabled);
                break;
            }

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION: {
                // The peer list changed — refresh it
                Log.d(TAG, "Peers changed — requesting fresh peer list");
                activity.requestPeers();
                break;
            }

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                // A P2P connection was formed or torn down
                NetworkInfo networkInfo =
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null && networkInfo.isConnected()) {
                    Log.d(TAG, "P2P connected — requesting connection info");
                    // Fetch group info (GO IP, isGroupOwner) from the framework
                    activity.requestConnectionInfo();
                } else {
                    Log.d(TAG, "P2P disconnected");
                    activity.onP2pDisconnected();
                }
                break;
            }

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: {
                // This device's own P2P details changed (e.g. display name)
                Log.d(TAG, "Own device details changed");
                break;
            }
        }
    }
}
