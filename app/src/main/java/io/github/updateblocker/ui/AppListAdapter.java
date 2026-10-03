package io.github.updateblocker.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.github.updateblocker.R;
import io.github.updateblocker.data.AppInfo;
import io.github.updateblocker.databinding.ItemAppBinding;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {

    public interface OnAppBlockToggleListener {
        void onToggle(AppInfo app, boolean isBlocked);
    }

    private final List<AppInfo> appList = new ArrayList<>();
    private final OnAppBlockToggleListener listener;

    public AppListAdapter(OnAppBlockToggleListener listener) {
        this.listener = listener;
    }

    public void setApps(List<AppInfo> newApps) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return appList.size();
            }

            @Override
            public int getNewListSize() {
                return newApps.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return appList.get(oldItemPosition).getPackageName()
                        .equals(newApps.get(newItemPosition).getPackageName());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                AppInfo oldItem = appList.get(oldItemPosition);
                AppInfo newItem = newApps.get(newItemPosition);
                return oldItem.isBlocked() == newItem.isBlocked()
                        && oldItem.getVersionCode() == newItem.getVersionCode()
                        && oldItem.getAppName().equals(newItem.getAppName());
            }
        });

        appList.clear();
        appList.addAll(newApps);
        diffResult.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAppBinding binding = ItemAppBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new AppViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.bind(appList.get(position));
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    class AppViewHolder extends RecyclerView.ViewHolder {
        private final ItemAppBinding binding;

        public AppViewHolder(ItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(AppInfo app) {
            binding.tvAppName.setText(app.getAppName());
            binding.tvPackageName.setText(app.getPackageName());

            if (app.getVersionName() != null && !app.getVersionName().isEmpty()) {
                binding.tvVersionInfo.setText(String.format("v%s (%d)", app.getVersionName(), app.getVersionCode()));
                binding.tvVersionInfo.setVisibility(android.view.View.VISIBLE);
            } else {
                binding.tvVersionInfo.setVisibility(android.view.View.GONE);
            }

            if (app.getIcon() != null) {
                binding.ivAppIcon.setImageDrawable(app.getIcon());
            } else {
                binding.ivAppIcon.setImageResource(R.drawable.ic_block);
            }

            // Avoid triggering listener when setting checked state
            binding.switchBlock.setOnCheckedChangeListener(null);
            binding.switchBlock.setChecked(app.isBlocked());

            binding.switchBlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
                app.setBlocked(isChecked);
                if (listener != null) {
                    listener.onToggle(app, isChecked);
                }
            });

            binding.getRoot().setOnClickListener(v -> {
                boolean newState = !binding.switchBlock.isChecked();
                binding.switchBlock.setChecked(newState);
            });
        }
    }
}
