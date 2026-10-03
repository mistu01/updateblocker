package io.github.updateblocker.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.updateblocker.R;
import io.github.updateblocker.data.AppInfo;
import io.github.updateblocker.data.PrefsManager;
import io.github.updateblocker.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements AppListAdapter.OnAppBlockToggleListener {

    private ActivityMainBinding binding;
    private AppListAdapter adapter;
    private PrefsManager prefsManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> filteredApps = new ArrayList<>();

    private String currentSearchQuery = "";
    private int currentFilterType = FILTER_ALL;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_BLOCKED = 1;
    private static final int FILTER_SYSTEM = 2;

    /**
     * Hooked by LSPosed to return true when module is active.
     */
    public static boolean isModuleActive() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = PrefsManager.getInstance(this);

        initViews();
        checkModuleStatus();
        loadApps();
    }

    private void initViews() {
        // Toolbar
        binding.topAppBar.inflateMenu(R.menu.menu_main);
        binding.topAppBar.setOnMenuItemClickListener(this::onToolbarMenuItemClick);

        // RecyclerView
        adapter = new AppListAdapter(this);
        binding.recyclerViewApps.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerViewApps.setAdapter(adapter);

        // SwipeRefresh
        binding.swipeRefresh.setOnRefreshListener(this::loadApps);

        // Search Input
        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s != null ? s.toString().trim().toLowerCase(Locale.getDefault()) : "";
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // Filter Chips
        binding.chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chipBlocked)) {
                currentFilterType = FILTER_BLOCKED;
            } else if (checkedIds.contains(R.id.chipSystem)) {
                currentFilterType = FILTER_SYSTEM;
            } else {
                currentFilterType = FILTER_ALL;
            }
            applyFilters();
        });

        // FAB to add custom package
        binding.fabAddCustom.setOnClickListener(v -> {
            CustomPackageDialog.show(this, packageName -> {
                boolean added = prefsManager.setPackageBlocked(this, packageName, true);
                if (added) {
                    Toast.makeText(this, getString(R.string.toast_blocked, packageName), Toast.LENGTH_SHORT).show();
                    loadApps();
                }
            });
        });
    }

    private void checkModuleStatus() {
        boolean active = isModuleActive();
        if (active) {
            binding.cardModuleStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_active_bg));
            binding.cardModuleStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_active_fg));
            binding.ivStatusIcon.setImageResource(R.drawable.ic_check_circle);
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_active_fg));
            binding.tvStatusTitle.setText(R.string.module_active);
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_active_fg));
            binding.tvStatusDescription.setText(R.string.module_active_desc);
        } else {
            binding.cardModuleStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive_bg));
            binding.cardModuleStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_inactive_fg));
            binding.ivStatusIcon.setImageResource(R.drawable.ic_warning);
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_inactive_fg));
            binding.tvStatusTitle.setText(R.string.module_inactive);
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_inactive_fg));
            binding.tvStatusDescription.setText(R.string.module_inactive_desc);
        }
    }

    private void loadApps() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.layoutEmpty.setVisibility(View.GONE);

        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> installedPackages = pm.getInstalledPackages(0);
            Set<String> blockedPackages = prefsManager.getBlockedPackages();
            Set<String> foundPackages = new HashSet<>();

            List<AppInfo> apps = new ArrayList<>();

            for (PackageInfo pi : installedPackages) {
                // Ignore self
                if (getPackageName().equals(pi.packageName)) continue;

                boolean isSystem = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                String appName = pi.applicationInfo.loadLabel(pm).toString();
                String versionName = pi.versionName != null ? pi.versionName : "";
                long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? pi.getLongVersionCode()
                        : pi.versionCode;

                boolean isBlocked = blockedPackages.contains(pi.packageName);
                AppInfo appInfo = new AppInfo(pi.packageName, appName, versionName, versionCode, isSystem, isBlocked);

                try {
                    Drawable icon = pi.applicationInfo.loadIcon(pm);
                    appInfo.setIcon(icon);
                } catch (Throwable ignored) {
                }

                apps.add(appInfo);
                foundPackages.add(pi.packageName);
            }

            // Also include custom blocked packages not in installed list
            for (String blockedPkg : blockedPackages) {
                if (!foundPackages.contains(blockedPkg)) {
                    AppInfo custom = new AppInfo(blockedPkg, blockedPkg, "", 0, false, true);
                    apps.add(custom);
                }
            }

            // Sort: Blocked apps first, then alphabetical by name
            Collections.sort(apps, (a, b) -> {
                if (a.isBlocked() != b.isBlocked()) {
                    return a.isBlocked() ? -1 : 1;
                }
                return a.getAppName().compareToIgnoreCase(b.getAppName());
            });

            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(apps);
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                updateBlockedChipCount();
                applyFilters();
            });
        });
    }

    private void applyFilters() {
        filteredApps.clear();

        for (AppInfo app : allApps) {
            // Filter by category
            if (currentFilterType == FILTER_BLOCKED && !app.isBlocked()) {
                continue;
            }
            if (currentFilterType == FILTER_SYSTEM && !app.isSystemApp()) {
                continue;
            }

            // Search query matching app name or package name
            if (!currentSearchQuery.isEmpty()) {
                boolean matchesName = app.getAppName().toLowerCase(Locale.getDefault()).contains(currentSearchQuery);
                boolean matchesPkg = app.getPackageName().toLowerCase(Locale.getDefault()).contains(currentSearchQuery);
                if (!matchesName && !matchesPkg) {
                    continue;
                }
            }

            filteredApps.add(app);
        }

        adapter.setApps(new ArrayList<>(filteredApps));

        if (filteredApps.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
        }
    }

    private void updateBlockedChipCount() {
        int count = 0;
        for (AppInfo app : allApps) {
            if (app.isBlocked()) count++;
        }
        if (count > 0) {
            binding.chipBlocked.setText(getString(R.string.filter_blocked) + " (" + count + ")");
        } else {
            binding.chipBlocked.setText(R.string.filter_blocked);
        }
    }

    @Override
    public void onToggle(AppInfo app, boolean isBlocked) {
        prefsManager.setPackageBlocked(this, app.getPackageName(), isBlocked);
        updateBlockedChipCount();

        String message = isBlocked
                ? getString(R.string.toast_blocked, app.getAppName())
                : getString(R.string.toast_unblocked, app.getAppName());

        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_restart_playstore, v -> restartPlayStore())
                .show();

        // If filtering by blocked and user unblocked, re-apply
        if (currentFilterType == FILTER_BLOCKED) {
            applyFilters();
        }
    }

    private boolean onToolbarMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_restart_playstore) {
            restartPlayStore();
            return true;
        } else if (item.getItemId() == R.id.action_about) {
            AboutDialog.show(this);
            return true;
        }
        return false;
    }

    private void restartPlayStore() {
        executor.execute(() -> {
            boolean success = false;
            try {
                Process su = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(su.getOutputStream());
                os.writeBytes("am force-stop com.android.vending\n");
                os.writeBytes("exit\n");
                os.flush();
                int exitCode = su.waitFor();
                success = (exitCode == 0);
            } catch (Throwable ignored) {
            }

            final boolean rootKilled = success;
            runOnUiThread(() -> {
                if (rootKilled) {
                    Toast.makeText(this, R.string.playstore_kill_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.playstore_kill_failed, Toast.LENGTH_SHORT).show();
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:com.android.vending"));
                        startActivity(intent);
                    } catch (Throwable t) {
                        Toast.makeText(this, "Could not open Play Store settings", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkModuleStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
