package com.jon.facebatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int PICK_SOURCE_IMAGES = 101;
    private static final int PICK_TARGET_IMAGES = 102;
    private static final int PICK_SOURCE_FOLDER = 103;
    private static final int PICK_TARGET_FOLDER = 104;
    private static final int REQUEST_NOTIFICATIONS = 105;
    private static final int MAX_FOLDER_IMAGES = 1000;

    private final LinkedHashMap<String, UriTools.ImageRef> sources = new LinkedHashMap<>();
    private final LinkedHashMap<String, UriTools.ImageRef> targets = new LinkedHashMap<>();
    private final ExecutorService mediaExecutor = Executors.newFixedThreadPool(3);
    private final AtomicInteger sourceGeneration = new AtomicInteger();
    private final AtomicInteger targetGeneration = new AtomicInteger();

    private TextView sourceCount;
    private TextView targetCount;
    private LinearLayout sourceThumbnails;
    private LinearLayout targetThumbnails;
    private TextView equationText;
    private TextView equationDetail;
    private Button startButton;
    private LinearLayout progressCard;
    private ProgressBar progressBar;
    private TextView progressTitle;
    private TextView progressMessage;
    private TextView progressCounters;
    private TextView progressCurrent;
    private TextView progressError;
    private Button cancelButton;
    private Button retryButton;
    private Button openResultsButton;
    private TextView outputPathText;
    private boolean receiverRegistered;
    private boolean pendingStartAfterPermission;

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderBatchState(fromIntent(intent));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.prepareWindow(this);
        reconcileInterruptedBatch();
        restoreSelection(savedInstanceState);
        setContentView(buildContent());
        refreshSelections();
        renderBatchState(BatchStateStore.load(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(BatchService.ACTION_PROGRESS);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(progressReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppSettings.Snapshot settings = AppSettings.load(this);
        if (outputPathText != null) {
            outputPathText.setText("Downloads/" + settings.outputFolder + "/  •  WebP results save as JPG");
        }
        renderBatchState(BatchStateStore.load(this));
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(progressReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mediaExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveSelection(outState, "sources", sources);
        saveSelection(outState, "targets", targets);
    }

    private void reconcileInterruptedBatch() {
        BatchStateStore.State state = BatchStateStore.load(this);
        if (state.running && !BatchService.isProcessBatchActive()) {
            state.running = false;
            state.cancelled = true;
            state.current = "";
            state.message = "The prior batch was interrupted before it finished. Start it again or retry the recorded failures.";
            BatchStateStore.save(this, state);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 18));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = Ui.row(this, Gravity.CENTER_VERTICAL);
        TextView brand = Ui.text(this, "FaceBatch", 22, Ui.INK, true);
        header.addView(brand, Ui.weighted(1));
        Button settings = Ui.neutralButton(this, "Settings");
        compactButton(settings);
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        settings.setLayoutParams(settingsLp);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        header.addView(settings);
        content.addView(header, Ui.matchWrap());

        SelectionViews sourceViews = addSelectionCard(content,
                "1", "Faces to insert", "SOURCE", true);
        sourceCount = sourceViews.count;
        sourceThumbnails = sourceViews.thumbnails;

        SelectionViews targetViews = addSelectionCard(content,
                "2", "Photos to modify", "TARGET", false);
        targetCount = targetViews.count;
        targetThumbnails = targetViews.thumbnails;

        LinearLayout summary = Ui.row(this, Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams summaryLp = Ui.matchWrap();
        summaryLp.topMargin = Ui.dp(this, 10);
        content.addView(summary, summaryLp);

        LinearLayout summaryText = new LinearLayout(this);
        summaryText.setOrientation(LinearLayout.VERTICAL);
        summary.addView(summaryText, Ui.weighted(1));
        equationText = Ui.text(this, "0 × 0 = 0", 21, Ui.INK, true);
        summaryText.addView(equationText, Ui.matchWrap());
        equationDetail = Ui.text(this, "Select a face and target.", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams detailLp = Ui.matchWrap();
        detailLp.topMargin = Ui.dp(this, 2);
        summaryText.addView(equationDetail, detailLp);

        startButton = Ui.primaryButton(this, "Select images to begin");
        startButton.setMinHeight(0);
        startButton.setMinimumHeight(0);
        LinearLayout.LayoutParams startLp = Ui.matchWrap();
        startLp.topMargin = Ui.dp(this, 8);
        startLp.height = Ui.dp(this, 50);
        content.addView(startButton, startLp);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prepareStart();
            }
        });

        progressCard = buildProgressCard();
        LinearLayout.LayoutParams progressLp = Ui.matchWrap();
        progressLp.topMargin = Ui.dp(this, 10);
        content.addView(progressCard, progressLp);

        return scroll;
    }

    private SelectionViews addSelectionCard(LinearLayout parent, String number, String title,
                                            String badge, final boolean source) {
        LinearLayout card = (LinearLayout) Ui.card(this);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
        LinearLayout.LayoutParams cardLp = Ui.matchWrap();
        cardLp.topMargin = Ui.dp(this, 10);
        parent.addView(card, cardLp);

        LinearLayout titleRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        TextView numberView = Ui.text(this, number, 12, Color.WHITE, true);
        numberView.setGravity(Gravity.CENTER);
        numberView.setBackground(Ui.rounded(Ui.ACCENT, 99, this));
        titleRow.addView(numberView, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView titleView = Ui.text(this, title, 17, Ui.INK, true);
        LinearLayout.LayoutParams titleLp = Ui.weighted(1);
        titleLp.leftMargin = Ui.dp(this, 8);
        titleRow.addView(titleView, titleLp);
        TextView count = Ui.text(this, "0 selected", 11, Ui.ACCENT_DARK, true);
        count.setGravity(Gravity.CENTER);
        count.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        count.setBackground(Ui.rounded(Ui.ACCENT_SOFT, 99, this));
        titleRow.addView(count);
        card.addView(titleRow, Ui.matchWrap());

        LinearLayout buttonRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonRowLp = Ui.matchWrap();
        buttonRowLp.topMargin = Ui.dp(this, 8);
        card.addView(buttonRow, buttonRowLp);

        Button choose = Ui.secondaryButton(this, "Images");
        compactButton(choose);
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        buttonRow.addView(choose, chooseLp);
        choose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchImagePicker(source ? PICK_SOURCE_IMAGES : PICK_TARGET_IMAGES);
            }
        });

        Button folder = Ui.neutralButton(this, "Folder");
        compactButton(folder);
        LinearLayout.LayoutParams folderLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        folderLp.leftMargin = Ui.dp(this, 7);
        buttonRow.addView(folder, folderLp);
        folder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchFolderPicker(source ? PICK_SOURCE_FOLDER : PICK_TARGET_FOLDER);
            }
        });

        Button clear = Ui.neutralButton(this, "Clear");
        compactButton(clear);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        clearLp.leftMargin = Ui.dp(this, 7);
        buttonRow.addView(clear, clearLp);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (source) {
                    sources.clear();
                } else {
                    targets.clear();
                }
                refreshSelections();
            }
        });

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollerLp = Ui.matchWrap();
        scrollerLp.topMargin = Ui.dp(this, 8);
        card.addView(scroller, scrollerLp);
        LinearLayout thumbnails = new LinearLayout(this);
        thumbnails.setOrientation(LinearLayout.HORIZONTAL);
        thumbnails.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(thumbnails, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60)));

        return new SelectionViews(count, thumbnails);
    }

    private void compactButton(Button button) {
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setTextSize(14);
        button.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
    }

    private LinearLayout buildProgressCard() {
        LinearLayout card = (LinearLayout) Ui.card(this);
        progressTitle = Ui.text(this, "Batch progress", 19, Ui.INK, true);
        card.addView(progressTitle, Ui.matchWrap());
        progressMessage = Ui.text(this, "", 14, Ui.MUTED, false);
        LinearLayout.LayoutParams messageLp = Ui.matchWrap();
        messageLp.topMargin = Ui.dp(this, 6);
        card.addView(progressMessage, messageLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        if (Build.VERSION.SDK_INT >= 21) {
            progressBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
            progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT_SOFT));
        }
        LinearLayout.LayoutParams barLp = Ui.matchWrap();
        barLp.height = Ui.dp(this, 10);
        barLp.topMargin = Ui.dp(this, 14);
        card.addView(progressBar, barLp);

        progressCounters = Ui.text(this, "", 14, Ui.INK, true);
        LinearLayout.LayoutParams countersLp = Ui.matchWrap();
        countersLp.topMargin = Ui.dp(this, 12);
        card.addView(progressCounters, countersLp);
        progressCurrent = Ui.text(this, "", 13, Ui.MUTED, false);
        LinearLayout.LayoutParams currentLp = Ui.matchWrap();
        currentLp.topMargin = Ui.dp(this, 5);
        card.addView(progressCurrent, currentLp);
        progressError = Ui.text(this, "", 12, Ui.DANGER, false);
        LinearLayout.LayoutParams errorLp = Ui.matchWrap();
        errorLp.topMargin = Ui.dp(this, 8);
        card.addView(progressError, errorLp);

        LinearLayout actions = Ui.row(this, Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = Ui.matchWrap();
        actionsLp.topMargin = Ui.dp(this, 14);
        card.addView(actions, actionsLp);

        cancelButton = Ui.dangerButton(this, "Cancel");
        actions.addView(cancelButton, Ui.weighted(1));
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent cancel = new Intent(MainActivity.this, BatchService.class);
                cancel.setAction(BatchService.ACTION_CANCEL);
                startService(cancel);
            }
        });

        retryButton = Ui.secondaryButton(this, "Retry failed");
        LinearLayout.LayoutParams retryLp = Ui.weighted(1);
        retryLp.leftMargin = Ui.dp(this, 8);
        actions.addView(retryButton, retryLp);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent retry = new Intent(MainActivity.this, BatchService.class);
                retry.setAction(BatchService.ACTION_RETRY_FAILED);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(retry);
                } else {
                    startService(retry);
                }
            }
        });

        openResultsButton = Ui.neutralButton(this, "Open results");
        LinearLayout.LayoutParams openLp = Ui.weighted(1);
        openLp.leftMargin = Ui.dp(this, 8);
        actions.addView(openResultsButton, openLp);
        openResultsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openResultsFolder();
            }
        });
        return card;
    }

    private void launchImagePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private void launchFolderPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == PICK_SOURCE_IMAGES || requestCode == PICK_TARGET_IMAGES) {
            persistPermissions(data);
            List<Uri> uris = collectUris(data);
            addUris(requestCode == PICK_SOURCE_IMAGES, uris);
        } else if (requestCode == PICK_SOURCE_FOLDER || requestCode == PICK_TARGET_FOLDER) {
            Uri tree = data.getData();
            if (tree != null) {
                persistPermission(tree, data.getFlags());
                scanFolder(requestCode == PICK_SOURCE_FOLDER, tree);
            }
        }
    }

    private void scanFolder(final boolean source, final Uri tree) {
        Toast.makeText(this, "Scanning folder for images", Toast.LENGTH_SHORT).show();
        mediaExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final List<Uri> images = UriTools.scanImageTree(
                        MainActivity.this, tree, MAX_FOLDER_IMAGES);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        addUris(source, images);
                        String suffix = images.size() >= MAX_FOLDER_IMAGES
                                ? " The first " + MAX_FOLDER_IMAGES + " were added."
                                : "";
                        Toast.makeText(MainActivity.this,
                                "Added " + images.size() + " image"
                                        + (images.size() == 1 ? "." : "s.") + suffix,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void addUris(boolean source, List<Uri> uris) {
        LinkedHashMap<String, UriTools.ImageRef> destination = source ? sources : targets;
        for (Uri uri : uris) {
            if (uri == null) continue;
            String key = uri.toString();
            if (!destination.containsKey(key)) {
                destination.put(key, new UriTools.ImageRef(key, UriTools.displayName(this, uri)));
            }
        }
        refreshSelections();
    }

    private void persistPermissions(Intent data) {
        int flags = data.getFlags();
        for (Uri uri : collectUris(data)) {
            persistPermission(uri, flags);
        }
    }

    private void persistPermission(Uri uri, int flags) {
        int takeFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            takeFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
        }
    }

    private List<Uri> collectUris(Intent data) {
        ArrayList<Uri> result = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) result.add(uri);
            }
        } else if (data.getData() != null) {
            result.add(data.getData());
        }
        return result;
    }

    private void refreshSelections() {
        if (sourceCount == null) return;
        SelectionStore.save(this, "sources", sources);
        SelectionStore.save(this, "targets", targets);
        sourceCount.setText(countLabel(sources.size()));
        targetCount.setText(countLabel(targets.size()));
        renderThumbnails(sourceThumbnails, sources, sourceGeneration.incrementAndGet(), true);
        renderThumbnails(targetThumbnails, targets, targetGeneration.incrementAndGet(), false);
        long total = (long) sources.size() * (long) targets.size();
        equationText.setText(sources.size() + " × " + targets.size() + " = " + total + " swap" + (total == 1 ? "" : "s"));
        if (total == 0) {
            equationDetail.setText("Select both image groups.");
            startButton.setText("Select images to begin");
            startButton.setEnabled(false);
            startButton.setAlpha(0.55f);
        } else {
            equationDetail.setText("Ready to run.");
            startButton.setText("Start " + total + " swap" + (total == 1 ? "" : "s"));
            boolean running = BatchStateStore.load(this).running;
            startButton.setEnabled(!running);
            startButton.setAlpha(running ? 0.55f : 1f);
        }
    }

    private String countLabel(int count) {
        return count + " selected";
    }

    private void renderThumbnails(final LinearLayout container,
                                  LinkedHashMap<String, UriTools.ImageRef> values,
                                  final int generation, final boolean source) {
        container.removeAllViews();
        if (values.isEmpty()) {
            TextView empty = Ui.text(this, "No images selected", 13, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            container.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 54)));
            return;
        }
        int index = 0;
        int limit = Math.min(8, values.size());
        for (Map.Entry<String, UriTools.ImageRef> entry : values.entrySet()) {
            if (index >= limit) break;
            final Uri uri = Uri.parse(entry.getKey());
            final ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription(entry.getValue().name);
            image.setBackground(Ui.rounded(Color.rgb(232, 230, 225), 13, this));
            image.setClipToOutline(true);
            LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 54), Ui.dp(this, 54));
            if (index > 0) imageLp.leftMargin = Ui.dp(this, 6);
            container.addView(image, imageLp);
            image.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    LinkedHashMap<String, UriTools.ImageRef> selected = source ? sources : targets;
                    UriTools.ImageRef removed = selected.remove(uri.toString());
                    if (removed != null) {
                        Toast.makeText(MainActivity.this, "Removed " + removed.name,
                                Toast.LENGTH_SHORT).show();
                        refreshSelections();
                    }
                    return true;
                }
            });
            mediaExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final Bitmap bitmap = UriTools.thumbnail(MainActivity.this, uri, Ui.dp(MainActivity.this, 180));
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                int current = source ? sourceGeneration.get() : targetGeneration.get();
                                if (current == generation && !isFinishing()) {
                                    image.setImageBitmap(bitmap);
                                }
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }
            });
            index++;
        }
        if (values.size() > limit) {
            TextView more = Ui.text(this, "+" + (values.size() - limit), 18, Ui.ACCENT_DARK, true);
            more.setGravity(Gravity.CENTER);
            more.setBackground(Ui.rounded(Ui.ACCENT_SOFT, 13, this));
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 54), Ui.dp(this, 54));
            moreLp.leftMargin = Ui.dp(this, 6);
            container.addView(more, moreLp);
        }
    }

    private void prepareStart() {
        long total = (long) sources.size() * (long) targets.size();
        if (total <= 0) return;
        if (total > 200) {
            new AlertDialog.Builder(this)
                    .setTitle("Start a large batch?")
                    .setMessage("This selection will make " + total
                            + " separate API requests. The app will queue them rather than sending them all at once.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start batch", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            requestPermissionAndStart();
                        }
                    })
                    .show();
        } else {
            requestPermissionAndStart();
        }
    }

    private void requestPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } else {
            startBatch();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && pendingStartAfterPermission) {
            pendingStartAfterPermission = false;
            startBatch();
        }
    }

    private void startBatch() {
        SelectionStore.save(this, "sources", sources);
        SelectionStore.save(this, "targets", targets);
        Intent service = new Intent(this, BatchService.class);
        service.setAction(BatchService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        Toast.makeText(this, "Batch started", Toast.LENGTH_SHORT).show();
    }

    private void renderBatchState(BatchStateStore.State state) {
        if (progressCard == null) return;
        boolean hasState = state.running || state.total > 0;
        progressCard.setVisibility(hasState ? View.VISIBLE : View.GONE);
        if (!hasState) {
            refreshSelections();
            return;
        }
        int processed = state.completed + state.failed;
        progressBar.setMax(Math.max(1, state.total));
        progressBar.setProgress(Math.min(processed, Math.max(1, state.total)));
        progressTitle.setText(state.running ? "Batch in progress" :
                (state.cancelled ? "Batch cancelled" : "Batch complete"));
        progressMessage.setText(state.message == null ? "" : state.message);
        progressCounters.setText("Saved " + state.completed + "   •   Failed " + state.failed
                + "   •   Total " + state.total);
        progressCurrent.setText(state.current == null || state.current.isEmpty()
                ? "" : "Current: " + state.current);
        progressCurrent.setVisibility(state.current == null || state.current.isEmpty()
                ? View.GONE : View.VISIBLE);
        progressError.setText(state.lastError == null ? "" : state.lastError);
        progressError.setVisibility(state.lastError == null || state.lastError.isEmpty()
                ? View.GONE : View.VISIBLE);
        cancelButton.setVisibility(state.running ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(!state.running && state.failed > 0 ? View.VISIBLE : View.GONE);
        openResultsButton.setVisibility(state.completed > 0 ? View.VISIBLE : View.GONE);
        startButton.setEnabled(!state.running && !sources.isEmpty() && !targets.isEmpty());
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.55f);
    }

    private BatchStateStore.State fromIntent(Intent intent) {
        BatchStateStore.State state = new BatchStateStore.State();
        state.running = intent.getBooleanExtra(BatchService.EXTRA_RUNNING, false);
        state.cancelled = intent.getBooleanExtra(BatchService.EXTRA_CANCELLED, false);
        state.total = intent.getIntExtra(BatchService.EXTRA_TOTAL, 0);
        state.completed = intent.getIntExtra(BatchService.EXTRA_COMPLETED, 0);
        state.failed = intent.getIntExtra(BatchService.EXTRA_FAILED, 0);
        state.current = intent.getStringExtra(BatchService.EXTRA_CURRENT);
        state.message = intent.getStringExtra(BatchService.EXTRA_MESSAGE);
        state.lastError = intent.getStringExtra(BatchService.EXTRA_LAST_ERROR);
        BatchStateStore.State stored = BatchStateStore.load(this);
        state.outputFolder = stored.outputFolder;
        state.failedJobsJson = stored.failedJobsJson;
        return state;
    }

    private void openResultsFolder() {
        BatchStateStore.State batch = BatchStateStore.load(this);
        String folder = batch.total > 0 && batch.outputFolder != null && !batch.outputFolder.trim().isEmpty()
                ? batch.outputFolder : AppSettings.load(this).outputFolder;
        String documentId = "primary:Download/" + folder;
        Uri folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", documentId);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
            return;
        } catch (Exception ignored) {
        }
        Intent downloads = new Intent(Intent.ACTION_VIEW, MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        try {
            startActivity(downloads);
        } catch (Exception ignored) {
            Toast.makeText(this, "Open Downloads/" + folder + "/ in your file manager.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveSelection(Bundle state, String prefix,
                               LinkedHashMap<String, UriTools.ImageRef> values) {
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (UriTools.ImageRef ref : values.values()) {
            uris.add(ref.uri);
            names.add(ref.name);
        }
        state.putStringArrayList(prefix + "_uris", uris);
        state.putStringArrayList(prefix + "_names", names);
    }

    private void restoreSelection(Bundle state) {
        SelectionStore.load(this, "sources", sources);
        SelectionStore.load(this, "targets", targets);
        if (state == null) return;
        restoreSelectionMap(state, "sources", sources);
        restoreSelectionMap(state, "targets", targets);
    }

    private void restoreSelectionMap(Bundle state, String prefix,
                                     LinkedHashMap<String, UriTools.ImageRef> destination) {
        ArrayList<String> uris = state.getStringArrayList(prefix + "_uris");
        ArrayList<String> names = state.getStringArrayList(prefix + "_names");
        if (uris == null || names == null) return;
        for (int i = 0; i < uris.size() && i < names.size(); i++) {
            destination.put(uris.get(i), new UriTools.ImageRef(uris.get(i), names.get(i)));
        }
    }

    private static final class SelectionViews {
        final TextView count;
        final LinearLayout thumbnails;

        SelectionViews(TextView count, LinearLayout thumbnails) {
            this.count = count;
            this.thumbnails = thumbnails;
        }
    }
}
