package com.jon.facebatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BatchService extends Service {
    public static final String ACTION_START = "com.jon.facebatch.action.START";
    public static final String ACTION_CANCEL = "com.jon.facebatch.action.CANCEL";
    public static final String ACTION_RETRY_FAILED = "com.jon.facebatch.action.RETRY_FAILED";
    public static final String ACTION_PROGRESS = "com.jon.facebatch.action.PROGRESS";

    public static final String EXTRA_SOURCE_URIS = "source_uris";
    public static final String EXTRA_SOURCE_NAMES = "source_names";
    public static final String EXTRA_TARGET_URIS = "target_uris";
    public static final String EXTRA_TARGET_NAMES = "target_names";

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_CANCELLED = "cancelled";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_COMPLETED = "completed";
    public static final String EXTRA_FAILED = "failed";
    public static final String EXTRA_CURRENT = "current";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_LAST_ERROR = "last_error";

    private static final String CHANNEL_ID = "facebatch_processing";
    private static final int NOTIFICATION_ID = 40317;
    private static final AtomicBoolean PROCESS_BATCH_ACTIVE = new AtomicBoolean(false);

    private final Object runLock = new Object();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Set<HttpURLConnection> activeConnections =
            Collections.synchronizedSet(new HashSet<HttpURLConnection>());

    private volatile boolean running;
    private ExecutorService workerPool;
    private Thread coordinatorThread;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private volatile BatchStateStore.State activeState;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelBatch();
            if (!running) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_RETRY_FAILED.equals(action)) {
            List<SwapJob> failed = BatchStateStore.jobsFromJson(
                    BatchStateStore.load(this).failedJobsJson);
            JobSource jobs = new ListJobSource(failed);
            if (jobs.total() > 0) {
                startJobs(jobs, true);
            } else {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && intent != null) {
            JobSource jobs = buildCrossProduct(intent);
            if (jobs.total() > 0) {
                startJobs(jobs, false);
            } else {
                stopSelf(startId);
            }
        }
        return START_NOT_STICKY;
    }

    private JobSource buildCrossProduct(Intent intent) {
        List<UriTools.ImageRef> sourceRefs = SelectionStore.read(this, "sources");
        List<UriTools.ImageRef> targetRefs = SelectionStore.read(this, "targets");
        ArrayList<String> sourceUris = new ArrayList<>();
        ArrayList<String> sourceNames = new ArrayList<>();
        ArrayList<String> targetUris = new ArrayList<>();
        ArrayList<String> targetNames = new ArrayList<>();
        for (UriTools.ImageRef ref : sourceRefs) {
            sourceUris.add(ref.uri);
            sourceNames.add(ref.name);
        }
        for (UriTools.ImageRef ref : targetRefs) {
            targetUris.add(ref.uri);
            targetNames.add(ref.name);
        }

        // The persisted selection is the normal path and avoids large Binder payloads.
        // Intent extras remain a fallback for recovery or future integrations.
        if ((sourceUris.isEmpty() || targetUris.isEmpty()) && intent != null) {
            ArrayList<String> extraSourceUris = intent.getStringArrayListExtra(EXTRA_SOURCE_URIS);
            ArrayList<String> extraSourceNames = intent.getStringArrayListExtra(EXTRA_SOURCE_NAMES);
            ArrayList<String> extraTargetUris = intent.getStringArrayListExtra(EXTRA_TARGET_URIS);
            ArrayList<String> extraTargetNames = intent.getStringArrayListExtra(EXTRA_TARGET_NAMES);
            if (sourceUris.isEmpty() && extraSourceUris != null && extraSourceNames != null) {
                sourceUris.addAll(extraSourceUris);
                sourceNames.addAll(extraSourceNames);
            }
            if (targetUris.isEmpty() && extraTargetUris != null && extraTargetNames != null) {
                targetUris.addAll(extraTargetUris);
                targetNames.addAll(extraTargetNames);
            }
        }
        return new CrossProductJobSource(sourceUris, sourceNames, targetUris, targetNames);
    }

    public static boolean isProcessBatchActive() {
        return PROCESS_BATCH_ACTIVE.get();
    }

    private void startJobs(final JobSource jobs, final boolean retryRun) {
        synchronized (runLock) {
            if (running) {
                return;
            }
            running = true;
            PROCESS_BATCH_ACTIVE.set(true);
            cancelled.set(false);
        }

        final AppSettings.Snapshot settings = AppSettings.load(this);
        if (AppSettings.PROFILE_AUTO.equals(settings.profile)
                || AppSettings.PROFILE_FJOY.equals(settings.profile)) {
            FJoyApiClient.resetForNewBatch(this);
            FaceOverRouter.resetCache();
        }
        if (AppSettings.PROFILE_AUTO.equals(settings.profile)
                || AppSettings.PROFILE_AIFACE.equals(settings.profile)) {
            AIFaceSwapApiClient.resetSession();
            FaceOverRouter.resetCache();
        }
        final BatchStateStore.State state = new BatchStateStore.State();
        state.running = true;
        state.cancelled = false;
        state.total = jobs.total();
        state.completed = 0;
        state.failed = 0;
        state.current = "Preparing batch";
        boolean builtInSession = AppSettings.PROFILE_AUTO.equals(settings.profile)
                || AppSettings.PROFILE_FJOY.equals(settings.profile)
                || AppSettings.PROFILE_AIFACE.equals(settings.profile);
        state.message = builtInSession
                ? "Starting face-swap session"
                : (retryRun ? "Retrying failed swaps" : "Starting batch");
        state.outputFolder = settings.outputFolder;
        state.failedJobsJson = "[]";
        state.lastError = "";
        activeState = state;
        persistAndBroadcast(state);
        Notification initialNotification = buildNotification(state, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, initialNotification);
        }
        acquireWakeLock();

        coordinatorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runBatch(jobs, settings, state);
            }
        }, "FaceBatch-Coordinator");
        coordinatorThread.start();
    }

    private void runBatch(JobSource jobs, AppSettings.Snapshot settings,
                          BatchStateStore.State state) {
        final List<SwapJob> failedJobs = Collections.synchronizedList(new ArrayList<SwapJob>());
        final AtomicInteger savedSequence = new AtomicInteger(0);
        final File workDirectory = new File(getCacheDir(), "batch_work");
        clearDirectory(workDirectory);
        if (!workDirectory.exists()) {
            workDirectory.mkdirs();
        }

        final boolean mayUseFJoy = AppSettings.PROFILE_AUTO.equals(settings.profile)
                || AppSettings.PROFILE_FJOY.equals(settings.profile);

        int concurrency = Math.max(1, settings.concurrency);
        if (AppSettings.PROFILE_AUTO.equals(settings.profile)
                || AppSettings.PROFILE_AIFACE.equals(settings.profile)
                || AppSettings.PROFILE_FJOY.equals(settings.profile)
                || AppSettings.PROFILE_TAO.equals(settings.profile)) {
            // The regular Face Over backends create per-request identities, issue temporary
            // credentials, and may enforce server pacing. Run their complete workflows one at a
            // time even if a higher generic concurrency value was saved previously.
            concurrency = 1;
        }
        workerPool = Executors.newFixedThreadPool(concurrency);
        CompletionService<JobOutcome> completions = new ExecutorCompletionService<>(workerPool);
        int inFlight = 0;
        while (!cancelled.get() && inFlight < concurrency && jobs.hasNext()) {
            submitJob(completions, jobs.next(), settings, workDirectory, savedSequence, state);
            inFlight++;
        }

        try {
            while (inFlight > 0 && !cancelled.get()) {
                Future<JobOutcome> future = completions.take();
                JobOutcome outcome = future.get();
                inFlight--;
                synchronized (state) {
                    if (outcome.success) {
                        state.completed++;
                        state.message = "Saved " + outcome.filename;
                        state.lastError = "";
                    } else {
                        state.failed++;
                        failedJobs.add(outcome.job);
                        state.message = "Could not process " + outcome.job.label();
                        state.lastError = outcome.error;
                    }
                    state.current = outcome.job.label();
                    state.failedJobsJson = BatchStateStore.jobsToJson(failedJobs);
                    persistAndBroadcast(state);
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state, true));
                }
                while (!cancelled.get() && inFlight < concurrency && jobs.hasNext()) {
                    submitJob(completions, jobs.next(), settings, workDirectory, savedSequence, state);
                    inFlight++;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
        } catch (Exception exception) {
            state.lastError = safeMessage(exception);
        } finally {
            if (workerPool != null) {
                workerPool.shutdownNow();
            }
            disconnectAll();
            clearDirectory(workDirectory);
            if (mayUseFJoy) {
                FJoyApiClient.discardAfterBatch(this);
            }
            finishBatch(state, failedJobs);
        }
    }

    private void submitJob(CompletionService<JobOutcome> completions, final SwapJob job,
                           final AppSettings.Snapshot settings, final File workDirectory,
                           final AtomicInteger savedSequence, final BatchStateStore.State state) {
        completions.submit(new java.util.concurrent.Callable<JobOutcome>() {
            @Override
            public JobOutcome call() {
                return processJob(job, settings, workDirectory, savedSequence, state);
            }
        });
    }

    private JobOutcome processJob(SwapJob job, AppSettings.Snapshot settings,
                                  File workDirectory, AtomicInteger savedSequence,
                                  BatchStateStore.State state) {
        String lastError = "Unknown error";
        int attempt = 0;
        int rateLimitWaits = 0;
        while (attempt <= settings.retries) {
            if (cancelled.get()) {
                return JobOutcome.failure(job, "Batch cancelled.");
            }
            try {
                publishCurrent(job, attempt, settings.retries, state);
                File image = ApiEngine.execute(this, job, settings, workDirectory,
                        new ApiClient.Cancellation() {
                            @Override
                            public boolean isCancelled() {
                                return cancelled.get() || Thread.currentThread().isInterrupted();
                            }
                        }, new ApiClient.ConnectionMonitor() {
                            @Override
                            public void opened(HttpURLConnection connection) {
                                activeConnections.add(connection);
                            }

                            @Override
                            public void closed(HttpURLConnection connection) {
                                activeConnections.remove(connection);
                            }
                        });
                DownloadSaver.SavedImage saved = DownloadSaver.save(this, image, job,
                        settings.outputFolder, savedSequence.incrementAndGet());
                return JobOutcome.success(job, saved.filename);
            } catch (Exception exception) {
                lastError = safeMessage(exception);
                if (cancelled.get()) break;

                if (exception instanceof ApiClient.ApiException) {
                    ApiClient.ApiException api = (ApiClient.ApiException) exception;
                    if (api.httpStatus == 429 && api.retryAfterSeconds > 0 && rateLimitWaits < 4) {
                        rateLimitWaits++;
                        int wait = Math.min(180, api.retryAfterSeconds + 2);
                        publishRateLimitWait(job, wait, state);
                        try {
                            sleepCancellable(wait * 1000L);
                            continue;
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (!isRetryable(exception) || attempt >= settings.retries) {
                    break;
                }
                attempt++;
                try {
                    sleepCancellable(1000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return JobOutcome.failure(job, lastError);
    }

    private void publishRateLimitWait(SwapJob job, int seconds, BatchStateStore.State state) {
        synchronized (state) {
            if (!state.running) return;
            state.current = job.label();
            state.message = "Server asked us to wait. Retrying in " + seconds + " seconds";
            persistAndBroadcast(state);
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state, true));
        }
    }

    private void sleepCancellable(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled");
            }
            Thread.sleep(Math.min(250L, Math.max(1L, end - System.currentTimeMillis())));
        }
    }


    private static boolean isRetryable(Exception exception) {
        if (!(exception instanceof ApiClient.ApiException)) {
            return true;
        }
        int status = ((ApiClient.ApiException) exception).httpStatus;
        return status == 0 || status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private void publishCurrent(SwapJob job, int attempt, int retries,
                                BatchStateStore.State state) {
        synchronized (state) {
            if (!state.running) return;
            state.current = job.label();
            state.message = attempt == 0 ? "Uploading and processing" :
                    "Retry " + attempt + " of " + retries;
            persistAndBroadcast(state);
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state, true));
        }
    }

    private void finishBatch(BatchStateStore.State state, List<SwapJob> failedJobs) {
        boolean wasCancelled = cancelled.get();
        synchronized (state) {
            state.running = false;
            state.cancelled = wasCancelled;
            state.failedJobsJson = BatchStateStore.jobsToJson(failedJobs);
            state.current = "";
            if (wasCancelled) {
                state.message = "Batch cancelled. " + state.completed + " image"
                        + (state.completed == 1 ? " was" : "s were") + " saved.";
            } else if (state.failed == 0) {
                state.message = "Finished. All " + state.completed + " images were saved.";
            } else {
                state.message = "Finished with " + state.failed + " failed swap"
                        + (state.failed == 1 ? "." : "s.");
            }
            persistAndBroadcast(state);
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state, false));
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
        releaseWakeLock();
        synchronized (runLock) {
            running = false;
            PROCESS_BATCH_ACTIVE.set(false);
            activeState = null;
        }
        stopSelf();
    }

    private void cancelBatch() {
        cancelled.set(true);
        disconnectAll();
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        if (coordinatorThread != null) {
            coordinatorThread.interrupt();
        }
        BatchStateStore.State state = activeState;
        if (state == null) {
            state = BatchStateStore.load(this);
        }
        synchronized (state) {
            state.current = "Cancelling";
            state.message = "Stopping active requests";
            persistAndBroadcast(state);
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state, true));
        }
    }

    private void disconnectAll() {
        synchronized (activeConnections) {
            for (HttpURLConnection connection : activeConnections) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                }
            }
            activeConnections.clear();
        }
    }

    private void persistAndBroadcast(BatchStateStore.State state) {
        BatchStateStore.save(this, state);
        Intent broadcast = new Intent(ACTION_PROGRESS);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_RUNNING, state.running);
        broadcast.putExtra(EXTRA_CANCELLED, state.cancelled);
        broadcast.putExtra(EXTRA_TOTAL, state.total);
        broadcast.putExtra(EXTRA_COMPLETED, state.completed);
        broadcast.putExtra(EXTRA_FAILED, state.failed);
        broadcast.putExtra(EXTRA_CURRENT, state.current == null ? "" : state.current);
        broadcast.putExtra(EXTRA_MESSAGE, state.message == null ? "" : state.message);
        broadcast.putExtra(EXTRA_LAST_ERROR, state.lastError == null ? "" : state.lastError);
        sendBroadcast(broadcast);
    }

    private Notification buildNotification(BatchStateStore.State state, boolean active) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 7, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_facebatch)
                .setContentTitle(active ? "FaceBatch is working" : "FaceBatch finished")
                .setContentText(state.message)
                .setContentIntent(content)
                .setOnlyAlertOnce(active)
                .setAutoCancel(!active)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        int processed = state.completed + state.failed;
        if (active && state.total > 0) {
            builder.setProgress(state.total, Math.min(processed, state.total), false);
            Intent cancelIntent = new Intent(this, BatchService.class);
            cancelIntent.setAction(ACTION_CANCEL);
            PendingIntent cancel = PendingIntent.getService(this, 8, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_cancel, "Cancel", cancel).build());
            builder.setOngoing(true);
        } else {
            builder.setProgress(0, 0, false);
            builder.setOngoing(false);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Batch processing", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress while FaceBatch uploads and saves images");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "FaceBatch:BatchWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable == null ? "Unknown error" : throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    private static void clearDirectory(File directory) {
        if (directory == null || !directory.exists()) return;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    clearDirectory(file);
                }
                file.delete();
            }
        }
        directory.delete();
    }

    @Override
    public void onDestroy() {
        PROCESS_BATCH_ACTIVE.set(false);
        cancelled.set(true);
        disconnectAll();
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        if (coordinatorThread != null && coordinatorThread != Thread.currentThread()) {
            coordinatorThread.interrupt();
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private interface JobSource {
        int total();
        boolean hasNext();
        SwapJob next();
    }

    private static final class ListJobSource implements JobSource {
        private final List<SwapJob> jobs;
        private int index;

        ListJobSource(List<SwapJob> jobs) {
            this.jobs = jobs == null ? new ArrayList<SwapJob>() : jobs;
        }

        @Override
        public int total() {
            return jobs.size();
        }

        @Override
        public boolean hasNext() {
            return index < jobs.size();
        }

        @Override
        public SwapJob next() {
            return jobs.get(index++);
        }
    }

    private static final class CrossProductJobSource implements JobSource {
        private final ArrayList<String> sourceUris;
        private final ArrayList<String> sourceNames;
        private final ArrayList<String> targetUris;
        private final ArrayList<String> targetNames;
        private final int sourceCount;
        private final int targetCount;
        private int sourceIndex;
        private int targetIndex;

        CrossProductJobSource(ArrayList<String> sourceUris, ArrayList<String> sourceNames,
                              ArrayList<String> targetUris, ArrayList<String> targetNames) {
            this.sourceUris = sourceUris == null ? new ArrayList<String>() : sourceUris;
            this.sourceNames = sourceNames == null ? new ArrayList<String>() : sourceNames;
            this.targetUris = targetUris == null ? new ArrayList<String>() : targetUris;
            this.targetNames = targetNames == null ? new ArrayList<String>() : targetNames;
            sourceCount = Math.min(this.sourceUris.size(), this.sourceNames.size());
            targetCount = Math.min(this.targetUris.size(), this.targetNames.size());
        }

        @Override
        public int total() {
            long product = (long) sourceCount * (long) targetCount;
            return product > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
        }

        @Override
        public boolean hasNext() {
            return sourceIndex < sourceCount && targetIndex < targetCount;
        }

        @Override
        public SwapJob next() {
            SwapJob job = new SwapJob(sourceUris.get(sourceIndex), sourceNames.get(sourceIndex),
                    targetUris.get(targetIndex), targetNames.get(targetIndex));
            targetIndex++;
            if (targetIndex >= targetCount) {
                targetIndex = 0;
                sourceIndex++;
            }
            return job;
        }
    }

    private static final class JobOutcome {
        final SwapJob job;
        final boolean success;
        final String filename;
        final String error;

        private JobOutcome(SwapJob job, boolean success, String filename, String error) {
            this.job = job;
            this.success = success;
            this.filename = filename;
            this.error = error;
        }

        static JobOutcome success(SwapJob job, String filename) {
            return new JobOutcome(job, true, filename, "");
        }

        static JobOutcome failure(SwapJob job, String error) {
            return new JobOutcome(job, false, "", error);
        }
    }
}
