package com.kakario.apkextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    static class AppItem {
        String label, pkg, version, sourceDir;
        String[] splits;
        boolean system;
        long size;
        Drawable icon;
    }

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AppAdapter adapter;
    private EditText search;
    private CheckBox showSystem;
    private TextView status;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("APK 추출");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(16), dp(14), dp(16), dp(6));
        root.addView(title);

        search = new EditText(this);
        search.setHint("앱 이름 검색");
        search.setSingleLine(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(dp(12), 0, dp(12), 0);
        root.addView(search, sp);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(16), dp(4));
        showSystem = new CheckBox(this);
        showSystem.setText("시스템 앱도 보기");
        bar.addView(showSystem);
        status = new TextView(this);
        status.setGravity(Gravity.END);
        status.setTextColor(Color.GRAY);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> askAction(shown.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) { filter(); }
        });
        showSystem.setOnCheckedChangeListener((c, on) -> filter());

        status.setText("불러오는 중...");
        exec.execute(this::loadApps);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<AppItem> items = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || ai.sourceDir == null) continue;
            AppItem it = new AppItem();
            it.label = String.valueOf(ai.loadLabel(pm));
            it.pkg = pi.packageName;
            it.version = pi.versionName == null ? "" : pi.versionName;
            it.sourceDir = ai.sourceDir;
            it.splits = ai.splitSourceDirs;
            it.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            long sz = new File(ai.sourceDir).length();
            if (it.splits != null) for (String s : it.splits) sz += new File(s).length();
            it.size = sz;
            try { it.icon = ai.loadIcon(pm); } catch (Exception ignored) {}
            items.add(it);
        }
        Collections.sort(items, (a, c) -> a.label.compareToIgnoreCase(c.label));
        ui.post(() -> { all.clear(); all.addAll(items); filter(); });
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (AppItem it : all) {
            if (it.system && !showSystem.isChecked()) continue;
            if (!q.isEmpty() && !it.label.toLowerCase(Locale.ROOT).contains(q)
                    && !it.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(it);
        }
        status.setText(shown.size() + "개");
        adapter.notifyDataSetChanged();
    }

    private static String sizeText(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
        return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
    }

    private void askAction(AppItem it) {
        boolean split = it.splits != null && it.splits.length > 0;
        String msg = it.pkg + "\n버전 " + it.version + " · " + sizeText(it.size)
                + (split ? "\n\n파일이 나뉜 앱이라 .apks(묶음 파일) 하나로 저장해요." : "")
                + "\n\n저장 위치: 다운로드/APK추출";
        new AlertDialog.Builder(this)
                .setTitle(it.label)
                .setMessage(msg)
                .setPositiveButton("저장", (d, w) -> extract(it, false))
                .setNeutralButton("저장 후 공유", (d, w) -> extract(it, true))
                .setNegativeButton("취소", null)
                .show();
    }

    private static String safe(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    @SuppressWarnings("deprecation")
    private void extract(AppItem it, boolean share) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("저장 중...");
        pd.setCancelable(false);
        pd.show();
        exec.execute(() -> {
            Uri uri = null;
            String err = null;
            boolean split = it.splits != null && it.splits.length > 0;
            String name = safe(it.label) + "_" + safe(it.version) + (split ? ".apks" : ".apk");
            ContentResolver cr = getContentResolver();
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, split ? "application/zip"
                        : "application/vnd.android.package-archive");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/APK추출");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("파일을 만들 수 없어요");
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (!split) {
                        copy(it.sourceDir, out);
                    } else {
                        try (ZipOutputStream zip = new ZipOutputStream(out)) {
                            zip.putNextEntry(new ZipEntry("base.apk"));
                            copy(it.sourceDir, zip);
                            zip.closeEntry();
                            for (String s : it.splits) {
                                zip.putNextEntry(new ZipEntry(new File(s).getName()));
                                copy(s, zip);
                                zip.closeEntry();
                            }
                        }
                    }
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } catch (Exception e) {
                err = e.getMessage();
                if (uri != null) try { cr.delete(uri, null, null); } catch (Exception ignored) {}
                uri = null;
            }
            final Uri result = uri;
            final String error = err;
            ui.post(() -> {
                pd.dismiss();
                if (result == null) {
                    Toast.makeText(this, "실패: " + error, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "저장했어요: 다운로드/APK추출/" + name, Toast.LENGTH_LONG).show();
                if (share) {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("application/octet-stream");
                    s.putExtra(Intent.EXTRA_STREAM, result);
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "공유"));
                }
            });
        });
    }

    private static void copy(String path, OutputStream out) throws Exception {
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(path)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    class AppAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int p) { return shown.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int p, View v, ViewGroup parent) {
            LinearLayout row;
            if (v == null) {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(8), dp(14), dp(8));
                ImageView iv = new ImageView(MainActivity.this);
                iv.setId(1);
                row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                TextView t1 = new TextView(MainActivity.this);
                t1.setId(2);
                t1.setTextSize(16);
                t1.setTextColor(Color.BLACK);
                t1.setSingleLine(true);
                TextView t2 = new TextView(MainActivity.this);
                t2.setId(3);
                t2.setTextSize(12);
                t2.setTextColor(Color.GRAY);
                t2.setSingleLine(true);
                col.addView(t1);
                col.addView(t2);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            } else row = (LinearLayout) v;
            AppItem it = shown.get(p);
            ((ImageView) row.findViewById(1)).setImageDrawable(it.icon);
            ((TextView) row.findViewById(2)).setText(it.label);
            boolean split = it.splits != null && it.splits.length > 0;
            ((TextView) row.findViewById(3)).setText(it.version + " · " + sizeText(it.size)
                    + (split ? " · 분할" : ""));
            return row;
        }
    }
}
