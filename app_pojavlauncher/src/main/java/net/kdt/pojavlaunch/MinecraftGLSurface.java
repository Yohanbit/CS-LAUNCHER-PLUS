package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.MainActivity.touchCharInput;
import static net.kdt.pojavlaunch.Tools.LOCAL_RENDERER;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_MOUSE_GRAB_FORCE;
import static net.kdt.pojavlaunch.utils.MCOptionUtils.getMcScale;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.hardware.input.InputManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.DefaultDataProvider;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.customcontrols.gamepad.direct.DirectGamepad;
import net.kdt.pojavlaunch.customcontrols.gamepad.direct.DirectGamepadEnableHandler;
import net.kdt.pojavlaunch.customcontrols.mouse.AbstractTouchpad;
import net.kdt.pojavlaunch.customcontrols.mouse.AndroidPointerCapture;
import net.kdt.pojavlaunch.customcontrols.mouse.InGUIEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.InGameEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.TouchEventProcessor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.performance.DynamicResolutionController;
import net.kdt.pojavlaunch.performance.PerformancePolicy;
import net.kdt.pojavlaunch.performance.ThermalGovernor;
import net.kdt.pojavlaunch.utils.FpsCounter;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.utils.TouchControllerUtils;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;


import fr.spse.gamepad_remapper.GamepadHandler;
import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;

/**
 * Class dealing with showing minecraft surface and taking inputs to dispatch them to minecraft
 */
