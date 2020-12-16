/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.screenshot;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import static com.android.systemui.screenshot.LogConfig.DEBUG_ANIM;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_INPUT;
import static com.android.systemui.screenshot.LogConfig.DEBUG_UI;
import static com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW;
import static com.android.systemui.screenshot.LogConfig.logTag;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ExitTransitionCoordinator;
import android.app.ExitTransitionCoordinator.ExitTransitionCallbacks;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.app.ChooserActivity;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.PhoneWindow;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ShareTransition;
import com.android.systemui.util.DeviceConfigProxy;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 * Controls the state and flow for screenshots.
 */
public class ScreenshotController {
    private static final String TAG = logTag(ScreenshotController.class);

    /**
     * POD used in the AsyncTask which saves an image in the background.
     */
    static class SaveImageInBackgroundData {
        public Bitmap image;
        public Consumer<Uri> finisher;
        public ScreenshotController.ActionsReadyListener mActionsReadyListener;

        void clearImage() {
            image = null;
        }
    }

    /**
     * Structure returned by the SaveImageInBackgroundTask
     */
    static class SavedImageData {
        public Uri uri;
        public Supplier<ShareTransition> shareTransition;
        public Notification.Action editAction;
        public Notification.Action deleteAction;
        public List<Notification.Action> smartActions;

        /**
         * POD for shared element transition to share sheet.
         */
        static class ShareTransition {
            public Bundle bundle;
            public Notification.Action shareAction;
            public Runnable onCancelRunnable;
        }

        /**
         * Used to reset the return data on error
         */
        public void reset() {
            uri = null;
            shareTransition = null;
            editAction = null;
            deleteAction = null;
            smartActions = null;
        }
    }

    interface ActionsReadyListener {
        void onActionsReady(ScreenshotController.SavedImageData imageData);
    }

    // These strings are used for communicating the action invoked to
    // ScreenshotNotificationSmartActionsProvider.
    static final String EXTRA_ACTION_TYPE = "android:screenshot_action_type";
    static final String EXTRA_ID = "android:screenshot_id";
    static final String ACTION_TYPE_DELETE = "Delete";
    static final String ACTION_TYPE_SHARE = "Share";
    static final String ACTION_TYPE_EDIT = "Edit";
    static final String EXTRA_SMART_ACTIONS_ENABLED = "android:smart_actions_enabled";
    static final String EXTRA_ACTION_INTENT = "android:screenshot_action_intent";

    static final String SCREENSHOT_URI_ID = "android:screenshot_uri_id";
    static final String EXTRA_CANCEL_NOTIFICATION = "android:screenshot_cancel_notification";
    static final String EXTRA_DISALLOW_ENTER_PIP = "android:screenshot_disallow_enter_pip";


    private static final int MESSAGE_CORNER_TIMEOUT = 2;
    private static final int SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS = 6000;

    // From WizardManagerHelper.java
    private static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    private final Context mContext;
    private final ScreenshotNotificationsController mNotificationsController;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final UiEventLogger mUiEventLogger;
    private final ImageExporter mImageExporter;
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;

    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final DisplayMetrics mDisplayMetrics;
    private final AccessibilityManager mAccessibilityManager;
    private final MediaActionSound mCameraSound;
    private final ScrollCaptureClient mScrollCaptureClient;
    private final DeviceConfigProxy mConfigProxy;
    private final PhoneWindow mWindow;
    private final View mDecorView;

    private final Binder mWindowToken;
    private ScreenshotView mScreenshotView;
    private Bitmap mScreenBitmap;
    private SaveImageInBackgroundTask mSaveInBgTask;

    private Animator mScreenshotAnimation;

    private Runnable mOnCompleteRunnable;

