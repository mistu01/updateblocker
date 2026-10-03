package io.github.updateblocker.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Set;

import io.github.updateblocker.data.PrefsManager;

public class BlockedAppsProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.updateblocker.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_IS_BLOCKED = "isBlocked";
    public static final String METHOD_GET_BLOCKED_LIST = "getBlockedList";
    public static final String METHOD_SET_BLOCKED = "setBlocked";

    public static final String KEY_RESULT = "result";
    public static final String KEY_PACKAGE_LIST = "package_list";
    public static final String KEY_BLOCKED_STATE = "blocked_state";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (getContext() == null) return null;
        PrefsManager prefs = PrefsManager.getInstance(getContext());
        Bundle result = new Bundle();

        switch (method) {
            case METHOD_IS_BLOCKED: {
                if (arg != null) {
                    boolean blocked = prefs.isPackageBlocked(arg);
                    result.putBoolean(KEY_RESULT, blocked);
                } else {
                    result.putBoolean(KEY_RESULT, false);
                }
                return result;
            }
            case METHOD_GET_BLOCKED_LIST: {
                Set<String> set = prefs.getBlockedPackages();
                result.putStringArrayList(KEY_PACKAGE_LIST, new ArrayList<>(set));
                return result;
            }
            case METHOD_SET_BLOCKED: {
                if (arg != null && extras != null) {
                    boolean blocked = extras.getBoolean(KEY_BLOCKED_STATE, false);
                    prefs.setPackageBlocked(getContext(), arg, blocked);
                    result.putBoolean(KEY_RESULT, true);
                }
                return result;
            }
            default:
                return null;
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
