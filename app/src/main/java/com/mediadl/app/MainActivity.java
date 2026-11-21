package com.mediadl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * Media Downloader — paste or share a link from YouTube, Instagram, Facebook etc.,
 * choose Video or Audio, quality and format, and save it to Download/MediaDownloader.
 * Engine: yt-dlp (open source) with ffmpeg, bundled inside the app.
 */
public class MainActivity extends Activity {

    private static final String PROCESS_ID = "download";
    private static final String SAVE_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/MediaDownloader";
    private static final long AUTO_UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000; // 3 days

    private static final String[] VIDEO_QUALITY = {"Best available", "1080p", "720p", "480p", "360p"};
    private static final int[] VIDEO_HEIGHT = {0, 1080, 720, 480, 360};
    private static final String[] VIDEO_FORMAT = {"MP4 (plays everywhere)", "MKV (keeps original quality)"};
    private static final String[] AUDIO_FORMAT = {"MP3 (plays everywhere)", "M4A (better quality, smaller)", "OPUS (smallest)"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText urlBox;
    private TextView infoText, statusText, engineText;
    private RadioButton videoRadio, audioRadio;
    private Spinner qualitySpinner, formatSpinner;
    private TextView qualityLabel;
    private Button fetchBtn, downloadBtn, cancelBtn, updateBtn;
    private ProgressBar progress;

    private volatile boolean engineReady = false;
    private volatile boolean busy = false;
    private volatile boolean cancelled = false;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleShare(getIntent());
        initEngine();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Media Downloader");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = small("YouTube · Instagram · Facebook · X and 1000+ sites");
        root.addView(sub);

        root.addView(label("1. Link"));
        urlBox = new EditText(this);
        urlBox.setHint("Paste a link, or use Share → Media Downloader");
        urlBox.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setSingleLine(false);
        urlBox.setMaxLines(3);
        root.addView(urlBox, matchWrap());

        LinearLayout row1 = row();
        Button pasteBtn = button("Paste");
        pasteBtn.setOnClickListener(v -> paste());
        fetchBtn = button("Check link");
        fetchBtn.setOnClickListener(v -> fetchInfo());
        row1.addView(pasteBtn);
        row1.addView(fetchBtn);
        root.addView(row1);

        infoText = small("");
        infoText.setVisibility(View.GONE);
        root.addView(infoText);

        root.addView(label("2. What to save"));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        videoRadio = new RadioButton(this);
        videoRadio.setText("Video");
        videoRadio.setId(View.generateViewId());
        audioRadio = new RadioButton(this);
        audioRadio.setText("Audio only");
        audioRadio.setId(View.generateViewId());
        group.addView(videoRadio);
        group.addView(audioRadio);
        root.addView(group);

        qualityLabel = label("Quality");
        root.addView(qualityLabel);
        qualitySpinner = new Spinner(this);
        root.addView(qualitySpinner, matchWrap());

        root.addView(label("Format"));
        formatSpinner = new Spinner(this);
        root.addView(formatSpinner, matchWrap());

        group.setOnCheckedChangeListener((g, id) -> refreshChoices());

        root.addView(label("3. Save"));
        downloadBtn = button("Download");
        downloadBtn.setOnClickListener(v -> startDownload());
        downloadBtn.setEnabled(false);
        root.addView(downloadBtn, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusText.setPadding(0, dp(8), 0, dp(8));
        statusText.setTextIsSelectable(true);
        root.addView(statusText);

        cancelBtn = button("Cancel download");
        cancelBtn.setVisibility(View.GONE);
        cancelBtn.setOnClickListener(v -> cancelDownload());
        root.addView(cancelBtn, matchWrap());

        TextView saved = small("Files are saved in your phone's Download/MediaDownloader folder. "
                + "Videos also appear in Gallery, audio in your music app.");
        saved.setPadding(0, dp(14), 0, 0);
        root.addView(saved);

        LinearLayout row2 = row();
        updateBtn = button("Update engine");
        updateBtn.setOnClickListener(v -> updateEngine(true));
        Button helpBtn = button("Help");
        helpBtn.setOnClickListener(v -> showHelp());
        row2.addView(updateBtn);
        row2.addView(helpBtn);
        row2.setPadding(0, dp(18), 0, 0);
        root.addView(row2);

        engineText = small("");
        root.addView(engineText);

        setContentView(scroll);

        SharedPreferences p = prefs();
        if (p.getBoolean("audio", false)) audioRadio.setChecked(true); else videoRadio.setChecked(true);
        refreshChoices();
        statusText.setText("Preparing… (the first launch takes about 30 seconds)");
    }

    private void refreshChoices() {
        boolean video = videoRadio.isChecked();
        qualityLabel.setVisibility(video ? View.VISIBLE : View.GONE);
        qualitySpinner.setVisibility(video ? View.VISIBLE : View.GONE);
        qualitySpinner.setAdapter(adapter(VIDEO_QUALITY));
        formatSpinner.setAdapter(adapter(video ? VIDEO_FORMAT : AUDIO_FORMAT));
        SharedPreferences p = prefs();
        if (video) {
            qualitySpinner.setSelection(Math.min(p.getInt("vq", 2), VIDEO_QUALITY.length - 1));
            formatSpinner.setSelection(Math.min(p.getInt("vf", 0), VIDEO_FORMAT.length - 1));
        } else {
            formatSpinner.setSelection(Math.min(p.getInt("af", 0), AUDIO_FORMAT.length - 1));
        }
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private TextView small(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAlpha(0.75f);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    private void status(String s) {
        ui.post(() -> statusText.setText(s));
    }

    // ---------------------------------------------------------------- links

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String url = extractUrl(text);
        if (url != null) {
            urlBox.setText(url);
            if (engineReady) fetchInfo();
        }
    }

    private static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        return m.find() ? m.group() : null;
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { status("Clipboard is empty — copy a link first."); return; }
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        String url = extractUrl(t == null ? null : t.toString());
        if (url == null) { status("No link found in the clipboard."); return; }
        urlBox.setText(url);
        fetchInfo();
    }

    private String currentUrl() {
        return extractUrl(urlBox.getText().toString().trim());
    }

    // ---------------------------------------------------------------- engine

    private void initEngine() {
        new Thread(() -> {
            try {
                YoutubeDL.getInstance().init(getApplicationContext());
                FFmpeg.getInstance().init(getApplicationContext());
                engineReady = true;
                ui.post(() -> {
                    downloadBtn.setEnabled(true);
                    statusText.setText("Ready.");
                    showEngineVersion();
                    if (currentUrl() != null) fetchInfo();
                });
                long last = prefs().getLong("lastUpdate", 0);
                if (System.currentTimeMillis() - last > AUTO_UPDATE_EVERY_MS) updateEngine(false);
            } catch (Exception e) {
                status("Could not start the download engine: " + e.getMessage());
            }
        }).start();
    }

    private void showEngineVersion() {
        String v = YoutubeDL.getInstance().versionName(getApplicationContext());
        if (v == null) v = YoutubeDL.getInstance().version(getApplicationContext());
        engineText.setText("Engine: yt-dlp " + (v == null ? "(built-in)" : v));
    }

    private void updateEngine(boolean manual) {
        if (!engineReady) { if (manual) status("Please wait, still preparing…"); return; }
        if (busy) { if (manual) status("Wait for the current download to finish."); return; }
        if (manual) { updateBtn.setEnabled(false); status("Updating engine…"); }
        new Thread(() -> {
            try {
                YoutubeDL.UpdateStatus s = YoutubeDL.getInstance()
                        .updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                prefs().edit().putLong("lastUpdate", System.currentTimeMillis()).apply();
                if (manual) status(s == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE
                        ? "Engine is already up to date." : "Engine updated.");
            } catch (Exception e) {
                if (manual) status("Update failed (check internet): " + e.getMessage());
            }
            ui.post(() -> { updateBtn.setEnabled(true); showEngineVersion(); });
        }).start();
    }

    // ---------------------------------------------------------------- info

    private void fetchInfo() {
        String url = currentUrl();
        if (url == null) { status("Paste a link first."); return; }
        if (!engineReady) { status("Please wait, still preparing…"); return; }
        if (busy) return;
        fetchBtn.setEnabled(false);
        infoText.setVisibility(View.VISIBLE);
        infoText.setText("Checking link…");
        new Thread(() -> {
            try {
                YoutubeDLRequest req = new YoutubeDLRequest(url);
                req.addOption("--no-playlist");
                VideoInfo info = YoutubeDL.getInstance().getInfo(req);
                StringBuilder sb = new StringBuilder();
                sb.append("✔ ").append(info.getTitle() == null ? "(no title)" : info.getTitle());
                if (info.getUploader() != null) sb.append("\nBy ").append(info.getUploader());
                int d = info.getDuration();
                if (d > 0) sb.append(String.format(Locale.US, "\nLength %d:%02d", d / 60, d % 60));
                ui.post(() -> infoText.setText(sb.toString()));
            } catch (Exception e) {
                ui.post(() -> infoText.setText("Could not read this link.\n" + friendly(e)));
            }
            ui.post(() -> fetchBtn.setEnabled(true));
        }).start();
    }

    // ---------------------------------------------------------------- download

    private void startDownload() {
        String url = currentUrl();
        if (url == null) { status("Paste a link first."); return; }
        if (!engineReady || busy) return;

        final boolean video = videoRadio.isChecked();
        final int q = qualitySpinner.getSelectedItemPosition();
        final int f = formatSpinner.getSelectedItemPosition();
        SharedPreferences.Editor ed = prefs().edit().putBoolean("audio", !video);
        if (video) ed.putInt("vq", q).putInt("vf", f); else ed.putInt("af", f);
        ed.apply();

        busy = true;
        cancelled = false;
        downloadBtn.setEnabled(false);
        fetchBtn.setEnabled(false);
        cancelBtn.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        progress.setIndeterminate(true);
        statusText.setText("Starting…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            PowerManager pm = getSystemService(PowerManager.class);
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mediadl:download");
            lock.acquire(60 * 60 * 1000L);
            File work = new File(getExternalFilesDir(null), "work");
            deleteTree(work);
            //noinspection ResultOfMethodCallIgnored
            work.mkdirs();
            String result;
            try {
                YoutubeDLRequest req = new YoutubeDLRequest(url);
                req.addOption("--no-playlist");
                req.addOption("--no-mtime");
                req.addOption("--windows-filenames");
                req.addOption("--embed-metadata");
                req.addOption("-o", work.getAbsolutePath() + "/%(title).90B.%(ext)s");

                if (video) {
                    int h = VIDEO_HEIGHT[q];
                    String lim = h > 0 ? "[height<=" + h + "]" : "";
                    if (f == 0) {
                        req.addOption("-f", "bv*" + lim + "[ext=mp4]+ba[ext=m4a]/b" + lim + "[ext=mp4]/bv*" + lim + "+ba/b" + lim + "/b");
                        req.addOption("--merge-output-format", "mp4");
                        req.addOption("--remux-video", "mp4");
                    } else {
                        req.addOption("-f", "bv*" + lim + "+ba/b" + lim + "/b");
                        req.addOption("--merge-output-format", "mkv");
                    }
                } else {
                    req.addOption("-x");
                    if (f == 0) {
                        req.addOption("-f", "ba/b");
                        req.addOption("--audio-format", "mp3");
                        req.addOption("--audio-quality", "0");
                    } else if (f == 1) {
                        req.addOption("-f", "ba[ext=m4a]/ba/b");
                        req.addOption("--audio-format", "m4a");
                    } else {
                        req.addOption("-f", "ba/b");
                        req.addOption("--audio-format", "opus");
                    }
                }

                Function3<Float, Long, String, Unit> cb = (pct, eta, line) -> {
                    ui.post(() -> {
                        if (pct != null && pct > 0) {
                            progress.setIndeterminate(false);
                            progress.setProgress(Math.round(pct));
                            String etaText = eta != null && eta > 0
                                    ? String.format(Locale.US, " · %d:%02d left", eta / 60, eta % 60) : "";
                            statusText.setText(String.format(Locale.US, "Downloading… %.0f%%%s", pct, etaText));
                        } else if (line != null && (line.contains("Merger") || line.contains("ExtractAudio")
                                || line.contains("VideoRemuxer") || line.contains("Metadata"))) {
                            progress.setIndeterminate(true);
                            statusText.setText("Finishing (converting)…");
                        }
                    });
                    return Unit.INSTANCE;
                };

                YoutubeDL.getInstance().execute(req, PROCESS_ID, cb);

                File out = newestFile(work);
                if (out == null) throw new IOException("no file was produced");
                status("Saving to Download/MediaDownloader…");
                Uri saved = saveToDownloads(out);
                result = "✔ Saved: " + out.getName() + "\n\nOpen your Files app → Download → MediaDownloader.";
                final Uri openUri = saved;
                ui.post(() -> offerOpen(openUri, mimeFor(out.getName())));
            } catch (YoutubeDL.CanceledException e) {
                result = "Download cancelled.";
            } catch (Exception e) {
                result = cancelled ? "Download cancelled." : "Download failed.\n" + friendly(e);
            } finally {
                deleteTree(work);
                if (lock.isHeld()) lock.release();
            }
            final String r = result;
            ui.post(() -> {
                busy = false;
                statusText.setText(r);
                progress.setVisibility(View.GONE);
                cancelBtn.setVisibility(View.GONE);
                downloadBtn.setEnabled(true);
                fetchBtn.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }).start();
    }

    private void cancelDownload() {
        cancelled = true;
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
        status("Cancelling…");
    }

    private void offerOpen(Uri uri, String mime) {
        if (uri == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Downloaded")
                .setMessage("Saved in Download/MediaDownloader.")
                .setPositiveButton("Open", (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, mime);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    } catch (Exception e) {
                        status("No app found to open this file.");
                    }
                })
                .setNegativeButton("OK", null)
                .show();
    }

    // ---------------------------------------------------------------- files

    private Uri saveToDownloads(File f) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(f.getName()));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("could not create the file in Downloads");
        try (InputStream in = new FileInputStream(f);
             OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("could not write to Downloads");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        return uri;
    }

