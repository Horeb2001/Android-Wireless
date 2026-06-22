package com.example.sharedrop;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds both the server and client transfer threads plus the shared handshake protocol.
 *
 * TCP CONSENT PROTOCOL (single connection):
 *
 *   1. Client connects → sends "FILENAME|FILESIZE\n"   (UTF-8, newline-terminated)
 *   2. Server reads metadata → shows consent dialog     (blocks on consentLock)
 *   3. Server writes "ACCEPT\n" or "DECLINE\n"
 *   4. Client reads response:
 *        ACCEPT  → streams raw file bytes immediately after
 *        DECLINE → closes socket, notifies UI
 *   5. Server (after ACCEPT) → reads raw bytes, writes to disk
 *
 * Reading is done byte-by-byte for the text lines so that the underlying
 * InputStream is never over-read — BufferedReader would silently consume
 * the first kilobytes of file data that follow the handshake lines.
 */
public class FileTransferService {

    private static final String TAG     = "FileTransfer";
    static final int            PORT    = 8888;
    private static final int    BUF     = 8192;   // 8 KB I/O buffer
    private static final int    TIMEOUT = 30_000;  // 30 s socket read timeout

    // ─── Callback interface ───────────────────────────────────────────────

    public interface TransferListener {
        /** Receiver side: metadata has arrived; show consent dialog now. */
        void onMetadataReceived(String fileName, long fileSize, String senderIp);

        /** Progress update — called on the background thread; dispatch to UI yourself. */
        void onProgress(int percent, long bytesDone, long totalBytes);

        /** Transfer finished. savedPath is non-null for the receiver, null for the sender. */
        void onTransferComplete(String savedPath);

        /** Sender side: receiver declined the transfer. */
        void onTransferDeclinedByReceiver();

        /** Any error during transfer. */
        void onError(String message);
    }

    // ─── Shared utility: read a single UTF-8 line without buffering ───────

