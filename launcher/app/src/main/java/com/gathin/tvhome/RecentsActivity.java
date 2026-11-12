package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.bridge.BridgeAdbManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Translucent tvOS-style Recent Apps overlay triggered by long-pressing Home.
 * Displays live running third-party apps (+ Play Store) with per-app RAM usage,
 * supports switching via LaunchCurtainController and force-stopping individual or all apps.
 */
public class RecentsActivity extends Activity {

    static final String EXTRA_OPENED_OVER_HOME = "opened_over_home";
    private static final long SNAPSHOT_TIMEOUT_MS = 3500L;
    private static final long FORCE_STOP_TIMEOUT_MS = 5000L;
    private static final String SAFE_PKG_REGEX = "[A-Za-z0-9_.]+";

    private static volatile RecentsActivity activeInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private BridgeAdbManager adb;

    private View scrim;
    private View container;
    private TextView memBadge;
    private RecyclerView recentsList;
    private LinearLayout stateCard;
    private ProgressBar progressBar;
    private TextView stateTitle;
    private TextView stateSubtitle;
    private TextView retryBtn;
    private TextView feedbackBanner;
    private LinearLayout actionsBar;
    private TextView btnCloseApp;
    private TextView btnCloseAll;
    private TextView hintText;

    private RecentsAdapter adapter;
    private LinearLayoutManager layoutManager;
    private LaunchCurtainController curtainController;
    private LaunchCurtainController.PanelDepthDelegate depthDelegate;

    private boolean animations = true;
    private boolean reducedMotion = false;
    private boolean openedOverHome;
    private boolean foregroundAppClosed;
    private String foregroundPkgBeforeRecents;
    private RecentsLogic.MemSummary currentMemSummary = new RecentsLogic.MemSummary(0L, 0L);

    private int selectedPosition = 0;
    private boolean busyAction = false;
    private boolean launchingTarget = false;
    private boolean backedByHomeCurtain = false;
    private boolean dismissing = false;
    private boolean resumed = false;
    private volatile boolean destroyed = false;

    private static final class LaunchMeta {
        final String packageName;
        final String label;
        final int uid;
        final boolean systemApp;
        final boolean updatedSystemApp;
        final ComponentName component;
        final ResolveInfo resolveInfo;
        final boolean isLeanback;

        LaunchMeta(String packageName, String label, int uid, boolean systemApp,
                   boolean updatedSystemApp, ComponentName component,
                   ResolveInfo resolveInfo, boolean isLeanback) {
            this.packageName = packageName;
            this.label = label;
            this.uid = uid;
            this.systemApp = systemApp;
            this.updatedSystemApp = updatedSystemApp;
            this.component = component;
            this.resolveInfo = resolveInfo;
            this.isLeanback = isLeanback;
        }
    }

    static boolean isCurrentlyOpen() {
        RecentsActivity act = activeInstance;
        return act != null && act.resumed && !act.isFinishing() && !act.isDestroyed();
    }

    static void dismissIfOpen() {
        RecentsActivity act = activeInstance;
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            act.handler.post(() -> {
                if (!act.isFinishing() && !act.isDestroyed()) {
                    act.dismissSmoothly(false);
                }
            });
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;
        setContentView(R.layout.activity_recents);

        Config cfg = Config.load(this);
        animations = cfg.animations;
        reducedMotion = cfg.reducedMotion;
        openedOverHome = getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPENED_OVER_HOME, false);

        executor = Executors.newSingleThreadExecutor();
        adb = BridgeAdbManager.getInstance(this);