    private static File newestFile(File dir) {
        File best = null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName();
            if (!f.isFile() || n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp")) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".opus") || n.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        String low = m.toLowerCase(Locale.US);
        if (low.contains("login") || low.contains("private") || low.contains("rate-limit") || low.contains("cookies"))
            return "This post is private or the site wants you logged in. Public posts work best.";
        if (low.contains("unsupported url")) return "This website or link type isn't supported.";
        if (low.contains("unable to resolve") || low.contains("network") || low.contains("timed out"))
            return "Internet problem — check your connection and try again.";
        if (low.contains("sign in to confirm") || low.contains("http error 403"))
            return "The site blocked the request. Tap \"Update engine\" and try again.";
        // keep only the last useful line
        String[] lines = m.trim().split("\n");
        String last = lines[lines.length - 1];
        return last.length() > 300 ? last.substring(0, 300) + "…" : last;
    }

    private void showHelp() {
        String msg = "EASIEST WAY\n"
                + "In YouTube or Instagram tap Share → Media Downloader. The link is filled in automatically.\n\n"
                + "OR\nCopy the link → open this app → tap Paste.\n\n"
                + "THEN\nChoose Video or Audio only, pick quality/format, tap Download.\n\n"
                + "IF A DOWNLOAD FAILS\nTap \"Update engine\" (YouTube changes often) and try again. "
                + "Private Instagram posts can't be downloaded.\n\n"
                + "Files go to Download/MediaDownloader.\n\n"
                + "Please download only for personal use or content you have the right to save.";
        new AlertDialog.Builder(this).setTitle("How to use").setMessage(msg)
                .setPositiveButton("OK", null).show();
    }
}
