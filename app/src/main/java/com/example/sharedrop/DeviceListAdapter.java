package com.example.sharedrop;

import android.graphics.Color;
import android.net.wifi.p2p.WifiP2pDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * RecyclerView adapter that displays the list of discovered Wi-Fi Direct peers.
 * Tapping a row calls {@link OnDeviceClickListener#onDeviceClick(WifiP2pDevice)}.
 */
public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

    public interface OnDeviceClickListener {
        void onDeviceClick(WifiP2pDevice device);
    }

    private final List<WifiP2pDevice>    devices;
    private final OnDeviceClickListener  listener;

    public DeviceListAdapter(List<WifiP2pDevice> devices, OnDeviceClickListener listener) {
        this.devices  = devices;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                               .inflate(R.layout.item_device, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        WifiP2pDevice device = devices.get(position);

        h.tvName.setText(device.deviceName.isEmpty() ? "Unknown Device" : device.deviceName);
        h.tvAddress.setText(device.deviceAddress);
        h.tvStatus.setText(statusLabel(device.status));
        h.tvStatus.setBackgroundColor(statusColor(device.status));

        h.itemView.setOnClickListener(v -> listener.onDeviceClick(device));
    }

    @Override
    public int getItemCount() { return devices.size(); }

    /** Human-readable string for the Wi-Fi Direct device status integer. */
    private String statusLabel(int status) {
        switch (status) {
            case WifiP2pDevice.AVAILABLE:   return "Available";
            case WifiP2pDevice.INVITED:     return "Invited";
            case WifiP2pDevice.CONNECTED:   return "Connected";
            case WifiP2pDevice.FAILED:      return "Failed";
            case WifiP2pDevice.UNAVAILABLE: return "Unavailable";
            default:                        return "Unknown";
        }
    }

    /** Badge background colour reflecting availability. */
    private int statusColor(int status) {
        switch (status) {
            case WifiP2pDevice.AVAILABLE:  return Color.parseColor("#4CAF50"); // green
            case WifiP2pDevice.CONNECTED:  return Color.parseColor("#1565C0"); // blue
            case WifiP2pDevice.INVITED:    return Color.parseColor("#FF9800"); // orange
            default:                       return Color.parseColor("#9E9E9E"); // grey
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvAddress;
        final TextView tvStatus;

        ViewHolder(View v) {
            super(v);
            tvName    = v.findViewById(R.id.tv_device_name);
            tvAddress = v.findViewById(R.id.tv_device_address);
            tvStatus  = v.findViewById(R.id.tv_device_status);
        }
    }
}