        bindViews();
        setupCarousel();
        setupCurtain();
        setupActionButtons();
        playEntranceAnimation();
        loadRunningAppsAsync();
    }

    private void bindViews() {
        scrim = findViewById(R.id.recentsScrim);
        container = findViewById(R.id.recentsContainer);
        memBadge = findViewById(R.id.recentsMemBadge);
        recentsList = findViewById(R.id.recentsList);
        stateCard = findViewById(R.id.recentsStateCard);
        progressBar = findViewById(R.id.recentsProgress);
        stateTitle = findViewById(R.id.recentsStateTitle);
        stateSubtitle = findViewById(R.id.recentsStateSubtitle);
        retryBtn = findViewById(R.id.recentsRetryBtn);
        feedbackBanner = findViewById(R.id.recentsFeedbackBanner);
        actionsBar = findViewById(R.id.recentsActions);
        btnCloseApp = findViewById(R.id.btnCloseApp);
        btnCloseAll = findViewById(R.id.btnCloseAll);
        hintText = findViewById(R.id.recentsHint);

        scrim.setOnClickListener(v -> {
            if (!busyAction && (curtainController == null || !curtainController.isBusy())) {
                dismissSmoothly(foregroundAppClosed);
            }
        });
        retryBtn.setOnClickListener(v -> {
            if (!busyAction) loadRunningAppsAsync();
        });
    }

    private void setupCarousel() {
        layoutManager = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        recentsList.setLayoutManager(layoutManager);
        recentsList.setItemAnimator(null);
        adapter = new RecentsAdapter(this, new RecentsAdapter.Listener() {
            @Override
            public void onItemClicked(int position, View sourceArtView, RecentsAdapter.Item item) {
                switchToApp(sourceArtView, item);
            }

            @Override
            public void onCardFocused(int position) {
                selectedPosition = position;
                recentsList.smoothScrollToPosition(position);
            }

            @Override
            public void onMoveDownToActions(int position) {
                selectedPosition = position;
                if (actionsBar.getVisibility() == View.VISIBLE) {
                    btnCloseApp.requestFocus();
                }
            }
        }, animations, reducedMotion);
        recentsList.setAdapter(adapter);
    }

    private void setupCurtain() {
        depthDelegate = new LaunchCurtainController.PanelDepthDelegate(container, true);
        curtainController = new LaunchCurtainController(
                this,
                handler,
                scrim,
                findViewById(R.id.launchCurtain),
                findViewById(R.id.launchCurtainArt),
                new LaunchCurtainController.Host() {
                    @Override
                    public boolean animationsEnabled() {
                        return animations;
                    }

                    @Override
                    public boolean reducedMotionEnabled() {
                        return reducedMotion;
                    }

                    @Override
                    public void onBeforeCurtainExpand(View sourceView, int rootW, int rootH, AppLaunchSettings cfg) {
                        depthDelegate.prepare(rootW, rootH, cfg);
                    }

                    @Override
                    public void onCurtainDepthProgress(float easedProgress, AppLaunchSettings cfg) {
                        depthDelegate.update(easedProgress, cfg);
                    }

                    @Override
                    public void onRestoreBackground() {
                        launchingTarget = false;
                        depthDelegate.restore();
                    }

                    @Override
                    public boolean shouldCaptureArtBitmap(View sourceView) {
                        return sourceView != null;
                    }

                    @Override
                    public long translucentFinishDelayMs(AppLaunchSettings cfg) {
                        return LaunchCurtainController.translucentOverlayFinishDelayMs(
                                cfg, backedByHomeCurtain);
                    }

                    @Override
                    public void onTranslucentFinish() {
                        launchingTarget = false;
                        backedByHomeCurtain = false;
                        if (!isFinishing() && !isDestroyed()) {
                            finishForCurtainHandoff();
                        }
                    }
                });
        curtainController.setTranslucentHost(true);
    }

    private void setupActionButtons() {
        View.OnFocusChangeListener pillScaleListener = (v, focused) -> {
            float scale = focused && animations && !reducedMotion ? 1.06f : 1f;
            if (animations && !reducedMotion) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(150L)
                        .setInterpolator(new OvershootInterpolator(1.2f)).start();
            } else {
                v.setScaleX(scale);
                v.setScaleY(scale);
            }
        };
        btnCloseApp.setOnFocusChangeListener(pillScaleListener);
        btnCloseAll.setOnFocusChangeListener(pillScaleListener);

        View.OnKeyListener upToCardListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                focusCardAt(selectedPosition);
                return true;
            }
            return false;
        };
        btnCloseApp.setOnKeyListener(upToCardListener);
        btnCloseAll.setOnKeyListener(upToCardListener);

        btnCloseApp.setOnClickListener(v -> closeFocusedApp());
        btnCloseAll.setOnClickListener(v -> closeAllApps());
    }

    private void playEntranceAnimation() {
        if (!animations || reducedMotion) {
            scrim.setAlpha(1f);
            container.setAlpha(1f);
            container.setScaleX(1f);
            container.setScaleY(1f);
            return;
        }
        scrim.setAlpha(0f);
        container.setAlpha(0f);
        container.setScaleX(0.95f);
        container.setScaleY(0.95f);
        scrim.animate().alpha(1f).setDuration(170L)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        container.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private void loadRunningAppsAsync() {
        showStateLoading();
        executor.execute(() -> {
            Map<String, LaunchMeta> metas = queryLaunchableApps();
            String rawOutput = adb.executeShellOutput(RecentsLogic.SNAPSHOT_SHELL_COMMAND, SNAPSHOT_TIMEOUT_MS);
            if (rawOutput == null) {
                if (destroyed) return;
                handler.post(this::showStateAdbOffline);
                return;
            }
            RecentsLogic.Snapshot snapshot = RecentsLogic.parseSnapshot(rawOutput);
            List<RecentsLogic.LaunchableApp> launchableApps = new ArrayList<>(metas.size());
            for (LaunchMeta meta : metas.values()) {
                launchableApps.add(new RecentsLogic.LaunchableApp(
                        meta.packageName, meta.label, meta.uid, meta.systemApp, meta.updatedSystemApp));
            }
            List<RecentsLogic.RunningApp> running = RecentsLogic.buildRunningApps(snapshot, launchableApps);
            PackageManager pm = getPackageManager();
            List<RecentsAdapter.Item> uiItems = new ArrayList<>(running.size());
            for (RecentsLogic.RunningApp app : running) {
                LaunchMeta meta = metas.get(app.packageName);
                if (meta == null) continue;
                Drawable banner = AppAdapter.loadBannerForPackage(this, pm, meta.resolveInfo);
                Drawable icon = meta.resolveInfo.loadIcon(pm);
                int[] backdrop = banner == null && icon != null
                        ? AppAdapter.backdrop(icon, AppAdapter.dominantColor(icon))
                        : null;
                uiItems.add(new RecentsAdapter.Item(
                        app, meta.component, meta.isLeanback, banner, icon, backdrop));
            }

            if (destroyed) return;
            handler.post(() -> applySnapshotToUi(snapshot, uiItems));
        });
    }

    private Map<String, LaunchMeta> queryLaunchableApps() {
        PackageManager pm = getPackageManager();
        Map<String, LaunchMeta> out = new LinkedHashMap<>();

        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        addResolveInfos(pm, pm.queryIntentActivities(leanbackIntent, 0), true, out);

        Intent stdIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        addResolveInfos(pm, pm.queryIntentActivities(stdIntent, 0), false, out);
        return out;
    }

    private void addResolveInfos(PackageManager pm, List<ResolveInfo> list,
                                 boolean isLeanback, Map<String, LaunchMeta> out) {
        if (list == null) return;
        for (ResolveInfo ri : list) {
            if (ri == null || ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || out.containsKey(pkg)) continue;
            boolean systemApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updatedSys = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (!RecentsLogic.isEligiblePackage(pkg, ai.uid, systemApp, true)) continue;
            CharSequence rawLabel = ri.loadLabel(pm);
            String label = rawLabel != null ? rawLabel.toString().trim() : pkg;
            if (label.isEmpty()) label = pkg;
            ComponentName cmp = new ComponentName(pkg, ri.activityInfo.name);
            out.put(pkg, new LaunchMeta(pkg, label, ai.uid, systemApp, updatedSys, cmp, ri, isLeanback));
        }
    }

    private void applySnapshotToUi(RecentsLogic.Snapshot snapshot, List<RecentsAdapter.Item> uiItems) {
        if (destroyed || isFinishing()) return;
        currentMemSummary = snapshot.memSummary;
        updateMemBadge(currentMemSummary);

        if (!openedOverHome && foregroundPkgBeforeRecents == null) {
            String fg = snapshot.foregroundPackageBeforeRecents;
            if (RecentsLogic.SELF_PACKAGE.equals(fg)) {
                openedOverHome = true;
            } else {
                foregroundPkgBeforeRecents = fg;
            }
        }

        if (uiItems.isEmpty()) {
            showStateEmpty();
            return;
        }

        stateCard.setVisibility(View.GONE);
        feedbackBanner.setVisibility(View.GONE);
        recentsList.setVisibility(View.VISIBLE);
        actionsBar.setVisibility(View.VISIBLE);
        hintText.setVisibility(View.VISIBLE);

        selectedPosition = 0;
        adapter.setItems(uiItems);
        recentsList.post(() -> focusCardAt(0));
    }

    private void updateMemBadge(RecentsLogic.MemSummary mem) {
        String text = RecentsLogic.formatMemoryHeader(mem);
        if (text.isEmpty()) {
            memBadge.setVisibility(View.GONE);
        } else {
            memBadge.setText(text);
            memBadge.setVisibility(View.VISIBLE);
        }
    }

    private void showStateLoading() {
        recentsList.setVisibility(View.GONE);
        actionsBar.setVisibility(View.GONE);
        feedbackBanner.setVisibility(View.GONE);
        stateCard.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        stateTitle.setText(R.string.recents_loading);
        stateSubtitle.setVisibility(View.GONE);
        retryBtn.setVisibility(View.GONE);
        hintText.setVisibility(View.INVISIBLE);
    }

    private void showStateEmpty() {
        recentsList.setVisibility(View.GONE);
        actionsBar.setVisibility(View.GONE);
        feedbackBanner.setVisibility(View.GONE);
        stateCard.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        stateTitle.setText(R.string.recents_empty_title);
        stateSubtitle.setText(R.string.recents_empty_hint);
        stateSubtitle.setVisibility(View.VISIBLE);
        retryBtn.setVisibility(View.GONE);
        hintText.setVisibility(View.INVISIBLE);
    }

    private void showStateAdbOffline() {
        recentsList.setVisibility(View.GONE);
        actionsBar.setVisibility(View.GONE);
        feedbackBanner.setVisibility(View.GONE);
        stateCard.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        stateTitle.setText(R.string.recents_adb_offline);
        stateSubtitle.setText(R.string.recents_empty_hint);
        stateSubtitle.setVisibility(View.VISIBLE);
        retryBtn.setVisibility(View.VISIBLE);
        hintText.setVisibility(View.INVISIBLE);
        retryBtn.requestFocus();
    }

    private void focusCardAt(int pos) {
        int count = adapter.getItemCount();
        if (count <= 0) return;
        int clamped = Math.max(0, Math.min(pos, count - 1));
        selectedPosition = clamped;
        RecyclerView.ViewHolder vh = recentsList.findViewHolderForAdapterPosition(clamped);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.recentCard);
            if (card != null) {
                card.requestFocus();
                return;
            }
        }
        recentsList.scrollToPosition(clamped);
        recentsList.post(() -> {
            RecyclerView.ViewHolder delayed = recentsList.findViewHolderForAdapterPosition(clamped);
            if (delayed != null) {
                View card = delayed.itemView.findViewById(R.id.recentCard);
                if (card != null) card.requestFocus();
            }
        });
    }

    private void switchToApp(View sourceArtView, RecentsAdapter.Item item) {
        if (busyAction || dismissing || item == null || item.component == null) return;
        if (curtainController != null && curtainController.isBusy()) return;

        Intent launchIntent = new Intent(Intent.ACTION_MAIN)
                .setComponent(item.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (item.isLeanback) {
            launchIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        }

        AppAdapter.LaunchCommit commit = curtainApplied -> {
            if (curtainApplied && depthDelegate != null) {
                depthDelegate.commitHide();
            }
            boolean backed = false;
            if (curtainApplied && openedOverHome) {
                backed = MainActivity.holdCurtainForPausedOverlayLaunch(
                        curtainController != null ? curtainController.lastSettings() : null);
            }
            backedByHomeCurtain = backed;
            try {
                MainActivity.recordExternalCloseTarget(launchIntent, View.NO_ID);
                startActivity(launchIntent);
                launchingTarget = curtainApplied;
                if (curtainApplied) {
                    LauncherMotion.applyCurtainOpenTransition(
                            this, animations, reducedMotion, curtainController.lastSettings());
                } else {
                    LauncherMotion.applySubScreenOpenTransition(this, animations, reducedMotion);
                    finish();
                }
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                MainActivity.clearExternalCloseTarget();
                if (backed) {
                    MainActivity.releasePausedOverlayCurtain();
                }
                backedByHomeCurtain = false;
                launchingTarget = false;
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                if (!curtainApplied) finish();
                return false;
            }
        };

        if (curtainController != null) {
            curtainController.launch(sourceArtView, null, true, commit);
        } else {
            commit.run(false);
        }
    }

    private void closeFocusedApp() {
        if (busyAction || dismissing || (curtainController != null && curtainController.isBusy())) return;
        int count = adapter.getItemCount();
        if (count <= 0) return;

        int targetPos = Math.max(0, Math.min(selectedPosition, count - 1));
        RecentsAdapter.Item target = adapter.itemAt(targetPos);
        if (target == null || !isSafePackageName(target.app.packageName)) return;

        busyAction = true;
        final String pkg = target.app.packageName;
        final String label = target.app.label;
        final long rssKb = target.app.rssKb;
        final RecentsLogic.MemSummary memBefore = currentMemSummary;
        final boolean wasLastApp = (count == 1);

        RecyclerView.ViewHolder vh = recentsList.findViewHolderForAdapterPosition(targetPos);
        final View itemView = vh != null ? vh.itemView : null;
        long animDuration = itemView != null && animations && !reducedMotion ? 200L : 0L;
        if (itemView != null && animDuration > 0L) {
            float dy = -42f * getResources().getDisplayMetrics().density;
            itemView.animate()
                    .translationY(dy)
                    .alpha(0f)
                    .scaleX(0.90f)
                    .scaleY(0.90f)
                    .setDuration(animDuration)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }

        final AtomicReference<String> shellResult = new AtomicReference<>();
        final boolean[] shellDone = {false};
        final boolean[] animDone = {animDuration == 0L};

        Runnable finishStep = () -> {
            if (!shellDone[0] || !animDone[0] || destroyed || isFinishing()) return;

            if (itemView != null) {
                itemView.animate().cancel();
                itemView.setAlpha(1f);
                itemView.setTranslationY(0f);
                itemView.setScaleX(1f);
                itemView.setScaleY(1f);
            }

            if (pkg.equals(foregroundPkgBeforeRecents)) {
                foregroundAppClosed = true;
            }

            String out = shellResult.get();
            RecentsLogic.MemSummary memAfter = currentMemSummary;
            if (out != null) {
                memAfter = RecentsLogic.parseMemInfo(out);
                currentMemSummary = memAfter;
                updateMemBadge(currentMemSummary);
            }
            long freedMb = RecentsLogic.estimateFreedMb(memBefore, memAfter, rssKb);

            adapter.removeAt(targetPos);
            if (wasLastApp || adapter.getItemCount() == 0) {
                recentsList.setVisibility(View.GONE);
                actionsBar.setVisibility(View.GONE);
                hintText.setVisibility(View.INVISIBLE);
                showFeedbackThenReturnHome(getString(R.string.recents_freed_single, label, freedMb), 700L);
            } else {
                busyAction = false;
                int nextIdx = Math.min(targetPos, adapter.getItemCount() - 1);
                focusCardAt(nextIdx);
            }
        };

        if (animDuration > 0L) {
            handler.postDelayed(() -> {
                animDone[0] = true;
                finishStep.run();
            }, animDuration);
        }

        executor.execute(() -> {
            String cmd = "am force-stop " + pkg + "; sleep 0.12; head -n 8 /proc/meminfo";
            String out = adb.executeShellOutput(cmd, FORCE_STOP_TIMEOUT_MS);
            if (destroyed) return;
            handler.post(() -> {
                shellResult.set(out);
                shellDone[0] = true;
                finishStep.run();
            });
        });
    }

    private void closeAllApps() {
        if (busyAction || dismissing || (curtainController != null && curtainController.isBusy())) return;
        List<RecentsAdapter.Item> all = adapter.allItems();
        if (all.isEmpty()) return;

        StringBuilder cmdBuilder = new StringBuilder();
        long totalRssKb = 0L;
        int stoppedCount = 0;
        for (RecentsAdapter.Item item : all) {
            if (item == null || !isSafePackageName(item.app.packageName)) continue;
            cmdBuilder.append("am force-stop ").append(item.app.packageName).append("; ");
            totalRssKb += item.app.rssKb;
            stoppedCount++;
        }
        if (stoppedCount == 0) return;

        cmdBuilder.append("sleep 0.15; head -n 8 /proc/meminfo");
        final String batchCmd = cmdBuilder.toString();
        final int finalCount = stoppedCount;
        final long finalRssKb = totalRssKb;
        final RecentsLogic.MemSummary memBefore = currentMemSummary;

        busyAction = true;
        foregroundAppClosed = true;

        long animDuration = animations && !reducedMotion ? 190L : 0L;
        if (animDuration > 0L) {
            float dy = -28f * getResources().getDisplayMetrics().density;
            recentsList.animate().alpha(0f).translationY(dy).setDuration(animDuration)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            actionsBar.animate().alpha(0f).setDuration(animDuration).start();
        }

        final AtomicReference<String> shellResult = new AtomicReference<>();
        final boolean[] shellDone = {false};
        final boolean[] animDone = {animDuration == 0L};

        Runnable finishAll = () -> {
            if (!shellDone[0] || !animDone[0] || destroyed || isFinishing()) return;
            String out = shellResult.get();
            RecentsLogic.MemSummary memAfter = out != null ? RecentsLogic.parseMemInfo(out) : memBefore;
            currentMemSummary = memAfter;
            updateMemBadge(currentMemSummary);

            long freedMb = RecentsLogic.estimateFreedMb(memBefore, memAfter, finalRssKb);
            adapter.setItems(null);
            recentsList.setVisibility(View.GONE);
            actionsBar.setVisibility(View.GONE);
            hintText.setVisibility(View.INVISIBLE);
            showFeedbackThenReturnHome(getString(R.string.recents_freed_summary, finalCount, freedMb), 950L);
        };

        if (animDuration > 0L) {
            handler.postDelayed(() -> {
                animDone[0] = true;
                finishAll.run();
            }, animDuration);
        }

        executor.execute(() -> {
            String out = adb.executeShellOutput(batchCmd, FORCE_STOP_TIMEOUT_MS);
            if (destroyed) return;
            handler.post(() -> {
                shellResult.set(out);
                shellDone[0] = true;
                finishAll.run();
            });
        });
    }

    private void showFeedbackThenReturnHome(String message, long holdMs) {
        feedbackBanner.setText(message);
        feedbackBanner.setVisibility(View.VISIBLE);
        if (animations && !reducedMotion) {
            feedbackBanner.setAlpha(0f);
            feedbackBanner.setScaleX(0.92f);
            feedbackBanner.setScaleY(0.92f);
            feedbackBanner.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        } else {
            feedbackBanner.setAlpha(1f);
            feedbackBanner.setScaleX(1f);
            feedbackBanner.setScaleY(1f);
        }
        handler.postDelayed(() -> {
            if (!destroyed && !isFinishing()) {
                dismissSmoothly(true);
            }
        }, holdMs);
    }

    private void dismissSmoothly(boolean goHomeIfOverOtherApp) {
        if (dismissing || isFinishing() || isDestroyed()) return;
        dismissing = true;

        if (goHomeIfOverOtherApp && !openedOverHome) {
            startHome();
        }

        if (!animations || reducedMotion) {
            finish();
            return;
        }
        scrim.animate().cancel();
        container.animate().cancel();
        scrim.animate().alpha(0f).setDuration(150L).start();
        container.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(150L)
                .withEndAction(() -> {
                    if (!isFinishing() && !isDestroyed()) finish();
                }).start();
    }

    private void startHome() {
        try {
            Intent home = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        } catch (ActivityNotFoundException | SecurityException ignored) {}
    }

    private static boolean isSafePackageName(String pkg) {
        return pkg != null && !pkg.isEmpty() && pkg.matches(SAFE_PKG_REGEX);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!busyAction && (curtainController == null || !curtainController.isBusy())) {
            dismissSmoothly(foregroundAppClosed);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event != null ? event.getKeyCode() : 0;
        boolean isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (curtainController != null && curtainController.handleDispatchKeyEvent(event, isOk)) {
            return true;
        }
        if (isOk && !busyAction && !dismissing && isEmptyStateVisible()) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dismissSmoothly(foregroundAppClosed);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isEmptyStateVisible() {
        return stateCard != null && stateCard.getVisibility() == View.VISIBLE
                && progressBar != null && progressBar.getVisibility() != View.VISIBLE
                && retryBtn != null && retryBtn.getVisibility() != View.VISIBLE;
    }

    @Override
    public void onBackPressed() {
        if (busyAction || dismissing || (curtainController != null && curtainController.isBusy())) {
            return;
        }
        dismissSmoothly(foregroundAppClosed);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (curtainController != null) {
            curtainController.onResume();
        }
        if (backedByHomeCurtain && (curtainController == null || !curtainController.isBusy())) {
            backedByHomeCurtain = false;
            MainActivity.releasePausedOverlayCurtain();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (curtainController != null && curtainController.isBusy()) {
            launchingTarget = true;
            curtainController.onPause();
            return;
        }
        if (!busyAction && !dismissing) {
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (curtainController != null) {
            curtainController.onStop();
        }
        if (launchingTarget && !isFinishing()) {
            launchingTarget = false;
            backedByHomeCurtain = false;
            finishForCurtainHandoff();
        }
    }

    @Override
    public void finish() {
        super.finish();
        LauncherMotion.clearWindowTransition(this);
    }

    private void finishForCurtainHandoff() {
        super.finish();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (activeInstance == this) {
            activeInstance = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (curtainController != null) {
            curtainController.destroy();
        }
        super.onDestroy();
    }
}

