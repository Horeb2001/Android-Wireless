package com.example.sharedrop;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the list of received files in ReceivedFilesActivity.
 */
public class ReceivedFilesAdapter extends RecyclerView.Adapter<ReceivedFilesAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(File file);
    }

    private final List<File>         files;
    private final OnFileClickListener listener;

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());

    public ReceivedFilesAdapter(List<File> files, OnFileClickListener listener) {
        this.files    = files;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                               .inflate(R.layout.item_received_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        File file = files.get(position);

        h.tvName.setText(file.getName());
        h.tvSize.setText(formatSize(file.length()));
        h.tvDate.setText(DATE_FMT.format(new Date(file.lastModified())));
        h.tvIcon.setText(fileEmoji(file.getName()));

        h.itemView.setOnClickListener(v -> listener.onFileClick(file));
    }

    @Override
    public int getItemCount() { return files.size(); }

    /** Pick an emoji that roughly matches the file extension. */
    private String fileEmoji(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|heic)")) return "🖼";
        if (lower.matches(".*\\.(mp4|mkv|avi|mov|3gp|webm)"))       return "🎬";
        if (lower.matches(".*\\.(mp3|aac|ogg|flac|wav|m4a)"))       return "🎵";
        if (lower.matches(".*\\.(pdf)"))                              return "📕";
        if (lower.matches(".*\\.(doc|docx|odt)"))                    return "📝";
        if (lower.matches(".*\\.(xls|xlsx|csv)"))                    return "📊";
        if (lower.matches(".*\\.(ppt|pptx)"))                        return "📊";
        if (lower.matches(".*\\.(zip|rar|tar|gz|7z)"))               return "📦";
        if (lower.matches(".*\\.(apk)"))                              return "🤖";
        return "📄";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)            return bytes + " B";
        if (bytes < 1024 * 1024)     return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
                                     return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvIcon;
        final TextView tvName;
        final TextView tvSize;
        final TextView tvDate;

        ViewHolder(View v) {
            super(v);
            tvIcon = v.findViewById(R.id.tv_file_type_icon);
            tvName = v.findViewById(R.id.tv_file_name);
            tvSize = v.findViewById(R.id.tv_file_size);
            tvDate = v.findViewById(R.id.tv_file_date);
        }
    }
}
