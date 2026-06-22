package com.example.sharedrop;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shows the list of files received via ShareDrop.
 * Files are stored in getExternalFilesDir(DIRECTORY_DOWNLOADS)/ShareDrop/.
 * Tapping a file opens it with the system's default viewer via FileProvider.
 */
public class ReceivedFilesActivity extends AppCompatActivity {

    private RecyclerView         rvFiles;
    private TextView             tvEmpty;
    private List<File>           fileList = new ArrayList<>();
    private ReceivedFilesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_received_files);

        // Back-arrow in toolbar
        Toolbar toolbar = findViewById(R.id.toolbar_received);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rvFiles = findViewById(R.id.rv_received_files);
        tvEmpty = findViewById(R.id.tv_empty);

        adapter = new ReceivedFilesAdapter(fileList, this::openFile);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        rvFiles.setAdapter(adapter);

        loadFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFiles(); // refresh on return from another app
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadFiles() {
        File saveDir = new File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ShareDrop");

        fileList.clear();

        if (saveDir.exists() && saveDir.isDirectory()) {
            File[] all = saveDir.listFiles();
            if (all != null && all.length > 0) {
                // Newest file first
                Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : all) {
                    if (f.isFile()) fileList.add(f);
                }
            }
        }

        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
        rvFiles.setVisibility(fileList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openFile(File file) {
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file);

        // Resolve MIME type; fall back to */* so Android can still prompt the user
        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = guessMime(file.getName());

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(intent, "Open with…"));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this file type", Toast.LENGTH_SHORT).show();
        }
    }

    /** Minimal MIME-type guesser based on file extension. */
    private String guessMime(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".mp4"))  return "video/mp4";
        if (n.endsWith(".mp3"))  return "audio/mpeg";
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".apk"))  return "application/vnd.android.package-archive";
        if (n.endsWith(".zip"))  return "application/zip";
        if (n.endsWith(".txt"))  return "text/plain";
        return "*/*";
    }
}
