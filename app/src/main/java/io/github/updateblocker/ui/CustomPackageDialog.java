package io.github.updateblocker.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import io.github.updateblocker.R;
import io.github.updateblocker.databinding.DialogCustomPackageBinding;

public class CustomPackageDialog {

    public interface OnPackageAddedListener {
        void onPackageAdded(String packageName);
    }

    public static void show(Context context, OnPackageAddedListener listener) {
        DialogCustomPackageBinding binding = DialogCustomPackageBinding.inflate(LayoutInflater.from(context));

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_add_title)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.btn_add, null)
                .setNegativeButton(R.string.btn_cancel, (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = binding.etPackageName.getText() != null
                        ? binding.etPackageName.getText().toString().trim()
                        : "";

                if (isValidPackageName(input)) {
                    if (listener != null) {
                        listener.onPackageAdded(input);
                    }
                    dialog.dismiss();
                } else {
                    binding.tilPackage.setError(context.getString(R.string.toast_invalid_pkg));
                }
            });
        });

        dialog.show();
    }

    private static boolean isValidPackageName(String name) {
        if (name == null || name.length() < 3 || !name.contains(".")) {
            return false;
        }
        return name.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$");
    }
}
