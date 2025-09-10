package com.translator.kapamtalk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class DictionaryAdapter extends RecyclerView.Adapter<DictionaryAdapter.DictionaryViewHolder> {

    private List<DictionaryEntry> entries;
    private OnItemClickListener listener;

    // Interface for click handling
    public interface OnItemClickListener {
        void onItemClick(DictionaryEntry entry);
    }

    public DictionaryAdapter(List<DictionaryEntry> entries) {
        this.entries = entries;
    }

    // Method to set click listener
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setEntries(List<DictionaryEntry> entries) {
        this.entries = entries;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DictionaryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dictionary_item, parent, false);
        return new DictionaryViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull DictionaryViewHolder holder, int position) {
        DictionaryEntry entry = entries.get(position);
        holder.wordText.setText(entry.getWord());
        holder.meaningText.setText(entry.getMeaning());
        holder.pronunciationText.setText(entry.getPronunciation());

        // Set click listener for the item
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries != null ? entries.size() : 0;
    }

    static class DictionaryViewHolder extends RecyclerView.ViewHolder {
        TextView wordText, meaningText, pronunciationText;

        public DictionaryViewHolder(@NonNull View itemView) {
            super(itemView);
            wordText = itemView.findViewById(R.id.wordText);
            meaningText = itemView.findViewById(R.id.meaningText);
            pronunciationText = itemView.findViewById(R.id.pronunciationText);
        }
    }
}