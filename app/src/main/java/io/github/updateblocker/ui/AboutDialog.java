package io.github.updateblocker.ui;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import io.github.updateblocker.BuildConfig;
import io.github.updateblocker.R;

public class AboutDialog {

    public static void show(Context context) {
        String title = context.getString(R.string.about_title) + " v" + BuildConfig.VERSION_NAME;
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(R.string.about_content)
                .setPositiveButton(R.string.btn_close, (dialog, which) -> dialog.dismiss())
                .show();
    }
}
