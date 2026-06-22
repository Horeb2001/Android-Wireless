package com.example.sharedrop;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Main screen — handles:
 *   • Runtime permission requests for Wi-Fi Direct peer discovery
 *   • WifiP2pManager initialisation and channel management
 *   • BroadcastReceiver lifecycle (onResume/onPause)
 *   • Peer discovery and display
 *   • Connecting to a selected peer
 *   • Role assignment after group formation (Group Owner = receiver, Client = sender)
 *   • File picker and outbound file transfer
 *   • Incoming consent dialog for the receiver role
 *   • Progress display for both send and receive
 *
 * ROLE MODEL (MVP):
 *   After Wi-Fi Direct group formation the framework assigns one device as
 *   Group Owner (GO) and the other as Client.  In this app:
 *     GO     → runs FileServerThread; waits to receive files from the client
 *     Client → picks a file and sends it to the GO's IP via FileClientThread
 *   This mirrors the spec exactly.  For a reverse transfer, disconnect and
 *   reconnect — the GO role may be negotiated to the other device.
 */
public class MainActivity extends AppCompatActivity
        implements WifiP2pManager.PeerListListener,
                   WifiP2pManager.ConnectionInfoListener,
                   DeviceListAdapter.OnDeviceClickListener {

    private static final String TAG  = "ShareDrop";
    private static final int    PERM = 100;   // request code for permission dialog

    // ── Wi-Fi Direct ──────────────────────────────────────────────────────
    private WifiP2pManager         manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver      receiver;
    private IntentFilter           intentFilter;

    // ── State ─────────────────────────────────────────────────────────────
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private boolean isConnected    = false;
    private boolean isGroupOwner   = false;
    private String  groupOwnerIp   = null;   // GO's IP, set after group forms

    // ── File selection ────────────────────────────────────────────────────
    private Uri    selectedUri;
    private String selectedName;
    private long   selectedSize;

    // ── Transfer threads ──────────────────────────────────────────────────
    private FileTransferService.FileServerThread serverThread;
    private FileTransferService.FileClientThread clientThread;

    // ── UI ────────────────────────────────────────────────────────────────
    private TextView    tvStatus;
    private TextView    tvConnectionStatus;
    private TextView    tvRole;
    private RecyclerView rvPeers;
    private Button      btnDiscover;
    private Button      btnRescan;
    private Button      btnDisconnect;
    private Button      btnSelectFile;
    private Button      btnSend;
    private TextView    tvSelectedFile;
    private ProgressBar progressBar;
    private TextView    tvProgress;

    private DeviceListAdapter        peerAdapter;
    private ActivityResultLauncher<Intent> filePicker;

    // ═════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupToolbar();
        bindViews();
        setupFilePicker();
        checkWifiDirectHardware();
        initWifiDirect();
        requestPermissions();

        // Start the server thread immediately.  On both devices it listens on
        // port 8888.  Only the Group Owner will actually receive a connection —
        // the client's server thread stays idle while it acts as sender.
        startServerThread();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register BroadcastReceiver while the activity is in the foreground
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        registerReceiver(receiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiver != null) unregisterReceiver(receiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serverThread != null) serverThread.stopServer();
        // Cleanly remove the P2P group when leaving the app
        if (manager != null && channel != null) {
            manager.removeGroup(channel, null);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Initialisation helpers
    // ═════════════════════════════════════════════════════════════════════

    private void setupToolbar() {
        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
    }

    private void bindViews() {
        tvStatus           = findViewById(R.id.tv_status);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);
        tvRole             = findViewById(R.id.tv_role);
        rvPeers            = findViewById(R.id.rv_peers);
        btnDiscover        = findViewById(R.id.btn_discover);
        btnRescan          = findViewById(R.id.btn_rescan);
        btnDisconnect      = findViewById(R.id.btn_disconnect);
        btnSelectFile      = findViewById(R.id.btn_select_file);
        btnSend            = findViewById(R.id.btn_send);
        tvSelectedFile     = findViewById(R.id.tv_selected_file);
        progressBar        = findViewById(R.id.progress_bar);
        tvProgress         = findViewById(R.id.tv_progress);

        peerAdapter = new DeviceListAdapter(peers, this);
        rvPeers.setLayoutManager(new LinearLayoutManager(this));
        rvPeers.setAdapter(peerAdapter);

        btnDiscover.setOnClickListener(v -> discoverPeers());
        btnRescan.setOnClickListener(v -> { peers.clear(); peerAdapter.notifyDataSetChanged(); discoverPeers(); });
        btnDisconnect.setOnClickListener(v -> disconnect());
        btnSelectFile.setOnClickListener(v -> openFilePicker());
        btnSend.setOnClickListener(v -> sendFile());
        findViewById(R.id.btn_view_received).setOnClickListener(v ->
                startActivity(new Intent(this, ReceivedFilesActivity.class)));

        setTransferUiVisible(false);
        btnSelectFile.setEnabled(false);
        btnSend.setEnabled(false);
        btnDisconnect.setVisibility(View.GONE);
    }

    private void setupFilePicker() {
        filePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        selectedUri = result.getData().getData();
                        resolveFileMeta(selectedUri);
                    }
                });
    }

    private void checkWifiDirectHardware() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            toast("This device does not support Wi-Fi Direct");
            btnDiscover.setEnabled(false);
            btnRescan.setEnabled(false);
        }
    }

    private void initWifiDirect() {
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            toast("Wi-Fi Direct unavailable on this device");
            return;
        }
        // Initialize the P2P channel; the last parameter is a channel-lost listener (null = ignore)
        channel = manager.initialize(this, getMainLooper(), null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: NEARBY_WIFI_DEVICES replaces ACCESS_FINE_LOCATION for discovery
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else {
            // Android 6–12: location permission is mandatory for Wi-Fi Direct peer discovery
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        // File read permission for Android < 13 (needed to open the file for sending)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    toast("Required permissions denied — discovery may not work");
                    return;
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Wi-Fi Direct operations
    // ═════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private void discoverPeers() {
        if (manager == null) return;
        tvStatus.setText("Discovering peers…");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                tvStatus.setText("Discovery started — looking for nearby devices…");
            }
            @Override public void onFailure(int reason) {
                String msg = failureReason(reason);
                tvStatus.setText("Discovery failed: " + msg);
                toast("Discovery failed: " + msg + " (ensure Wi-Fi is ON)");
            }
        });
    }

    /** Called from WiFiDirectBroadcastReceiver when WIFI_P2P_PEERS_CHANGED_ACTION fires. */
    @SuppressLint("MissingPermission")
    public void requestPeers() {
        if (manager != null) manager.requestPeers(channel, this);
    }

    /** WifiP2pManager.PeerListListener — delivers the refreshed peer list. */
    @Override
    public void onPeersAvailable(WifiP2pDeviceList peerList) {
        Collection<WifiP2pDevice> fresh = peerList.getDeviceList();
        peers.clear();
        peers.addAll(fresh);
        peerAdapter.notifyDataSetChanged();

        tvStatus.setText(peers.isEmpty()
                ? "No devices found — make sure the other device has Wi-Fi ON"
                : "Found " + peers.size() + " device(s) — tap one to connect");
    }

    /** Called from WiFiDirectBroadcastReceiver when a P2P connection is established. */
    @SuppressLint("MissingPermission")
    public void requestConnectionInfo() {
        if (manager != null) manager.requestConnectionInfo(channel, this);
    }

    /** WifiP2pManager.ConnectionInfoListener — delivers group formation details. */
    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (!info.groupFormed) return;

        isConnected  = true;
        isGroupOwner = info.isGroupOwner;
        groupOwnerIp = info.groupOwnerAddress.getHostAddress();

        Log.d(TAG, "Group formed | isGroupOwner=" + isGroupOwner + " | GO IP=" + groupOwnerIp);

        runOnUiThread(() -> {
            btnDisconnect.setVisibility(View.VISIBLE);

            if (isGroupOwner) {
                // ── RECEIVER role ─────────────────────────────────────
                tvRole.setText("Role: Receiver (Group Owner) — waiting for incoming files…");
                tvConnectionStatus.setText("Connected · my P2P IP: 192.168.49.1");
                btnSelectFile.setEnabled(false);
                btnSend.setEnabled(false);
                // Ensure the server is listening
                if (serverThread == null || !serverThread.isRunning()) startServerThread();

            } else {
                // ── SENDER role ───────────────────────────────────────
                tvRole.setText("Role: Sender (Client) — select a file then tap Send");
                tvConnectionStatus.setText("Connected to receiver at " + groupOwnerIp);
                btnSelectFile.setEnabled(true);
            }
        });
    }

    /** Called from WiFiDirectBroadcastReceiver when the P2P group is torn down. */
    public void onP2pDisconnected() {
        isConnected  = false;
        isGroupOwner = false;
        groupOwnerIp = null;

        runOnUiThread(() -> {
            tvConnectionStatus.setText("Disconnected");
            tvRole.setText("Role: —");
            btnDisconnect.setVisibility(View.GONE);
            btnSelectFile.setEnabled(false);
            btnSend.setEnabled(false);
            setTransferUiVisible(false);
        });
    }

    /** Called from WiFiDirectBroadcastReceiver when WIFI_P2P_STATE_CHANGED_ACTION fires. */
    public void setWifiDirectEnabled(boolean enabled) {
        tvStatus.setText(enabled
                ? getString(R.string.status_wifi_direct_on)
                : getString(R.string.status_wifi_direct_off));
        btnDiscover.setEnabled(enabled);
        btnRescan.setEnabled(enabled);
    }

    /** DeviceListAdapter.OnDeviceClickListener — user tapped a peer row. */
    @Override
    public void onDeviceClick(WifiP2pDevice device) {
        if (isConnected) { toast("Already connected — disconnect first"); return; }
        connectTo(device);
    }

    @SuppressLint("MissingPermission")
    private void connectTo(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        // groupOwnerIntent = -1 lets the framework negotiate the GO role automatically.
        // Range 0–15 (0 = never GO, 15 = always GO); -1 = system decides.

        tvConnectionStatus.setText("Connecting to " + device.deviceName + "…");

        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                tvConnectionStatus.setText("Connection request sent — waiting for acceptance…");
            }
            @Override public void onFailure(int reason) {
                tvConnectionStatus.setText("Connection failed: " + failureReason(reason));
                toast("Connection failed: " + failureReason(reason));
            }
        });
    }

    private void disconnect() {
        if (manager == null || channel == null) return;
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                onP2pDisconnected();
                toast("Disconnected");
                startServerThread(); // restart so next incoming connection is accepted
            }
            @Override public void onFailure(int reason) {
                toast("Disconnect error: " + failureReason(reason));
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // File selection
    // ═════════════════════════════════════════════════════════════════════

    private void openFilePicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        filePicker.launch(Intent.createChooser(i, "Select file to send"));
    }

    private void resolveFileMeta(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeCol = c.getColumnIndex(OpenableColumns.SIZE);
                selectedName = (nameCol >= 0) ? c.getString(nameCol) : "file";
                selectedSize = (sizeCol >= 0) ? c.getLong(sizeCol) : 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveFileMeta", e);
            toast("Cannot read file info");
            return;
        }
        tvSelectedFile.setText(selectedName + " (" + fmtSize(selectedSize) + ")");
        btnSend.setEnabled(true);
    }

    // ═════════════════════════════════════════════════════════════════════
    // File transfer — SENDER side
    // ═════════════════════════════════════════════════════════════════════

    private void sendFile() {
        if (selectedUri == null) { toast("Select a file first"); return; }
        if (!isConnected)        { toast("Not connected to a peer"); return; }
        if (isGroupOwner)        { toast("You are the receiver — the other device must send"); return; }
        if (groupOwnerIp == null){ toast("Group Owner IP unknown — try reconnecting"); return; }

        showProgress();

        clientThread = new FileTransferService.FileClientThread(
                this, groupOwnerIp, FileTransferService.PORT,
                selectedUri, selectedName, selectedSize,
                new FileTransferService.TransferListener() {
                    @Override public void onMetadataReceived(String f, long s, String ip) {}

                    @Override public void onProgress(int pct, long done, long total) {
                        runOnUiThread(() -> updateProgress(pct, done, total, true));
                    }

                    @Override public void onTransferComplete(String path) {
                        runOnUiThread(() -> {
                            hideProgress();
                            tvProgress.setText("✓ File sent successfully!");
                            tvProgress.setVisibility(View.VISIBLE);
                            toast("File sent!");
                            // Reset selection so the user can send another file
                            selectedUri  = null;
                            selectedName = null;
                            selectedSize = 0;
                            tvSelectedFile.setText(getString(R.string.no_file_selected));
                            btnSend.setEnabled(false);
                        });
                    }

                    @Override public void onTransferDeclinedByReceiver() {
                        runOnUiThread(() -> {
                            hideProgress();
                            toast("Transfer declined by receiver");
                        });
                    }

                    @Override public void onError(String msg) {
                        runOnUiThread(() -> {
                            hideProgress();
                            toast("Send error: " + msg);
                            Log.e(TAG, "Client error: " + msg);
                        });
                    }
                });
        clientThread.start();
    }

    // ═════════════════════════════════════════════════════════════════════
    // File transfer — RECEIVER side (server thread + consent dialog)
    // ═════════════════════════════════════════════════════════════════════

    private void startServerThread() {
        if (serverThread != null) serverThread.stopServer();

        serverThread = new FileTransferService.FileServerThread(
                this, FileTransferService.PORT,
                new FileTransferService.TransferListener() {
                    @Override public void onMetadataReceived(String name, long size, String ip) {
                        // Show consent dialog on the UI thread
                        runOnUiThread(() -> showConsentDialog(name, size, ip));
                    }

                    @Override public void onProgress(int pct, long done, long total) {
                        runOnUiThread(() -> updateProgress(pct, done, total, false));
                    }

                    @Override public void onTransferComplete(String savedPath) {
                        runOnUiThread(() -> onReceiveComplete(savedPath));
                    }

                    @Override public void onTransferDeclinedByReceiver() { /* n/a server side */ }

                    @Override public void onError(String msg) {
                        runOnUiThread(() -> {
                            hideProgress();
                            toast("Receive error: " + msg);
                            Log.e(TAG, "Server error: " + msg);
                        });
                    }
                });
        serverThread.start();
    }

    private void showConsentDialog(String fileName, long fileSize, String senderIp) {
        if (isFinishing() || isDestroyed()) return;

        String msg = "From device:  " + senderIp
                   + "\n\nFile:  "    + fileName
                   + "\nSize:  "      + fmtSize(fileSize);

        new AlertDialog.Builder(this)
                .setTitle("📥 Incoming Transfer")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Accept", (d, w) -> {
                    showProgress();
                    serverThread.acceptTransfer(true);
                })
                .setNegativeButton("Decline", (d, w) -> {
                    serverThread.acceptTransfer(false);
                    toast("Transfer declined");
                })
                .show();
    }

    private void onReceiveComplete(String savedPath) {
        hideProgress();
        if (isFinishing() || isDestroyed()) return;

        new AlertDialog.Builder(this)
                .setTitle("✅ File Received!")
                .setMessage("Saved to:\n" + savedPath)
                .setPositiveButton("View Files", (d, w) ->
                        startActivity(new Intent(this, ReceivedFilesActivity.class)))
                .setNegativeButton("OK", null)
                .show();

        // Restart server so the next incoming transfer is accepted immediately
        startServerThread();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Progress UI helpers
    // ═════════════════════════════════════════════════════════════════════

    private void showProgress() {
        setTransferUiVisible(true);
        progressBar.setProgress(0);
        tvProgress.setText("0 %");
    }

    private void hideProgress() {
        // Keep the status text visible but hide the bar
        progressBar.setVisibility(View.GONE);
    }

    private void setTransferUiVisible(boolean show) {
        int vis = show ? View.VISIBLE : View.GONE;
        progressBar.setVisibility(vis);
        tvProgress.setVisibility(vis);
    }

    private void updateProgress(int pct, long done, long total, boolean sending) {
        progressBar.setProgress(pct);
        String action = sending ? "Sending" : "Receiving";
        // Calculate transfer speed (crude, updated each percent)
        tvProgress.setText(String.format(Locale.US,
                "%s: %s / %s  (%d %%)", action, fmtSize(done), fmtSize(total), pct));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Utility
    // ═════════════════════════════════════════════════════════════════════

    private String failureReason(int code) {
        switch (code) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "P2P not supported";
            case WifiP2pManager.BUSY:            return "System busy — try again";
            case WifiP2pManager.ERROR:           return "Internal error";
            default:                             return "Unknown (" + code + ")";
        }
    }

    private String fmtSize(long bytes) {
        if (bytes < 1024)                 return bytes + " B";
        if (bytes < 1024 * 1024)         return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