    /**
     * Reads bytes from {@code in} until '\n' or EOF, returning the line content
     * (carriage-return stripped). Reads at most 1 KB to prevent abuse.
     * Does NOT use BufferedReader — callers can safely switch to binary I/O on
     * the same stream immediately after this call returns.
     */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 1024) throw new IOException("Protocol line too long (>1 KB)");
        }
        return sb.toString();
    }

    /** Silently close any Closeable — never throws. */
    static void closeQuietly(Closeable c) {
        if (c != null) { try { c.close(); } catch (IOException ignored) {} }
    }

    // =====================================================================
    // FILE SERVER THREAD — listens for incoming transfers
    // =====================================================================

    /**
     * Runs permanently on TCP port {@link #PORT}, accepting one client at a time.
     * Both devices start this thread on launch.  When a client connects it:
     *   • reads the metadata line
     *   • asks the UI for consent (blocks until the user taps Accept/Decline)
     *   • sends the response, then receives file bytes if accepted
     * After each handled connection the loop restarts so the next transfer can arrive.
     */
    public static class FileServerThread extends Thread {

        private final Context          appCtx;
        private final int              port;
        private final TransferListener listener;

        private ServerSocket serverSocket;
        private Socket       clientSocket;

        private final AtomicBoolean running       = new AtomicBoolean(false);
        private final AtomicBoolean userAccepted  = new AtomicBoolean(false);
        private final Object        consentLock   = new Object();
        private volatile boolean    consentGiven  = false;

        public FileServerThread(Context context, int port, TransferListener listener) {
            this.appCtx   = context.getApplicationContext();
            this.port     = port;
            this.listener = listener;
            setName("ShareDrop-Server");
            setDaemon(true);
        }

        public boolean isRunning() { return running.get(); }

        /**
         * Called from the UI thread once the user taps Accept or Decline on the
         * consent dialog.  Unblocks the waiting server thread.
         */
        public void acceptTransfer(boolean accepted) {
            synchronized (consentLock) {
                userAccepted.set(accepted);
                consentGiven = true;
                consentLock.notifyAll();
            }
        }

        /** Cleanly shuts down the server — call before starting a fresh instance. */
        public void stopServer() {
            running.set(false);
            closeQuietly(clientSocket);
            closeQuietly(serverSocket);
            // Wake up any thread blocked on consentLock.wait()
            synchronized (consentLock) { consentLock.notifyAll(); }
        }

        @Override
        public void run() {
            running.set(true);
            Log.d(TAG, "Server listening on port " + port);

            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);          // avoid TIME_WAIT bind errors
                serverSocket.bind(new InetSocketAddress(port));

                // Outer loop: accept the next connection after each transfer finishes
                while (running.get()) {
                    Log.d(TAG, "Waiting for incoming connection…");
                    clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(0);            // no timeout while showing dialog
                    String senderIp = clientSocket.getInetAddress().getHostAddress();
                    Log.d(TAG, "Connection from: " + senderIp);

                    handleTransfer(clientSocket, senderIp);  // blocks until transfer done
                }
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "Server error", e);
                    if (listener != null) listener.onError("Server error: " + e.getMessage());
                }
            } finally {
                running.set(false);
                closeQuietly(serverSocket);
                Log.d(TAG, "Server stopped");
            }
        }

        private void handleTransfer(Socket socket, String senderIp) {
            InputStream  rawIn  = null;
            OutputStream rawOut = null;
            try {
                rawIn  = socket.getInputStream();
                rawOut = socket.getOutputStream();

                // ── Step 1: read metadata "FILENAME|FILESIZE\n" ──────────
                String meta = readLine(rawIn);
                Log.d(TAG, "Metadata received: " + meta);

                if (meta == null || meta.isEmpty() || !meta.contains("|")) {
                    Log.w(TAG, "Invalid metadata, ignoring connection");
                    return;
                }

                String[] parts    = meta.split("\\|", 2);
                String   fileName = parts[0].trim();
                long     fileSize = Long.parseLong(parts[1].trim());

                // ── Step 2: show consent dialog; block until user responds ─
                consentGiven = false;
                if (listener != null) listener.onMetadataReceived(fileName, fileSize, senderIp);

                synchronized (consentLock) {
                    // Wait up to 60 s for the user to respond; auto-decline after that
                    long deadline = System.currentTimeMillis() + 60_000;
                    while (!consentGiven && running.get()) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) break;
                        try { consentLock.wait(Math.min(remaining, 500)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }

                if (!running.get()) return;

                // ── Step 3: write ACCEPT or DECLINE ──────────────────────
                byte[] response = (userAccepted.get() ? "ACCEPT\n" : "DECLINE\n").getBytes("UTF-8");
                rawOut.write(response);
                rawOut.flush();
                Log.d(TAG, "Sent response: " + new String(response).trim());

                if (!userAccepted.get()) return;

                // ── Step 4: receive file bytes ────────────────────────────
                socket.setSoTimeout(TIMEOUT);               // re-arm timeout for actual data

                File saveDir = new File(
                        appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ShareDrop");
                if (!saveDir.exists() && !saveDir.mkdirs()) {
                    if (listener != null) listener.onError("Cannot create save directory");
                    return;
                }

                // Avoid overwriting existing files
                String safeName = uniqueName(saveDir, fileName);
                File   dest     = new File(saveDir, safeName);
                Log.d(TAG, "Saving to: " + dest.getAbsolutePath());

                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf      = new byte[BUF];
                    long   received = 0;
                    int    lastPct  = -1;
                    int    n;

                    while (received < fileSize &&
                           (n = rawIn.read(buf, 0, (int) Math.min(BUF, fileSize - received))) != -1) {
                        fos.write(buf, 0, n);
                        received += n;
                        int pct = (int) (received * 100L / fileSize);
                        if (pct != lastPct) {
                            if (listener != null) listener.onProgress(pct, received, fileSize);
                            lastPct = pct;
                        }
                    }
                    fos.flush();

                    if (received == fileSize) {
                        Log.d(TAG, "Receive complete: " + received + " bytes");
                        if (listener != null) listener.onTransferComplete(dest.getAbsolutePath());
                    } else {
                        if (listener != null)
                            listener.onError("Incomplete transfer: got " + received + "/" + fileSize);
                    }
                }

            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "Transfer error", e);
                    if (listener != null) listener.onError("Transfer error: " + e.getMessage());
                }
            } finally {
                closeQuietly(socket);
            }
        }

        /** Returns a unique filename in dir, appending a timestamp when a clash exists. */
        private String uniqueName(File dir, String name) {
            if (!new File(dir, name).exists()) return name;
            String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            int    dot = name.lastIndexOf('.');
            return (dot > 0)
                    ? name.substring(0, dot) + "_" + ts + name.substring(dot)
                    : name + "_" + ts;
        }
    }

    // =====================================================================
    // FILE CLIENT THREAD — connects to the Group Owner and sends a file
    // =====================================================================

    /**
     * Run on the non-GO device whenever the user taps Send.
     * Connects to the Group Owner's IP on {@link #PORT} and executes
     * the sender side of the consent protocol, then streams the file bytes.
     */
    public static class FileClientThread extends Thread {

        private final Context          appCtx;
        private final String           serverIp;
        private final int              port;
        private final android.net.Uri  fileUri;
        private final String           fileName;
        private final long             fileSize;
        private final TransferListener listener;

        public FileClientThread(Context context, String serverIp, int port,
                                android.net.Uri fileUri, String fileName, long fileSize,
                                TransferListener listener) {
            this.appCtx   = context.getApplicationContext();
            this.serverIp = serverIp;
            this.port     = port;
            this.fileUri  = fileUri;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.listener = listener;
            setName("ShareDrop-Client");
            setDaemon(true);
        }

        @Override
        public void run() {
            Log.d(TAG, "Connecting to " + serverIp + ":" + port);

            // Retry up to 3 times to give the server a moment to start listening
            int attempt = 0;
            Socket socket = null;
            while (attempt < 3) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(serverIp, port), TIMEOUT);
                    break;  // connected
                } catch (IOException e) {
                    attempt++;
                    closeQuietly(socket);
                    socket = null;
                    Log.w(TAG, "Connect attempt " + attempt + " failed: " + e.getMessage());
                    if (attempt < 3) {
                        try { Thread.sleep(1500); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); return;
                        }
                    }
                }
            }

            if (socket == null) {
                listener.onError("Could not connect to receiver after 3 attempts");
                return;
            }

            try {
                socket.setSoTimeout(TIMEOUT);
                OutputStream rawOut = socket.getOutputStream();
                InputStream  rawIn  = socket.getInputStream();

                // ── Step 1: send metadata "FILENAME|FILESIZE\n" ──────────
                String meta = fileName + "|" + fileSize + "\n";
                rawOut.write(meta.getBytes("UTF-8"));
                rawOut.flush();
                Log.d(TAG, "Sent metadata: " + meta.trim());

                // ── Step 2: wait for ACCEPT or DECLINE ───────────────────
                String response = readLine(rawIn).trim();
                Log.d(TAG, "Response from receiver: " + response);

                if ("DECLINE".equalsIgnoreCase(response)) {
                    listener.onTransferDeclinedByReceiver();
                    return;
                }
                if (!"ACCEPT".equalsIgnoreCase(response)) {
                    listener.onError("Unexpected response: " + response);
                    return;
                }

                // ── Step 3: stream file bytes ─────────────────────────────
                Log.d(TAG, "Sending " + fileName + " (" + fileSize + " bytes)");

                try (InputStream fileIn = appCtx.getContentResolver().openInputStream(fileUri)) {
                    if (fileIn == null) { listener.onError("Cannot open file"); return; }

                    byte[] buf     = new byte[BUF];
                    long   sent    = 0;
                    int    lastPct = -1;
                    int    n;

                    long startMs = System.currentTimeMillis();

                    while ((n = fileIn.read(buf)) != -1) {
                        rawOut.write(buf, 0, n);
                        sent += n;
                        int pct = (int) (sent * 100L / fileSize);
                        if (pct != lastPct) {
                            listener.onProgress(pct, sent, fileSize);
                            lastPct = pct;
                        }
                    }
                    rawOut.flush();

                    long elapsed = System.currentTimeMillis() - startMs;
                    Log.d(TAG, "Sent " + sent + " bytes in " + elapsed + " ms");
                    listener.onTransferComplete(null);
                }

            } catch (IOException e) {
                Log.e(TAG, "Client error", e);
                listener.onError("Send error: " + e.getMessage());
            } finally {
                closeQuietly(socket);
            }
        }
    }
}