public class MinecraftGLSurface extends View implements GrabListener, DirectGamepadEnableHandler {
    /* Gamepad object for gamepad inputs, instantiated on need */
    private GamepadHandler mGamepadHandler;
    private int mActiveGamepadDeviceId = -1;
    private InputManager mAndroidInputManager;
    private boolean mInputListenerRegistered;
    private final InputManager.InputDeviceListener mInputDeviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int deviceId) {}
        @Override public void onInputDeviceChanged(int deviceId) {}
        @Override public void onInputDeviceRemoved(int deviceId) {
            if (deviceId == mActiveGamepadDeviceId) releaseGamepadHandler();
        }
    };
    /* The RemapperView.Builder object allows you to set which buttons to remap */
    private final RemapperManager mInputManager = new RemapperManager(getContext(), new RemapperView.Builder(null)
            .remapA(true)
            .remapB(true)
            .remapX(true)
            .remapY(true)

            .remapLeftJoystick(true)
            .remapRightJoystick(true)
            .remapStart(true)
            .remapSelect(true)
            .remapLeftShoulder(true)
            .remapRightShoulder(true)
            .remapLeftTrigger(true)
            .remapRightTrigger(true)
            .remapDpad(true));

    /* Sensitivity, adjusted according to screen size */
    private final double mSensitivityFactor = (1.4 * (1080f/ Tools.getDisplayMetrics((Activity) getContext()).heightPixels));

    /* Surface ready listener, used by the activity to launch minecraft */
    SurfaceReadyListener mSurfaceReadyListener = null;
    final Object mSurfaceReadyListenerLock = new Object();
    /* View holding the surface, either a SurfaceView or a TextureView */
    View mSurface;
    Surface mNativeSurface;
    String TAG = "MinecraftGLSurface";

    private final InGameEventProcessor mIngameProcessor = new InGameEventProcessor(mSensitivityFactor);
    private final InGUIEventProcessor mInGUIProcessor = new InGUIEventProcessor();
    private TouchEventProcessor mCurrentTouchProcessor = mInGUIProcessor;
    private AndroidPointerCapture mPointerCapture;
    private boolean mLastGrabState = false;
    public static boolean sdlEnabled = false;
    boolean useSurfaceView = LauncherPreferences.PREF_USE_ALTERNATE_SURFACE;

    private final Handler mResolutionHandler = new Handler(Looper.getMainLooper());
    private final DynamicResolutionController mResolutionController = new DynamicResolutionController();
    private boolean mHostResumed, mResolutionSurfaceReady, mResolutionReleased;
    private boolean mDynamicResolutionActive, mApplyingDynamicResolution;
    private float mOriginalResolution;
    private float mLastAppliedResolution = LauncherPreferences.PREF_SCALE_FACTOR;
    private int mDynamicMinimum, mDynamicTarget;
    private long mLastResolutionPresents = -1L, mLastResolutionSampleAt, mLastValidResolutionSampleAt;
    private SharedPreferences mResolutionPreferences;
    private Runnable mResolutionChangedListener;
    private final SharedPreferences.OnSharedPreferenceChangeListener mResolutionPreferenceListener = (prefs, key) -> {
        if ("resolutionRatio".equals(key)) {
            // A player may explicitly choose exactly the current temporary scale. Float comparison
            // alone cannot distinguish that from a surface callback; the persisted selection can.
            Runnable manualChange = () -> {
                if (!mDynamicResolutionActive || mResolutionReleased) return;
                try {
                    float selected = prefs.getInt("resolutionRatio", Math.round(mOriginalResolution * 100f)) / 100f;
                    mOriginalResolution = selected;
                    mResolutionController.configure(selected, mDynamicMinimum, mDynamicTarget, SystemClock.uptimeMillis());
                    mLastResolutionPresents = -1L;
                    applyDynamicResolution(selected);
                } catch (Throwable error) {
                    restoreDynamicResolution();
                    Log.w(TAG, "Invalid manual resolution setting", error);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) manualChange.run();
            else mResolutionHandler.post(manualChange);
        }
        if ("extremeDynamicResolution".equals(key) || "extremeMinResolution".equals(key)
                || "extremeTargetFps".equals(key)) {
            mResolutionHandler.removeCallbacks(this.mResolutionTick);
            mResolutionHandler.post(this.mResolutionTick);
        }
    };
    private final Runnable mResolutionTick = new Runnable() {
        @Override public void run() {
            if (mResolutionReleased || !mHostResumed || !mResolutionSurfaceReady || !isAttachedToWindow()) {
                restoreDynamicResolution();
                return;
            }
            try {
                SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
                boolean enabled = prefs != null && prefs.getBoolean("extremeDynamicResolution", false);
                if (!enabled) {
                    restoreDynamicResolution();
                    return; // Default-off has no polling heartbeat; the preference listener wakes it.
                } else if (!CallbackBridge.isGrabbing()) {
                    restoreDynamicResolution();
                } else {
                    long now = SystemClock.uptimeMillis();
                    int minimum = prefs.getInt("extremeMinResolution", 60);
                    int target = prefs.getInt("extremeTargetFps", 60);
                    if (!mDynamicResolutionActive || minimum != mDynamicMinimum || target != mDynamicTarget) {
                        restoreDynamicResolution();
                        mOriginalResolution = LauncherPreferences.PREF_SCALE_FACTOR;
                        mDynamicMinimum = minimum;
                        mDynamicTarget = target;
                        mResolutionController.configure(mOriginalResolution, minimum, target, now);
                        mDynamicResolutionActive = true;
                        mLastResolutionPresents = -1L;
                        mLastValidResolutionSampleAt = now;
                    }
                    long presents = FpsCounter.getTotalPresents();
                    long elapsed = now - mLastResolutionSampleAt;
                    boolean validSample = false;
                    if (presents >= 0 && mLastResolutionPresents >= 0 && presents > mLastResolutionPresents
                            && elapsed >= 500L && elapsed <= 2500L) {
                        double fps = (presents - mLastResolutionPresents) * 1000.0 / elapsed;
                        if (fps > 0 && fps <= 1000) {
                            validSample = true;
                            mLastValidResolutionSampleAt = now;
                            // UNKNOWN is not a thermal event. Heat can veto recovery, never fabricate load.
                            PerformancePolicy policy = PerformancePolicy.current();
                            boolean hot = policy != null && policy.thermalPolling
                                    && ThermalGovernor.current().atLeast(ThermalGovernor.Level.MODERATE);
                            applyDynamicResolution(mResolutionController.sample(fps, hot, now));
                        }
                    }
                    if (!validSample) mResolutionController.sample(-1, false, now);
                    mLastResolutionPresents = presents;
                    mLastResolutionSampleAt = now;
                    // Unsupported/reset/stalled counters are not evidence of low FPS. Fail open to quality.
                    if (now - mLastValidResolutionSampleAt > 3000L) restoreDynamicResolution();
                }
            } catch (Throwable error) {
                restoreDynamicResolution();
                Log.w(TAG, "Dynamic resolution unavailable; keeping user resolution", error);
                return; // A preference change or lifecycle restart may retry; do not spam a bad renderer.
            }
            mResolutionHandler.postDelayed(this, 1000L);
        }
    };

    private void applyDynamicResolution(float scale) {
        if (Math.abs(LauncherPreferences.PREF_SCALE_FACTOR - scale) < 0.0001f) return;
        if (getWidth() < 2 || getHeight() < 2 || mNativeSurface == null || !mNativeSurface.isValid()) return;
        if (Tools.getDisplayFriendlyRes(getWidth(), scale) < 2
                || Tools.getDisplayFriendlyRes(getHeight(), scale) < 2) return;
        mApplyingDynamicResolution = true;
        try {
            LauncherPreferences.PREF_SCALE_FACTOR = scale; // Input coordinates use the same live scale.
            refreshSize(true); // Existing fixed-buffer + GLFW window-size callback, always on UI thread.
        } finally {
            mApplyingDynamicResolution = false;
        }
    }

    private void restoreDynamicResolution() {
        if (!mDynamicResolutionActive) return;
        mDynamicResolutionActive = false;
        mLastResolutionPresents = -1L;
        mApplyingDynamicResolution = true;
        try {
            LauncherPreferences.PREF_SCALE_FACTOR = mOriginalResolution;
            if (mResolutionSurfaceReady && !mResolutionReleased) refreshSize(true);
        } catch (Throwable error) {
            // Keep the user scale even if a disappearing renderer cannot accept a resize now.
            Log.w(TAG, "Unable to restore game buffer immediately", error);
        } finally {
            mApplyingDynamicResolution = false;
        }
    }

    private void startResolutionTicker() {
        if (mResolutionReleased) return;
        if (mResolutionPreferences == null && LauncherPreferences.DEFAULT_PREF != null) {
            mResolutionPreferences = LauncherPreferences.DEFAULT_PREF;
            mResolutionPreferences.registerOnSharedPreferenceChangeListener(mResolutionPreferenceListener);
        }
        mResolutionHandler.removeCallbacks(mResolutionTick);
        mResolutionHandler.post(mResolutionTick);
    }

    private void stopResolutionTicker() {
        mResolutionHandler.removeCallbacksAndMessages(null);
        restoreDynamicResolution();
    }

    /** Manual selection, not the transient scale used for buffers and input coordinates. */
    public float getUserResolutionScale() {
        return mDynamicResolutionActive ? mOriginalResolution : LauncherPreferences.PREF_SCALE_FACTOR;
    }

    /** Host uses this to update resolution-dependent overlays, including the touch hotbar. */
    public void setResolutionChangedListener(Runnable listener) {
        mResolutionChangedListener = listener;
    }

    public MinecraftGLSurface(Context context) {
        this(context, null);
    }

    public MinecraftGLSurface(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setFocusable(true);
        CallbackBridge.setDirectGamepadEnableHandler(this);
        CallbackBridge.setMinecraftGLSurface(this);
    }

    public View getSurfaceView() {
        return mSurface;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setUpPointerCapture(AbstractTouchpad touchpad) {
        if(mPointerCapture != null) mPointerCapture.detach();
        mPointerCapture = new AndroidPointerCapture(touchpad, this);
    }
    protected static View.OnGenericMotionListener motionListener = (v, event) -> false;
    private static void setupSDL(Context ctx, Surface nativeSurface, ViewGroup layout){
        SDLSurface surface = new SDLSurface(ctx);
        motionListener = SDLActivity.getMotionListener();
        // Sets up the Java side, must be done here or else it might run on a non-looper thread
        // which crashes the SDLCommandHandler
        org.libsdl.app.SDL.initialize();
        SDL.setContext((MainActivity) ctx);
        SDLActivity.externalInitialize(surface, layout, nativeSurface);
    }

    /** Initialize the view and all its settings
     * @param isAlreadyRunning set to true to tell the view that the game is already running
     *                         (only updates the window without calling the start listener)
     * @param touchpad the optional cursor-emulating touchpad, used for touch event processing
     *                 when the cursor is not grabbed
     */
    public void start(boolean isAlreadyRunning, AbstractTouchpad touchpad){
        if (!mInputListenerRegistered) {
            mAndroidInputManager = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
            if (mAndroidInputManager != null) {
                mAndroidInputManager.registerInputDeviceListener(mInputDeviceListener, null);
                mInputListenerRegistered = true;
            }
        }
        if(Tools.isAndroid8OrHigher()) setUpPointerCapture(touchpad);
        mInGUIProcessor.setAbstractTouchpad(touchpad);
        // Kopper Zink has orientation issues on SurfaceView
        try {
            useSurfaceView = useSurfaceView && !LOCAL_RENDERER.equals("opengles3_desktopgl_zink_kopper");
        } catch (NullPointerException ignored){}
        if(useSurfaceView){
            SurfaceView surfaceView = new SurfaceView(getContext());
            mSurface = surfaceView;
            mNativeSurface = surfaceView.getHolder().getSurface();
            setupSDL(getContext(), mNativeSurface, (ViewGroup) getParent());
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    mResolutionSurfaceReady = true;
                    startResolutionTicker();
                    net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setSurfaceAttached(true);
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(mNativeSurface);
                        return;
                    }
                    isCalled = true;

                    realStart(mNativeSurface);
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    refreshSize();
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    mResolutionSurfaceReady = false;
                    stopResolutionTicker();
                    clearSurfaceFrameRate(holder.getSurface());
                    net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setSurfaceAttached(false);
                    /*
                    Surface recreation in SurfaceView happens very often. When tabbing back in from
                    out, when minimizing floating window, when turning into floating window, etc.
                    Whenever the surface isn't in view, it is destroyed. When going into floating
                    window, it appears to automatically release the associated ANativeWindow. This
                    can cause a crash if not handled.
                     */
                }
            });

            ((ViewGroup)getParent()).addView(surfaceView);
        }else{
            TextureView textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            mSurface = textureView;

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    mNativeSurface = new Surface(surface);
                    mResolutionSurfaceReady = true;
                    startResolutionTicker();
                    net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setSurfaceAttached(true);
                    setupSDL(getContext(), mNativeSurface, (ViewGroup) getParent());
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(mNativeSurface);
                        return;
                    }
                    isCalled = true;

                    realStart(mNativeSurface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    refreshSize();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    mResolutionSurfaceReady = false;
                    stopResolutionTicker();
                    clearSurfaceFrameRate(mNativeSurface);
                    net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setSurfaceAttached(false);
                    net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setCursorVisible(false);
                    /*
                    Surface recreation in TextureView can only really happen once, when turning
                    into a floating window. Subsequent turns to floating window no longer trigger
                    recreation. Tabbing out and in does not trigger recreation.
                     */
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });

            ((ViewGroup)getParent()).addView(textureView);
        }


    }

    /**
     * The touch event for both grabbed an non-grabbed mouse state on the touch screen
     * Does not cover the virtual mouse touchpad
     */
    @Override
    @SuppressWarnings("accessibility")
    public boolean onTouchEvent(MotionEvent e) {
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) return false;

        // Looking for a mouse to handle, won't have an effect if no mouse exists.
        for (int i = 0; i < e.getPointerCount(); i++) {
            int toolType = e.getToolType(i);
            if(toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                if(Tools.isAndroid8OrHigher() &&
                        mPointerCapture != null) {
                    // Can't handleAutomaticCapture if mouse isn't captured
                    if (!CallbackBridge.isGrabbing() // Only capture if not in menu and user said so
                            && !PREF_MOUSE_GRAB_FORCE) {
                        // This returns true but we really can't consume this.
                        // Else we don't receive ACTION_MOVE
                        return !dispatchGenericMotionEvent(e);
                    }
                    mPointerCapture.handleAutomaticCapture();
                    return true;
                }
            }else if(toolType != MotionEvent.TOOL_TYPE_STYLUS) continue;

            // Mouse found
            if(CallbackBridge.isGrabbing()) return false;
            CallbackBridge.sendCursorPos(   e.getX(i) * LauncherPreferences.PREF_SCALE_FACTOR, e.getY(i) * LauncherPreferences.PREF_SCALE_FACTOR);
            return true; //mouse event handled successfully
        }
        TouchControllerUtils.processTouchEvent(e, this);
        if (mIngameProcessor == null || mInGUIProcessor == null) return true;
        return mCurrentTouchProcessor.processTouchEvent(e);
    }

    private void createGamepad(View contextView, InputDevice inputDevice) {
        mActiveGamepadDeviceId = inputDevice != null ? inputDevice.getId() : -1;
        if(CallbackBridge.sGamepadDirectInput && !sdlEnabled) {
            mGamepadHandler = new DirectGamepad();
        }else if(!sdlEnabled) {
            mGamepadHandler = new Gamepad(contextView, inputDevice, DefaultDataProvider.INSTANCE, true);
        }else mGamepadHandler = (code, value) -> {}; // Ensure it isn't null while also not processing the events.
    }

    /**
     * The event for mouse/joystick movements
     */
    @SuppressLint("NewApi")
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if(sdlEnabled && Gamepad.isGamepadEvent(event)) {
            final MotionEvent copy = MotionEvent.obtain(event);
            PojavApplication.sExecutorService.execute(()->{
                try {
                    motionListener.onGenericMotion(this, copy);
                    copy.recycle();
                } catch (Throwable ignored) {
                    Log.e(TAG, "SDL failed to send motionevent!");
                }
            });
            return true;
        }
        super.dispatchGenericMotionEvent(event);
        int mouseCursorIndex = -1;

        if(!sdlEnabled && Gamepad.isGamepadEvent(event)){
            if(mGamepadHandler == null) createGamepad(this, event.getDevice());

            mInputManager.handleMotionEventInput(getContext(), event, mGamepadHandler);
            return true;
        }

        for(int i = 0; i < event.getPointerCount(); i++) {
            if(event.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;
            // Mouse found
            mouseCursorIndex = i;
            break;
        }
        if(mouseCursorIndex == -1) return false; // we cant consoom that, theres no mice!

        // Make sure we grabbed the mouse if necessary
        updateGrabState(CallbackBridge.isGrabbing());
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
                CallbackBridge.mouseX = (event.getX(mouseCursorIndex) * LauncherPreferences.PREF_SCALE_FACTOR);
                CallbackBridge.mouseY = (event.getY(mouseCursorIndex) * LauncherPreferences.PREF_SCALE_FACTOR);
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(event.getActionButton(),true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(event.getActionButton(),false);
            default:
                return false;
        }
    }

    /** The event for keyboard/ gamepad button inputs */
    public boolean processKeyEvent(KeyEvent event) {
        //Log.i("KeyEvent", event.toString());

        //Filtering useless events by order of probability
        int eventKeycode = event.getKeyCode();
        if(eventKeycode == KeyEvent.KEYCODE_UNKNOWN) return true;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if(event.getRepeatCount() != 0) return true;
        int action = event.getAction();
        if(action == KeyEvent.ACTION_MULTIPLE) return true;
        // Ignore the cancelled up events. They occur when the user switches layouts.
        // In accordance with https://developer.android.com/reference/android/view/KeyEvent#FLAG_CANCELED
        if(action == KeyEvent.ACTION_UP &&
                (event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) return true;

        //Sometimes, key events comes from SOME keys of the software keyboard
        //Even weirder, is is unknown why a key or another is selected to trigger a keyEvent
        if((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD){
            if(eventKeycode == KeyEvent.KEYCODE_ENTER) return true; //We already listen to it.
            touchCharInput.dispatchKeyEvent(event);
            return true;
        }

        //Sometimes, key events may come from the mouse
        if(event.getDevice() != null
                && ( (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ||   (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)  ){

            if(eventKeycode == KeyEvent.KEYCODE_BACK){
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }
        // Android bundles in garbage KeyEvents for compatibility with old apps
        // that don't have controller code so we are, checking for em.
        boolean isGamepadEvent = Gamepad.isGamepadEvent(event);
        if (sdlEnabled && isGamepadEvent) {
            final KeyEvent copy = new KeyEvent(event);
            PojavApplication.sExecutorService.execute(() -> {
                try {
                    SDLActivity.handleKeyEvent(this, eventKeycode, copy, null);
                } catch (Throwable ignored) {
                    Log.e(TAG, "SDL failed to send keyevent!");
                }
            });
            return true;
        }
        if(!sdlEnabled && isGamepadEvent){
            if(mGamepadHandler == null) createGamepad(this, event.getDevice());

            mInputManager.handleKeyEventInput(getContext(), event, mGamepadHandler);
            return true;
        }

        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(eventKeycode);
        if(EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }

        // Some events will be generated an infinite number of times when no consumed
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    /** Convert the mouse button, then send it
     * @return Whether the event was processed
     */
    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = -256;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                break;
            case MotionEvent.BUTTON_TERTIARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
                break;
            case MotionEvent.BUTTON_SECONDARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
                break;
        }
        if(glfwButton == -256) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }

    /** Called when the size need to be set at any point during the surface lifecycle **/
    public void refreshSize(){
        refreshSize(false);
    }

    /** Same as refreshSize, but allows you to force an immediate size update **/
    public void refreshSize(boolean immediate) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mResolutionHandler.post(() -> refreshSize(immediate));
            return;
        }
        // A live manual change becomes the new ceiling; surface resize callbacks alone do not.
        if (mDynamicResolutionActive && !mApplyingDynamicResolution) {
            float requested = LauncherPreferences.PREF_SCALE_FACTOR;
            if (Math.abs(requested - mLastAppliedResolution) > 0.0001f) {
                mOriginalResolution = requested;
                mResolutionController.configure(requested, mDynamicMinimum, mDynamicTarget, SystemClock.uptimeMillis());
                mLastResolutionPresents = -1L;
            }
        }
        if(isInLayout() && !immediate) {
            post(this::refreshSize);
            return;
        }
        // Use the width and height of the View instead of display dimensions to avoid
        // getting squiched/stretched due to inconsistencies between the layout and
        // screen dimensions.
        int newWidth = Tools.getDisplayFriendlyRes(getWidth(), LauncherPreferences.PREF_SCALE_FACTOR);
        int newHeight = Tools.getDisplayFriendlyRes(getHeight(), LauncherPreferences.PREF_SCALE_FACTOR);
        if (newHeight < 1 || newWidth < 1) {
            Log.e("MGLSurface", String.format("Impossible resolution : %dx%d", newWidth, newHeight));
            return;
        }
        if(mSurface == null){
            Log.w("MGLSurface", "Attempt to refresh size on null surface");
            return;
        }
        boolean changed = windowWidth != newWidth || windowHeight != newHeight
                || Math.abs(mLastAppliedResolution - LauncherPreferences.PREF_SCALE_FACTOR) > 0.0001f;
        windowWidth = newWidth;
        windowHeight = newHeight;
        mLastAppliedResolution = LauncherPreferences.PREF_SCALE_FACTOR;
        if(useSurfaceView){
            SurfaceView view = (SurfaceView) mSurface;
            if(view.getHolder() != null){
                view.getHolder().setFixedSize(windowWidth, windowHeight);
            }
        }else{
            TextureView view = (TextureView)mSurface;
            if(view.getSurfaceTexture() != null){
                view.getSurfaceTexture().setDefaultBufferSize(windowWidth, windowHeight);
            }
        }

        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);
        if (changed && mResolutionChangedListener != null) mResolutionChangedListener.run();

    }

    private float chooseSupportedRefreshRate() {
        Display display = getDisplay();
        if (display == null) return 0f;
        Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) return display.getRefreshRate();
        java.util.TreeSet<Float> supported = new java.util.TreeSet<>();
        for (Display.Mode mode : modes) {
            if (mode != null && mode.getRefreshRate() > 0f) supported.add(mode.getRefreshRate());
        }
        if (supported.isEmpty()) return display.getRefreshRate();

        float desired = supported.last(); // Unlimited/VSync path: highest actually supported rate.
        // The surface is created before runCraft applies options.txt. Reading only
        // the old game cap here could request 60 Hz even after selecting Unlimited.
        String pacingMode = LauncherPreferences.DEFAULT_PREF.getString("extremeFpsMode", "unlimited");
        String maxFps = "unlimited".equals(pacingMode) ? "260"
                : "manual".equals(pacingMode)
                ? String.valueOf(LauncherPreferences.DEFAULT_PREF.getInt("extremeManualFps", 120))
                : MCOptionUtils.get("maxFps");
        try {
            int configured = Integer.parseInt(maxFps);
            // Minecraft's 260 slider value represents Unlimited. A lower value
            // is a target/cap selected by the user; request the smallest panel
            // rate that can present it without asking for an unsupported value.
            if (configured > 0 && configured < 260) desired = configured;
        } catch (Throwable ignored) {}

        for (Float rate : supported) if (rate + 0.01f >= desired) return rate;
        return supported.last();
    }

    private void applySurfaceFrameRate(Surface surface) {
        if (surface == null || !surface.isValid() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        float supportedRate = chooseSupportedRefreshRate();
        if (supportedRate <= 0f) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(supportedRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            } else {
                surface.setFrameRate(supportedRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            }
            Log.i(TAG, "Game surface requested supported refresh rate: " + supportedRate + " Hz");
        } catch (Throwable error) {
            Log.w(TAG, "Unable to set game surface frame rate", error);
        }
    }

    private void clearSurfaceFrameRate(Surface surface) {
        if (surface == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            } else {
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            }
        } catch (Throwable ignored) {}
    }

    private void realStart(Surface surface){
        // Initial size set. Request immedate refresh, otherwise the initial width and height for the game
        // may be broken/unknown.
        refreshSize(true);
        // Load options only to derive a display-rate hint and required window integration.
        // This never changes Minecraft's FPS limit or VSync choice.
        MCOptionUtils.load();
        applySurfaceFrameRate(surface);
        MCOptionUtils.set("fullscreen", "off");
        MCOptionUtils.set("overrideWidth", String.valueOf(windowWidth));
        MCOptionUtils.set("overrideHeight", String.valueOf(windowHeight));
        MCOptionUtils.save();
        getMcScale();

        JREUtils.setupBridgeWindow(surface);

        new Thread(() -> {
            try {
                // Wait until the listener is attached
                synchronized(mSurfaceReadyListenerLock) {
                    if(mSurfaceReadyListener == null) mSurfaceReadyListenerLock.wait();
                }

                mSurfaceReadyListener.isReady();
            } catch (Throwable e) {
                Tools.showError(getContext(), e, true);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        post(()->updateGrabState(isGrabbing));
    }

    private TouchEventProcessor pickEventProcessor(boolean isGrabbing) {
        return isGrabbing ? mIngameProcessor : mInGUIProcessor;
    }

    private void updateGrabState(boolean isGrabbing) {
        TouchEventProcessor desiredProcessor = pickEventProcessor(isGrabbing);
        if (mLastGrabState != isGrabbing || mCurrentTouchProcessor != desiredProcessor) {
            mCurrentTouchProcessor.cancelPendingActions();
            mCurrentTouchProcessor = desiredProcessor;
            mLastGrabState = isGrabbing;
        }
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setCursorVisible(!isGrabbing);
        if (!isGrabbing) {
            net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.updateCursorFrame();
        }
    }

    private void releaseGamepadHandler() {
        post(() -> {
            if (mGamepadHandler instanceof Gamepad) ((Gamepad) mGamepadHandler).removeSelf();
            mGamepadHandler = null;
            mActiveGamepadDeviceId = -1;
        });
    }

    public void onHostPause() {
        mHostResumed = false;
        stopResolutionTicker();
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setHostResumed(false);
        if (mGamepadHandler instanceof Gamepad) ((Gamepad) mGamepadHandler).setPaused(true);
    }

    public void onHostResume() {
        mHostResumed = true;
        startResolutionTicker();
        if (mGamepadHandler instanceof Gamepad) ((Gamepad) mGamepadHandler).setPaused(false);
    }

    public void releaseHostResources() {
        stopResolutionTicker();
        mResolutionReleased = true;
        mResolutionChangedListener = null;
        if (mResolutionPreferences != null) {
            mResolutionPreferences.unregisterOnSharedPreferenceChangeListener(mResolutionPreferenceListener);
            mResolutionPreferences = null;
        }
        clearSurfaceFrameRate(mNativeSurface);
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setSurfaceAttached(false);
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setCursorVisible(false);
        releaseGamepadHandler();
        if (mInputListenerRegistered && mAndroidInputManager != null) {
            mAndroidInputManager.unregisterInputDeviceListener(mInputDeviceListener);
            mInputListenerRegistered = false;
        }
    }

    @Override
    public void onDirectGamepadEnabled() {
        releaseGamepadHandler(); // Recreate in the newly selected mode on the next event.
    }

    @Override
    protected void onDetachedFromWindow() {
        stopResolutionTicker();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mHostResumed && mResolutionSurfaceReady) startResolutionTicker();
    }

    /** A small interface called when the listener is ready for the first time */
    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(SurfaceReadyListener listener){
        synchronized (mSurfaceReadyListenerLock) {
            mSurfaceReadyListener = listener;
            mSurfaceReadyListenerLock.notifyAll();
        }
    }
}