    private final Handler mScreenshotHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CORNER_TIMEOUT:
                    if (DEBUG_UI) {
                        Log.d(TAG, "Corner timeout hit");
                    }
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT);
                    ScreenshotController.this.dismissScreenshot(false);
                    break;
                default:
                    break;
            }
        }
    };

    /** Tracks config changes that require re-creating UI */
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_ORIENTATION
                    | ActivityInfo.CONFIG_LAYOUT_DIRECTION
                    | ActivityInfo.CONFIG_LOCALE
                    | ActivityInfo.CONFIG_UI_MODE
                    | ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_ASSETS_PATHS);

    @Inject
    ScreenshotController(
            Context context,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotNotificationsController screenshotNotificationsController,
            ScrollCaptureClient scrollCaptureClient,
            UiEventLogger uiEventLogger,
            DeviceConfigProxy configProxy,
            ImageExporter imageExporter,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor) {
        mScreenshotSmartActions = screenshotSmartActions;
        mNotificationsController = screenshotNotificationsController;
        mScrollCaptureClient = scrollCaptureClient;
        mUiEventLogger = uiEventLogger;
        mImageExporter = imageExporter;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;

        final DisplayManager dm = requireNonNull(context.getSystemService(DisplayManager.class));
        final Display display = dm.getDisplay(DEFAULT_DISPLAY);
        final Context displayContext = context.createDisplayContext(display);
        mContext = displayContext.createWindowContext(TYPE_SCREENSHOT, null);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        mAccessibilityManager = AccessibilityManager.getInstance(mContext);
        mConfigProxy = configProxy;

        mWindowToken = new Binder("ScreenshotController");
        mScrollCaptureClient.setHostWindowToken(mWindowToken);

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                MATCH_PARENT, MATCH_PARENT, /* xpos */ 0, /* ypos */ 0, TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.token = mWindowToken;
        // This is needed to let touches pass through outside the touchable areas
        mWindowLayoutParams.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        mWindow = new PhoneWindow(mContext);
        mWindow.setWindowManager(mWindowManager, null, null);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        mWindow.setBackgroundDrawableResource(android.R.color.transparent);
        mDecorView = mWindow.getDecorView();

        reloadAssets();

        mDisplayMetrics = new DisplayMetrics();
        display.getRealMetrics(mDisplayMetrics);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    void takeScreenshotFullscreen(Consumer<Uri> finisher, Runnable onComplete) {
        mOnCompleteRunnable = onComplete;

        takeScreenshotInternal(
                finisher,
                new Rect(0, 0, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
    }

    void handleImageAsScreenshot(Bitmap screenshot, Rect screenshotScreenBounds,
            Insets visibleInsets, int taskId, int userId, ComponentName topComponent,
            Consumer<Uri> finisher, Runnable onComplete) {
        // TODO: use task Id, userId, topComponent for smart handler
        mOnCompleteRunnable = onComplete;

        if (screenshot == null) {
            Log.e(TAG, "Got null bitmap from screenshot message");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            finisher.accept(null);
            mOnCompleteRunnable.run();
            return;
        }

        boolean showFlash = false;
        if (!aspectRatiosMatch(screenshot, visibleInsets, screenshotScreenBounds)) {
            showFlash = true;
            visibleInsets = Insets.NONE;
            screenshotScreenBounds.set(0, 0, screenshot.getWidth(), screenshot.getHeight());
        }
        saveScreenshot(screenshot, finisher, screenshotScreenBounds, visibleInsets, showFlash);
    }

    /**
     * Displays a screenshot selector
     */
    void takeScreenshotPartial(final Consumer<Uri> finisher, Runnable onComplete) {
        dismissScreenshot(true);
        mOnCompleteRunnable = onComplete;

        mWindowManager.addView(mScreenshotView, mWindowLayoutParams);

        mScreenshotView.takePartialScreenshot(
                rect -> takeScreenshotInternal(finisher, rect));
    }

    /**
     * Clears current screenshot
     */
    void dismissScreenshot(boolean immediate) {
        if (DEBUG_DISMISS) {
            Log.d(TAG, "dismissScreenshot(immediate=" + immediate + ")");
        }
        // If we're already animating out, don't restart the animation
        // (but do obey an immediate dismissal)
        if (!immediate && mScreenshotView.isDismissing()) {
            if (DEBUG_DISMISS) {
                Log.v(TAG, "Already dismissing, ignoring duplicate command");
            }
            return;
        }
        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
        if (immediate) {
            resetScreenshotView();
        } else {
            mScreenshotView.animateDismissal();
        }
    }

    /**
     * Update resources on configuration change. Reinflate for theme/color changes.
     */
    private void reloadAssets() {
        if (DEBUG_UI) {
            Log.d(TAG, "reloadAssets()");
        }
        boolean wasAttached = mDecorView.isAttachedToWindow();
        if (wasAttached) {
            if (DEBUG_WINDOW) {
                Log.d(TAG, "Removing screenshot window");
            }
            mWindowManager.removeView(mDecorView);
        }

        // respect the display cutout in landscape (since we'd otherwise overlap) but not portrait
        int orientation = mContext.getResources().getConfiguration().orientation;
        mWindowLayoutParams.setFitInsetsTypes(
                orientation == ORIENTATION_PORTRAIT ? 0 : WindowInsets.Type.displayCutout());

        // ignore system bar insets for the purpose of window layout
        mDecorView.setOnApplyWindowInsetsListener((v, insets) -> v.onApplyWindowInsets(
                new WindowInsets.Builder(insets)
                        .setInsets(WindowInsets.Type.all(), Insets.NONE)
                        .build()));

        // Inflate the screenshot layout
        mScreenshotView = (ScreenshotView)
                LayoutInflater.from(mContext).inflate(R.layout.global_screenshot, null);
        mScreenshotView.init(mUiEventLogger, new ScreenshotView.ScreenshotViewCallback() {
            @Override
            public void onUserInteraction() {
                resetTimeout();
            }

            @Override
            public void onDismiss() {
                resetScreenshotView();
            }
        });

        // TODO(159460485): Remove this when focus is handled properly in the system
        mDecorView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                if (DEBUG_INPUT) {
                    Log.d(TAG, "onTouch: ACTION_OUTSIDE");
                }
                // Once the user touches outside, stop listening for input
                setWindowFocusable(false);
            }
            return false;
        });

        mScreenshotView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (DEBUG_INPUT) {
                    Log.d(TAG, "onKeyEvent: KeyEvent.KEYCODE_BACK");
                }
                dismissScreenshot(false);
                return true;
            }
            return false;
        });

        // view is added to window manager in startAnimation
        mWindow.setContentView(mScreenshotView, mWindowLayoutParams);
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    private void takeScreenshotInternal(Consumer<Uri> finisher, Rect crop) {
        // copy the input Rect, since SurfaceControl.screenshot can mutate it
        Rect screenRect = new Rect(crop);
        int width = crop.width();
        int height = crop.height();
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        final SurfaceControl.DisplayCaptureArgs captureArgs =
                new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                        .setSourceCrop(crop)
                        .setSize(width, height)
                        .build();
        final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                SurfaceControl.captureDisplay(captureArgs);
        Bitmap screenshot = screenshotBuffer == null ? null : screenshotBuffer.asBitmap();

        if (screenshot == null) {
            Log.e(TAG, "takeScreenshotInternal: Screenshot bitmap was null");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "Supplying null to Consumer<Uri>");
            }
            finisher.accept(null);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "Calling mOnCompleteRunnable.run()");
            }
            mOnCompleteRunnable.run();
            return;
        }

        saveScreenshot(screenshot, finisher, screenRect, Insets.NONE, true);
    }

    private void saveScreenshot(Bitmap screenshot, Consumer<Uri> finisher, Rect screenRect,
            Insets screenInsets, boolean showFlash) {
        if (mAccessibilityManager.isEnabled()) {
            AccessibilityEvent event =
                    new AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setContentDescription(
                    mContext.getResources().getString(R.string.screenshot_saving_title));
            mAccessibilityManager.sendAccessibilityEvent(event);
        }

        if (mScreenshotView.isAttachedToWindow()) {
            // if we didn't already dismiss for another reason
            if (!mScreenshotView.isDismissing()) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_REENTERED);
            }
            if (DEBUG_WINDOW) {
                Log.d(TAG, "saveScreenshot: screenshotView is already attached, resetting. "
                        + "(dismissing=" + mScreenshotView.isDismissing() + ")");
            }
            mScreenshotView.reset();
        }

        mScreenBitmap = screenshot;

        if (!isUserSetupComplete()) {
            Log.w(TAG, "User setup not complete, displaying toast only");
            // User setup isn't complete, so we don't want to show any UI beyond a toast, as editing
            // and sharing shouldn't be exposed to the user.
            saveScreenshotAndToast(finisher);
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        if (mConfigChanges.applyNewConfig(mContext.getResources())) {
            if (DEBUG_UI) {
                Log.d(TAG, "saveScreenshot: reloading assets");
            }
            reloadAssets();
        }

        // The window is focusable by default
        setWindowFocusable(true);

        // Start the post-screenshot animation
        startAnimation(finisher, screenRect, screenInsets, showFlash);

        if (mConfigProxy.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.SCREENSHOT_SCROLLING_ENABLED, false)) {
            mScrollCaptureClient.request(DEFAULT_DISPLAY, (connection) ->
                    mScreenshotView.showScrollChip(() ->
                            runScrollCapture(connection,
                                    () -> mScreenshotHandler.post(
                                            () -> dismissScreenshot(false)))));
        }
    }

    private void runScrollCapture(ScrollCaptureClient.Connection connection, Runnable andThen) {
        ScrollCaptureController controller = new ScrollCaptureController(mContext, connection,
                mMainExecutor, mBgExecutor, mImageExporter);
        controller.run(andThen);
    }

    /**
     * Save the bitmap but don't show the normal screenshot UI.. just a toast (or notification on
     * failure).
     */
    private void saveScreenshotAndToast(Consumer<Uri> finisher) {
        // Play the shutter sound to notify that we've taken a screenshot
        mScreenshotHandler.post(() -> {
            mCameraSound.play(MediaActionSound.SHUTTER_CLICK);
        });

        saveScreenshotInWorkerThread(finisher, imageData -> {
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "returning URI to finisher (Consumer<URI>): " + imageData.uri);
            }
            finisher.accept(imageData.uri);
            if (imageData.uri == null) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
                mNotificationsController.notifyScreenshotError(
                        R.string.screenshot_failed_to_save_text);
            } else {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);
                mScreenshotHandler.post(() -> Toast.makeText(mContext,
                        R.string.screenshot_saved_title, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(final Consumer<Uri> finisher, Rect screenRect, Insets screenInsets,
            boolean showFlash) {
        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
        mScreenshotHandler.post(() -> {
            if (!mScreenshotView.isAttachedToWindow()) {
                if (DEBUG_WINDOW) {
                    Log.d(TAG, "Adding screenshot window");
                }
                mWindowManager.addView(mWindow.getDecorView(), mWindowLayoutParams);
            }

            mScreenshotView.prepareForAnimation(mScreenBitmap, screenInsets);

            mScreenshotHandler.post(() -> {
                if (DEBUG_WINDOW) {
                    Log.d(TAG, "adding OnComputeInternalInsetsListener");
                }
                mScreenshotView.getViewTreeObserver().addOnComputeInternalInsetsListener(
                        mScreenshotView);

                mScreenshotAnimation =
                        mScreenshotView.createScreenshotDropInAnimation(screenRect, showFlash);

                saveScreenshotInWorkerThread(finisher, this::showUiOnActionsReady);

                // Play the shutter sound to notify that we've taken a screenshot
                mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

                if (DEBUG_ANIM) {
                    Log.d(TAG, "starting post-screenshot animation");
                }
                mScreenshotAnimation.start();
            });
        });
    }

    /** Reset screenshot view and then call onCompleteRunnable */
    private void resetScreenshotView() {
        if (DEBUG_UI) {
            Log.d(TAG, "resetScreenshotView");
        }
        if (mScreenshotView.isAttachedToWindow()) {
            if (DEBUG_WINDOW) {
                Log.d(TAG, "Removing screenshot window");
            }
            mWindowManager.removeView(mDecorView);
        }
        mScreenshotView.reset();
        mOnCompleteRunnable.run();
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(Consumer<Uri> finisher,
            @Nullable ScreenshotController.ActionsReadyListener actionsReadyListener) {
        ScreenshotController.SaveImageInBackgroundData
                data = new ScreenshotController.SaveImageInBackgroundData();
        data.image = mScreenBitmap;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;

        if (mSaveInBgTask != null) {
            // just log success/failure for the pre-existing screenshot
            mSaveInBgTask.setActionsReadyListener(this::logSuccessOnActionsReady);
        }

        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, mImageExporter,
                mScreenshotSmartActions, data, getShareTransitionSupplier());
        mSaveInBgTask.execute();
    }

    private void resetTimeout() {
        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);

        AccessibilityManager accessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        long timeoutMs = accessibilityManager.getRecommendedTimeoutMillis(
                SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);

        mScreenshotHandler.sendMessageDelayed(
                mScreenshotHandler.obtainMessage(MESSAGE_CORNER_TIMEOUT),
                timeoutMs);
        if (DEBUG_UI) {
            Log.d(TAG, "dismiss timeout: " + timeoutMs + " ms");
        }

    }

    /**
     * Sets up the action shade and its entrance animation, once we get the screenshot URI.
     */
    private void showUiOnActionsReady(ScreenshotController.SavedImageData imageData) {
        logSuccessOnActionsReady(imageData);
        if (DEBUG_UI) {
            Log.d(TAG, "Showing UI actions");
        }

        resetTimeout();

        if (imageData.uri != null) {
            mScreenshotHandler.post(() -> {
                if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
                    mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            mScreenshotView.setChipIntents(imageData);
                        }
                    });
                } else {
                    mScreenshotView.setChipIntents(imageData);
                }
            });
        }
    }

    /**
     * Supplies the necessary bits for the shared element transition to share sheet.
     * Note that once supplied, the action intent to share must be sent immediately after.
     */
    private Supplier<ShareTransition> getShareTransitionSupplier() {
        return () -> {
            ExitTransitionCallbacks cb = new ExitTransitionCallbacks() {
                @Override
                public boolean isReturnTransitionAllowed() {
                    return false;
                }

                @Override
                public void onFinish() { }
            };

            Pair<ActivityOptions, ExitTransitionCoordinator> transition =
                    ActivityOptions.startSharedElementAnimation(mWindow, cb, null,
                            Pair.create(mScreenshotView.getScreenshotPreview(),
                                    ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME));
            transition.second.startExit();

            ShareTransition supply = new ShareTransition();
            supply.bundle = transition.first.toBundle();
            supply.onCancelRunnable = () -> ActivityOptions.stopSharedElementAnimation(mWindow);
            return supply;
        };
    }

    /**
     * Logs success/failure of the screenshot saving task, and shows an error if it failed.
     */
    private void logSuccessOnActionsReady(ScreenshotController.SavedImageData imageData) {
        if (imageData.uri == null) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_text);
        } else {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);
        }
    }

    private boolean isUserSetupComplete() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
    }

    /**
     * Updates the window focusability.  If the window is already showing, then it updates the
     * window immediately, otherwise the layout params will be applied when the window is next
     * shown.
     */
    private void setWindowFocusable(boolean focusable) {
        if (DEBUG_WINDOW) {
            Log.d(TAG, "setWindowFocusable: " + focusable);
        }
        if (focusable) {
            mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (mDecorView.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(mDecorView, mWindowLayoutParams);
        }
    }

    /** Does the aspect ratio of the bitmap with insets removed match the bounds. */
    private static boolean aspectRatiosMatch(Bitmap bitmap, Insets bitmapInsets,
            Rect screenBounds) {
        int insettedWidth = bitmap.getWidth() - bitmapInsets.left - bitmapInsets.right;
        int insettedHeight = bitmap.getHeight() - bitmapInsets.top - bitmapInsets.bottom;

        if (insettedHeight == 0 || insettedWidth == 0 || bitmap.getWidth() == 0
                || bitmap.getHeight() == 0) {
            if (DEBUG_UI) {
                Log.e(TAG, "Provided bitmap and insets create degenerate region: "
                        + bitmap.getWidth() + "x" + bitmap.getHeight() + " " + bitmapInsets);
            }
            return false;
        }

        float insettedBitmapAspect = ((float) insettedWidth) / insettedHeight;
        float boundsAspect = ((float) screenBounds.width()) / screenBounds.height();

        boolean matchWithinTolerance = Math.abs(insettedBitmapAspect - boundsAspect) < 0.1f;
        if (DEBUG_UI) {
            Log.d(TAG, "aspectRatiosMatch: don't match bitmap: " + insettedBitmapAspect
                    + ", bounds: " + boundsAspect);
        }
        return matchWithinTolerance;
    }
}
