/*
 * Copyright 2013 Dolphin Emulator Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.vita3k.emulator.overlay;

import org.vita3k.emulator.R;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import java.util.Collections;
import android.view.View.OnTouchListener;

import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.HashSet;
import java.util.Set;

/**
 * Draws the interactive input overlay on top of the
 * {@link SurfaceView} that is rendering emulation.
 */
public final class InputOverlay extends SurfaceView implements OnTouchListener
{
  // mirror what is in controller_dialog.cpp
  public final static int OVERLAY_MASK_BASIC = 1;
  public final static int OVERLAY_MASK_L2R2 = 2;
  public final static int OVERLAY_MASK_L3R3 = 4;
  public final static int OVERLAY_MASK_TOUCH_SCREEN_SWITCH = 8;
  public final static int OVERLAY_MASK_HIDE_TOGGLE = 16;
  private final static int OVERLAY_MASK_UTILITY =
          OVERLAY_MASK_TOUCH_SCREEN_SWITCH | OVERLAY_MASK_HIDE_TOGGLE;

  // Seconds of inactivity before the overlay auto-hides (0 = never).
  // Pushed from OverlayConfig.hideOverlayTimeSeconds via setOverlayConfig().
  private int mHideOverlayTimeSeconds = 10;

  // Adaptive overlay haptics, ported from PPSSPP's System_Vibrate(HAPTIC_VIRTUAL_KEY).
  // A global debounce stops a second finger from cancelling an in-flight rumble, and
  // rapid consecutive hits use a softer, longer "rolling" pulse instead of a hard hit
  // so the motor does not produce a granular / clacky feel.
  private final static long HAPTIC_DEBOUNCE_MS = 35;
  private final static long HAPTIC_RAPID_WINDOW_MS = 85;
  private static long sLastVibrateTime = 0;

  private final Set<InputOverlayDrawableButton> overlayButtons = new HashSet<>();
  private final Set<InputOverlayDrawableDpad> overlayDpads = new HashSet<>();
  private final Set<InputOverlayDrawableJoystick> overlayJoysticks = new HashSet<>();
  private final Set<InputOverlayDrawableTatacon> overlayTatacons = new HashSet<>();
  private final Runnable mHideOverlayTicker = new Runnable() {
    @Override
    public void run() {
      tick();
      postDelayed(this, 1000);
    }
  };

  private int mOverlayMask = 0;
  private boolean mIsInEditMode = false;
  public enum EditMode { MOVE, RESIZE }
  private EditMode mEditMode = EditMode.MOVE;
  private boolean mAutoHideEnabled = true;
  private boolean mAllowVirtualController = true;
  private boolean mControllerAttached = false;
  private OverlayConfig mOverlayConfig;
  private InputOverlayDrawableButton mButtonBeingConfigured;
  private InputOverlayDrawableDpad mDpadBeingConfigured;
  private InputOverlayDrawableJoystick mJoystickBeingConfigured;
  private InputOverlayDrawableTatacon mTataconBeingConfigured;
  private float mScale = 1.0f;
  private int mOpacity = 100;
  private OverlayLayout mLayout;
  private OverlayLayout mPortraitLayout;
  private OverlayLayout mLandscapeLayout;
  private int mStableWidth = 0;
  private int mStableHeight = 0;
  private int mDeviceNativeRotation = 0; // Surface rotation: 0..3 (0/2 = portrait)
  // Screen orientation setting (0=Auto, 1=Portrait, 2=Landscape).
  // Pushed from EmulatorConfig.screenOrientation via Emulator.applyScreenOrientation()
  // and via the Settings editor dialog. Kept separate from OverlayConfig because
  // OverlayConfig.screenOrientation is never persisted.
  private int mScreenOrientation = 0;

  private OverlayLayout getCurrentLayout() {
      return isPortraitMode() ? mPortraitLayout : mLandscapeLayout;
  }

  private boolean isPortraitMode() {
      // Forced portrait, forced landscape, or Auto (follow device rotation)
      if (mScreenOrientation == 1)
          return true;
      if (mScreenOrientation == 2)
          return false;
      return isDevicePortrait();
  }

  private boolean isDevicePortrait() {
      // Accepts Surface rotation constants (0=ROTATION_0, 1=ROTATION_90,
      // 2=ROTATION_180, 3=ROTATION_270) as passed by getDisplay().getRotation(),
      // or degrees (0/90/180/270). ROTATION_0 and ROTATION_180 are portrait.
      int r = mDeviceNativeRotation;
      if (r >= 0 && r <= 3)
          return r == 0 || r == 2;
      return r == 0 || r == 180;
  }

  public void setDeviceNativeRotation(int rotation) {
      if (mDeviceNativeRotation != rotation) {
          mDeviceNativeRotation = rotation;
          refreshControls();
      }
  }

  public void setScreenOrientation(int orientation) {
      int resolved = Math.max(0, Math.min(2, orientation));
      if (mScreenOrientation != resolved) {
          mScreenOrientation = resolved;
          refreshControls();
      }
  }

  // last Time the screen was touched
  private long mlastTouchTime;
  // is the overlay hidden because we didn't used it for long enough ?
  private boolean mShowingOverlay = true;
  // hide overlay manually while keeping the touch utility buttons available
  private boolean mHideOverlayButtons = false;

  /**
   * Resizes a {@link Bitmap} by a given scale factor
   *
   * @param context The current {@link Context}
   * @param bitmap  The {@link Bitmap} to scale.
   * @param scale   The scale factor for the bitmap.
   * @return The scaled {@link Bitmap}
   */
  public static Bitmap resizeBitmap(Context context, Bitmap bitmap, float scale)
  {
    DisplayMetrics dm = context.getResources().getDisplayMetrics();
    int minDimension = Math.min(dm.widthPixels, dm.heightPixels);
    int maxBitmapDimension = Math.max(bitmap.getWidth(), bitmap.getHeight());
    float bitmapScale = scale * minDimension / maxBitmapDimension;

    return Bitmap.createScaledBitmap(bitmap,
            (int) (bitmap.getWidth() * bitmapScale),
            (int) (bitmap.getHeight() * bitmapScale),
            true);
  }

  /**
   * Constructor
   *
   * @param context The current {@link Context}.
   * @param attrs   {@link AttributeSet} for parsing XML attributes.
   */
  public InputOverlay(Context context/*, AttributeSet attrs*/)
  {
    super(context/*, attrs*/);

    // Force draw
    setWillNotDraw(false);

    // NOTE: Do NOT set an OnTouchListener / setClickable(true) here.
    // The overlay is drawn on top of EmuSurface (the SDL surface). If this view is
    // clickable, its fallback onTouchEvent() consumes every ACTION_DOWN, so touches
    // on empty areas never reach EmuSurface and SDL never receives SDL_EVENT_FINGER_*.
    // That kills the emulated PS Vita front touchscreen.
    // Touch is delivered through EmuSurface.onTouch(), which calls mOverlay.onTouch()
    // and forwards non-overlay pointers to SDL.

    // Request focus for the overlay so it has priority on presses.
    requestFocus();

    applyResolvedState(OverlayStore.loadGlobalState(getContext()), false);

    refreshControls();
    resetHideTimer();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if ((w != oldw || h != oldh) && w > 0 && h > 0) {
      refreshControls();
    }
  }

  private void startHideTimer() {
    removeCallbacks(mHideOverlayTicker);
    postDelayed(mHideOverlayTicker, 1000);
  }

  private void stopHideTimer() {
    removeCallbacks(mHideOverlayTicker);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    startHideTimer();
    updateVirtualControllerState();
  }

  @Override
  protected void onDetachedFromWindow() {
    stopHideTimer();
    releaseAllInputs();
    detachVirtualController();
    super.onDetachedFromWindow();
  }

  private void resetHideTimer(){
    if(!mShowingOverlay)
      invalidate();

    mShowingOverlay = true;
    mlastTouchTime = System.currentTimeMillis();
  }

  public void tick(){
    if(mOverlayMask == 0 || !mShowingOverlay || isInEditMode() || !mAutoHideEnabled)
      return;

    // "Never" selected in settings -> keep the overlay always visible.
    if (mHideOverlayTimeSeconds <= 0)
      return;

    long current_time = System.currentTimeMillis();
    if (current_time - mlastTouchTime >= mHideOverlayTimeSeconds * 1000L) {
      mShowingOverlay = false;
      invalidate();
    }
  }

  public void setState(int overlay_mask){
    if(mOverlayMask != overlay_mask){
      mOverlayMask = overlay_mask;
      invalidate();
    }

    resetHideTimer();
    updateVirtualControllerState();
  }

  public void setOverlayConfig(OverlayConfig config) {
    if (config == null) return;
    mOverlayConfig = config;

    int hideTime = config.hideOverlayTimeSeconds;
    // When the user changes the hide delay (or turns it off), re-show the overlay so
    // the new setting takes effect immediately instead of on the next touch.
    if (hideTime != mHideOverlayTimeSeconds) {
      mHideOverlayTimeSeconds = hideTime;
      resetHideTimer();
    }

    invalidate();
  }


  public int getOverlayMask() {
    return mOverlayMask;
  }

  public void releaseAllInputs() {
    for (InputOverlayDrawableButton button : overlayButtons) {
      if ((button.getRole() & OVERLAY_MASK_UTILITY) == 0) {
        setButton(button.getControl(), false);
      }
      button.setPressedState(false);
      button.setTrackId(-1);
    }

    for (InputOverlayDrawableDpad dpad : overlayDpads) {
      for (int i = 0; i < 4; i++) {
        setButton(dpad.getControl(i), false);
      }
      dpad.setState(InputOverlayDrawableDpad.STATE_DEFAULT);
      dpad.setTrackId(-1);
    }

    for (InputOverlayDrawableJoystick joystick : overlayJoysticks) {
      setAxis(joystick.getXControl(), (short)0);
      setAxis(joystick.getYControl(), (short)0);
      joystick.release();
    }

    for (InputOverlayDrawableTatacon tatacon : overlayTatacons) {
      if (tatacon.getActiveZone() != InputOverlayDrawableTatacon.ZONE_NONE)
        setButton(tatacon.getControl(tatacon.getActiveZone()), false);
      tatacon.setActiveZone(InputOverlayDrawableTatacon.ZONE_NONE);
      tatacon.setTrackId(-1);
    }

    invalidate();
  }

  public void draw(Canvas canvas)
  {
    super.draw(canvas);

    if (mOverlayMask == 0 || !mShowingOverlay)
      return;

    boolean inEditMode = isInEditMode();

    for (InputOverlayDrawableButton button : overlayButtons)
    {
      int role = button.getRole();
	  
	  boolean visible = mOverlayConfig.isButtonVisible(button.getLegacyId());
	  // In edit mode, only show enabled buttons so user isn't confused by hidden ones
	  if (!visible) continue;

      if (mHideOverlayButtons) {
        if ((role & OVERLAY_MASK_UTILITY) == 0 || (role & mOverlayMask) == 0)
          continue;
      } else if ((role & mOverlayMask) == 0) {
        continue;
      }

      button.setEditModeAlphaMultiplier(1.0f);
      button.draw(canvas);
    }

    if (!mHideOverlayButtons)
    {
      for (InputOverlayDrawableDpad dpad : overlayDpads)
      {
        boolean visible = mOverlayConfig.isButtonVisible(dpad.getLegacyId());
        if (!visible) continue;
        
        dpad.setEditModeAlphaMultiplier(1.0f);
        dpad.draw(canvas);
      }

      for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
      {
        boolean visible = mOverlayConfig.isButtonVisible(joystick.getLegacyId());
        if (!visible) continue;
        
        joystick.setEditModeAlphaMultiplier(1.0f);
        joystick.draw(canvas);
      }
	  
      for (InputOverlayDrawableTatacon tatacon : overlayTatacons)
      {
        boolean visible = mOverlayConfig.isButtonVisible(tatacon.getLegacyId());
        if (!visible) continue;
        
        tatacon.setEditModeAlphaMultiplier(1.0f);
        tatacon.draw(canvas);
      }
    }
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    if (mOverlayMask == 0)
      return false;

    if(!mAllowVirtualController && !isInEditMode())
      return false;

    resetHideTimer();

    if (isInEditMode())
      return onTouchWhileEditing(event);

    int action = event.getActionMasked();
    boolean firstPointer = action != MotionEvent.ACTION_POINTER_DOWN &&
            action != MotionEvent.ACTION_POINTER_UP;
    int pointerIndex = firstPointer ? 0 : event.getActionIndex();
    // track if the overlay is concerned this this action
    boolean concerned = false;



    for (InputOverlayDrawableButton button : overlayButtons)
    {
      int buttonRole = button.getRole();
      int legacyId = button.getLegacyId();

      if (!mOverlayConfig.isButtonVisible(legacyId)) continue;

      if (mHideOverlayButtons &&
              (((buttonRole & OVERLAY_MASK_UTILITY) == 0) || (buttonRole & mOverlayMask) == 0))
          continue;
      else if ((buttonRole & mOverlayMask) == 0)
          continue;

      // Determine the button state to apply based on the MotionEvent action flag.
      switch (action)
      {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
          // If a pointer enters the bounds of a button, press that button.
          if (button.getBounds()
                  .contains((int) event.getX(pointerIndex), (int) event.getY(pointerIndex)))
          {
            button.setPressedState(true);
            button.setTrackId(event.getPointerId(pointerIndex));
            concerned = true;

            if (legacyId == ButtonType.BUTTON_TOUCH_SWITCH)
              setTouchState(button.getTouchSwitchState());
            else if (legacyId == ButtonType.BUTTON_TOUCH_HIDE)
              mHideOverlayButtons = !mHideOverlayButtons;
            else
              setButton(button.getControl(), true);

            triggerHapticFeedback();
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          // If a pointer ends, release the button it was pressing.
          if (button.getTrackId() == event.getPointerId(pointerIndex))
          {
            button.setPressedState(false);

            if (legacyId != ButtonType.BUTTON_TOUCH_SWITCH &&
                    legacyId != ButtonType.BUTTON_TOUCH_HIDE)
              setButton(button.getControl(), false);

            button.setTrackId(-1);
            concerned = true;
          }
          break;
      }
    }

    if (!mHideOverlayButtons)
    {
      for (InputOverlayDrawableDpad dpad : overlayDpads)
      {
	if (!mOverlayConfig.isButtonVisible(dpad.getLegacyId())) continue;

        // Determine the button state to apply based on the MotionEvent action flag.
        switch (event.getAction() & MotionEvent.ACTION_MASK)
        {
          case MotionEvent.ACTION_DOWN:
          case MotionEvent.ACTION_POINTER_DOWN:
            // If a pointer enters the bounds of a button, press that button.
            if (dpad.getBounds()
                    .contains((int) event.getX(pointerIndex), (int) event.getY(pointerIndex)))
            {
              dpad.setTrackId(event.getPointerId(pointerIndex));
              concerned = true;
              triggerHapticFeedback(); // DPad press —— vibrate only when the pad is actually hit
            }

          case MotionEvent.ACTION_MOVE:
            if (dpad.getTrackId() == event.getPointerId(pointerIndex))
            {
              concerned = true;
              // Up, Down, Left, Right
              boolean[] dpadPressed = {false, false, false, false};

              if (dpad.getBounds().top + (dpad.getHeight() / 3) > (int) event.getY(pointerIndex))
                dpadPressed[0] = true;
              if (dpad.getBounds().bottom - (dpad.getHeight() / 3) < (int) event.getY(pointerIndex))
                dpadPressed[1] = true;
              if (dpad.getBounds().left + (dpad.getWidth() / 3) > (int) event.getX(pointerIndex))
                dpadPressed[2] = true;
              if (dpad.getBounds().right - (dpad.getWidth() / 3) < (int) event.getX(pointerIndex))
                dpadPressed[3] = true;

              // Press buttons
              for (int i = 0; i < dpadPressed.length; i++)
              {
                if (dpadPressed[i])
                  setButton(dpad.getControl(i), true);
              }
              setDpadState(dpad, dpadPressed[0], dpadPressed[1], dpadPressed[2], dpadPressed[3]);
            }
            break;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_POINTER_UP:
            // If a pointer ends, release the buttons.
            if (dpad.getTrackId() == event.getPointerId(pointerIndex))
            {
              concerned = true;
              for (int i = 0; i < 4; i++)
              {
                dpad.setState(InputOverlayDrawableDpad.STATE_DEFAULT);
                setButton(dpad.getControl(i), false);
              }
              dpad.setTrackId(-1);
            }
            break;
        }
      }

      for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
      {
	if (!mOverlayConfig.isButtonVisible(joystick.getLegacyId())) continue;

        boolean joystickConcerned = joystick.TrackEvent(event);
        if (joystickConcerned)
        {
          concerned = true;
          // Only the initial touch should buzz; TrackEvent() also returns true for
          // every MOVE/UP while dragging, which would otherwise cause a continuous buzz.
          if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
            triggerHapticFeedback(); // Joystick first touch

          int joyX = Math.round(joystick.getX() * (1 << 15));
          joyX = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, joyX));
          int joyY = Math.round(joystick.getY() * (1 << 15));
          joyY = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, joyY));
          setAxis(joystick.getXControl(), (short)joyX);
          setAxis(joystick.getYControl(), (short)joyY);
        }
      }
    }

    for (InputOverlayDrawableTatacon tatacon : overlayTatacons)
    {
      // When the overlay is hidden via the hide toggle, the tatacon must be disabled
      // too (not just hidden visually), matching every other control.
      if (mHideOverlayButtons) continue;
      if (!mOverlayConfig.isButtonVisible(tatacon.getLegacyId())) continue;

      switch (action)
      {
        case MotionEvent.ACTION_DOWN:
	case MotionEvent.ACTION_POINTER_DOWN:
	  if (tatacon.getBounds().contains((int) event.getX(pointerIndex), (int) event.getY(pointerIndex))
		  && !isPointerOwnedByButtonDpadOrJoystick(event.getPointerId(pointerIndex)))
	  {
		tatacon.setTrackId(event.getPointerId(pointerIndex));
		concerned = true;
	  }
        // 贯穿到 MOVE
	case MotionEvent.ACTION_MOVE:
	  if (tatacon.getTrackId() == event.getPointerId(pointerIndex))
	  {
            concerned = true;
            int zone = InputOverlayDrawableTatacon.zoneForTouch(tatacon.getBounds(),
                    event.getX(pointerIndex), event.getY(pointerIndex));
            int prevZone = tatacon.getActiveZone();
            if (zone != prevZone)
            {
              if (prevZone != InputOverlayDrawableTatacon.ZONE_NONE)
                setButton(tatacon.getControl(prevZone), false);
              if (zone != InputOverlayDrawableTatacon.ZONE_NONE)
              {
                setButton(tatacon.getControl(zone), true);
                triggerHapticFeedback(); // Taiko drum hit
              }
              tatacon.setActiveZone(zone);
            }
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          if (tatacon.getTrackId() == event.getPointerId(pointerIndex))
          {
            concerned = true;
            if (tatacon.getActiveZone() != InputOverlayDrawableTatacon.ZONE_NONE)
              setButton(tatacon.getControl(tatacon.getActiveZone()), false);
            tatacon.setActiveZone(InputOverlayDrawableTatacon.ZONE_NONE);
            tatacon.setTrackId(-1);
          }
          break;
      }
    }

    if (concerned)
      invalidate();

    return concerned;
  }

  private void triggerHapticFeedback() {
    if (mOverlayConfig == null || mOverlayConfig.hapticAmplitude <= 0)
      return;

    Context ctx = getContext();
    if (ctx == null)
      return;

    Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    if (vib == null || !vib.hasVibrator())
      return;

    final long now = SystemClock.uptimeMillis();
    final long delta = now - sLastVibrateTime;

    // CRITICAL FIX FOR DUAL-HAND HITS: prevent a second finger from cancelling the
    // ongoing rumble within the debounce window.
    if (delta < HAPTIC_DEBOUNCE_MS)
      return;
    sLastVibrateTime = now;

    final int strength = mOverlayConfig.hapticAmplitude; // 1..255, 0 handled above
    final float norm = strength / 255f;

    final int duration;
    final int amplitude;
    if (delta < HAPTIC_RAPID_WINDOW_MS) {
      // SMOOTH ROLLING MODE: rapid hits get a soft, longer low-amplitude pulse so the
      // motor keeps moving smoothly instead of stopping and restarting with a clack.
      duration = 50 + Math.round(norm * 15f);                                   // 50..65 ms
      amplitude = Math.max(1, Math.round(strength * 0.33f));                    // ~1/3 power
    } else {
      // SINGLE TAP MODE: full-power short impact for a deep, solid tap.
      duration = 45 + Math.round(norm * 40f);                                   // 45..85 ms
      amplitude = strength;
    }

    vib.vibrate(VibrationEffect.createOneShot(duration, amplitude));
  }

  public boolean onTouchWhileEditing(MotionEvent event)
  {
    int action = event.getAction() & MotionEvent.ACTION_MASK;
    boolean intersect = false;

    // Get touch coordinates - use first pointer for single-touch (most common case)
    // For multi-touch, we'd need to iterate all pointers
    int fingerPositionX = (int) event.getX();
    int fingerPositionY = (int) event.getY();

    // If a control is already being configured, only handle that one
    if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
        if (mButtonBeingConfigured != null) {
            if (mEditMode == EditMode.MOVE) {
                mButtonBeingConfigured.onConfigureTouch(event);
            } else {
                mButtonBeingConfigured.onConfigureResize(event);
            }
            if (action != MotionEvent.ACTION_MOVE) {
                saveControlPosition(mButtonBeingConfigured.getLegacyId(),
                        mButtonBeingConfigured.getBounds(),
                        mButtonBeingConfigured.getFinalScale());
                mButtonBeingConfigured = null;
            }
            invalidate();
            return true;
        }
        if (mDpadBeingConfigured != null) {
            if (mEditMode == EditMode.MOVE) {
                mDpadBeingConfigured.onConfigureTouch(event);
            } else {
                mDpadBeingConfigured.onConfigureResize(event);
            }
            if (action != MotionEvent.ACTION_MOVE) {
                saveControlPosition(mDpadBeingConfigured.getLegacyId(),
                        mDpadBeingConfigured.getBounds(),
                        mDpadBeingConfigured.getFinalScale());
                mDpadBeingConfigured = null;
            }
            invalidate();
            return true;
        }
        if (mJoystickBeingConfigured != null) {
            if (mEditMode == EditMode.MOVE) {
                mJoystickBeingConfigured.onConfigureTouch(event);
            } else {
                mJoystickBeingConfigured.onConfigureResize(event);
            }
            if (action != MotionEvent.ACTION_MOVE) {
                saveControlPosition(mJoystickBeingConfigured.getLegacyId(),
                        mJoystickBeingConfigured.getBounds(),
                        mJoystickBeingConfigured.getFinalScale());
                mJoystickBeingConfigured = null;
            }
            invalidate();
            return true;
        }
        if (mTataconBeingConfigured != null) {
            if (mEditMode == EditMode.MOVE) {
                mTataconBeingConfigured.onConfigureTouch(event);
            } else {
                mTataconBeingConfigured.onConfigureResize(event);
            }
            if (action != MotionEvent.ACTION_MOVE) {
                saveControlPosition(mTataconBeingConfigured.getLegacyId(),
                        mTataconBeingConfigured.getBounds(),
                        mTataconBeingConfigured.getFinalScale());
                mTataconBeingConfigured = null;
            }
            invalidate();
            return true;
        }
        // No control being configured, fall through to check for new selection on DOWN
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) {
            return false;
        }
    }

    // ACTION_DOWN / ACTION_POINTER_DOWN: pick the first control that contains the touch point
    // Priority: Button > Dpad > Joystick > Tatacon (order matters for overlapping controls)
    for (InputOverlayDrawableButton button : overlayButtons)
    {
        if (button.getBounds().contains(fingerPositionX, fingerPositionY))
        {
            mButtonBeingConfigured = button;
            mButtonBeingConfigured.onConfigureTouch(event);
            mButtonBeingConfigured.onConfigureResize(event);
            intersect = true;
            break;
        }
    }
    if (intersect) return true;

    for (InputOverlayDrawableDpad dpad : overlayDpads)
    {
        if (dpad.getBounds().contains(fingerPositionX, fingerPositionY))
        {
            mDpadBeingConfigured = dpad;
            mDpadBeingConfigured.onConfigureTouch(event);
            mDpadBeingConfigured.onConfigureResize(event);
            intersect = true;
            break;
        }
    }
    if (intersect) return true;

    for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
    {
        if (joystick.getBounds().contains(fingerPositionX, fingerPositionY))
        {
            mJoystickBeingConfigured = joystick;
            mJoystickBeingConfigured.onConfigureTouch(event);
            mJoystickBeingConfigured.onConfigureResize(event);
            intersect = true;
            break;
        }
    }
    if (intersect) return true;

    for (InputOverlayDrawableTatacon tatacon : overlayTatacons)
    {
        if (tatacon.getBounds().contains(fingerPositionX, fingerPositionY))
        {
            mTataconBeingConfigured = tatacon;
            mTataconBeingConfigured.onConfigureTouch(event);
            mTataconBeingConfigured.onConfigureResize(event);
            intersect = true;
            break;
        }
    }

    return intersect;
  }

  private void setDpadState(InputOverlayDrawableDpad dpad, boolean up, boolean down, boolean left,
          boolean right)
  {
    if (up)
    {
      if (left)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_UP_LEFT);
      else if (right)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_UP_RIGHT);
      else
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_UP);
    }
    else if (down)
    {
      if (left)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_DOWN_LEFT);
      else if (right)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_DOWN_RIGHT);
      else
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_DOWN);
    }
    else if (left)
    {
      dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_LEFT);
    }
    else if (right)
    {
      dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_RIGHT);
    }
  }


  private void addVitaOverlayControls(LayoutBounds layoutBounds, OverlayLayout layout, boolean isPortrait)
  {
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_cross,
              R.drawable.button_cross_pressed, ButtonType.BUTTON_CROSS, ControlId.a,
              OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_circle,
              R.drawable.button_circle_pressed, ButtonType.BUTTON_CIRCLE, ControlId.b,
              OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_square,
              R.drawable.button_square_pressed, ButtonType.BUTTON_SQUARE, ControlId.x,
              OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_triangle,
              R.drawable.button_triangle_pressed, ButtonType.BUTTON_TRIANGLE, ControlId.y,
              OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_start,
              R.drawable.button_start_pressed, ButtonType.BUTTON_START,
              ControlId.start, OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_ps,
            R.drawable.button_ps_pressed, ButtonType.BUTTON_PS,
            ControlId.guide, OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_select,
              R.drawable.button_select_pressed, ButtonType.BUTTON_SELECT,
              ControlId.select, OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_l,
              R.drawable.button_l_pressed, ButtonType.TRIGGER_L,
              ControlId.l1, OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_r,
              R.drawable.button_r_pressed, ButtonType.TRIGGER_R,
              ControlId.r1, OVERLAY_MASK_BASIC, layout, layoutBounds, mScale, mOpacity, isPortrait));

    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_l2,
            R.drawable.button_l2_pressed, ButtonType.TRIGGER_L2,
            ControlId.l2, OVERLAY_MASK_L2R2, layout, layoutBounds, mScale, mOpacity, isPortrait));

    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_r2,
            R.drawable.button_r2_pressed, ButtonType.TRIGGER_R2,
            ControlId.r2, OVERLAY_MASK_L2R2, layout, layoutBounds, mScale, mOpacity, isPortrait));

    // L3 and R3
    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_l3,
            R.drawable.button_l3_pressed, ButtonType.TRIGGER_L3,
            ControlId.l3, OVERLAY_MASK_L3R3, layout, layoutBounds, mScale, mOpacity, isPortrait));

    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_r3,
            R.drawable.button_r3_pressed, ButtonType.TRIGGER_R3,
            ControlId.r3, OVERLAY_MASK_L3R3, layout, layoutBounds, mScale, mOpacity, isPortrait));

    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_touch_f,
            R.drawable.button_touch_b, ButtonType.BUTTON_TOUCH_SWITCH,
            ControlId.touch, OVERLAY_MASK_TOUCH_SCREEN_SWITCH, layout, layoutBounds, mScale, mOpacity, isPortrait));

    // show hide button
    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_hide,
            R.drawable.button_hide_pressed, ButtonType.BUTTON_TOUCH_HIDE,
            ControlId.touch, OVERLAY_MASK_HIDE_TOGGLE, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayDpads.add(initializeOverlayDpad(getContext(), R.drawable.dpad_idle,
              R.drawable.dpad_up,
              R.drawable.dpad_up_left,
              ButtonType.DPAD_UP, ControlId.dup, ControlId.ddown,
              ControlId.dleft, ControlId.dright, layout, layoutBounds, mScale, mOpacity, isPortrait));

      overlayJoysticks.add(initializeOverlayJoystick(getContext(), R.drawable.joystick_range,
              R.drawable.joystick, R.drawable.joystick_pressed,
              ButtonType.STICK_LEFT, ControlId.axis_left_x,
              ControlId.axis_left_y, layout, layoutBounds, mScale, mOpacity, isPortrait));
      overlayJoysticks.add(initializeOverlayJoystick(getContext(), R.drawable.joystick_range,
              R.drawable.joystick, R.drawable.joystick_pressed,
              ButtonType.STICK_RIGHT, ControlId.axis_right_x,
              ControlId.axis_right_y, layout, layoutBounds, mScale, mOpacity, isPortrait));
      overlayTatacons.add(initializeOverlayTatacon(getContext(), R.drawable.tatacon,
              ButtonType.TATACON,
              ControlId.dup, ControlId.ddown, ControlId.b, ControlId.a,
              ControlId.l1, ControlId.r1,
              layout, layoutBounds, mScale, mOpacity, isPortrait));
  }

  public void refreshControls()
  {
    // Remove all the overlay buttons from the HashSet.
    overlayButtons.clear();
    overlayDpads.clear();
    overlayJoysticks.clear();
	overlayTatacons.clear();

    LayoutBounds layoutBounds = resolveLayoutBounds();
    addVitaOverlayControls(layoutBounds, getCurrentLayout(), isPortraitMode());

    invalidate();
  }

  public void resetButtonPlacement()
  {
    setLayout(OverlayStore.defaultLayout(getContext()), true);
  }

  public void setScale(float scale){
    if (scale != mScale){
      mScale = scale;
      refreshControls();
    }
  }

  public void setOpacity(int opacity){
    if (opacity != mOpacity){
      mOpacity = opacity;
      refreshControls();
    }
  }

  private void saveControlPosition(int buttonType, Rect bounds, float scale) {
    int centerX = bounds.left + bounds.width() / 2;
    int centerY = bounds.top + bounds.height() / 2;
    LayoutBounds layoutBounds = resolveLayoutBounds();
    float normalizedX = layoutBounds.width > 0 ? (float) centerX / layoutBounds.width : 0f;
    float normalizedY = layoutBounds.height > 0 ? (float) centerY / layoutBounds.height : 0f;
    android.util.Log.d("TataconDebug", "SAVE buttonType=" + buttonType + " cx=" + centerX + " cy=" + centerY
            + " layoutW=" + layoutBounds.width + " layoutH=" + layoutBounds.height
            + " normX=" + normalizedX + " normY=" + normalizedY + " scale=" + scale);
    OverlayLayout currentLayout = getCurrentLayout();
    OverlayLayout updatedLayout = currentLayout.withPosition(buttonType, normalizedX, normalizedY, scale, isPortraitMode());
    setLayout(updatedLayout, false);
  }

  /**
   * Initializes an InputOverlayDrawableButton, given by resId, with all of the
   * parameters set for it to be properly shown on the InputOverlay.
   * <p>
   * Position and appearance are fully resolved before returning the drawable.
   *
   * @param context      The current {@link Context}.
   * @param defaultResId The resource ID of the {@link Drawable} to get the {@link Bitmap} of (Default State).
   * @param pressedResId The resource ID of the {@link Drawable} to get the {@link Bitmap} of (Pressed State).
   * @param legacyId     Legacy identifier for the button the InputOverlayDrawableButton represents.
   * @param control      Control identifier for the button the InputOverlayDrawableButton represents.
   * @return An {@link InputOverlayDrawableButton} with the correct drawing bounds set.
   */
  private static InputOverlayDrawableButton initializeOverlayButton(Context context,
          int defaultResId, int pressedResId, int legacyId, int control, int role,
          OverlayLayout layout, LayoutBounds layoutBounds, float globalScale, int globalOpacity, boolean isPortrait)
  {
    // Resources handle for fetching the initial Drawable resource.
    final Resources res = context.getResources();

    // Decide scale based on button ID and user preference
    float scale = 0.15f;

    if(legacyId == ButtonType.TRIGGER_L
            || legacyId == ButtonType.TRIGGER_R
            || legacyId == ButtonType.TRIGGER_L2
            || legacyId == ButtonType.TRIGGER_R2
            || legacyId == ButtonType.BUTTON_START
            || legacyId == ButtonType.BUTTON_SELECT)
      scale = 0.25f;
    else if(legacyId == ButtonType.BUTTON_TOUCH_SWITCH
            || legacyId == ButtonType.BUTTON_PS
            || legacyId == ButtonType.BUTTON_TOUCH_HIDE)
      scale = 0.11f;

    scale *= globalScale;

    OverlayPosition position = layout.positionFor(legacyId, isPortrait);
    if (position == null)
      position = new OverlayPosition(0f, 0f);
    scale *= position.getScale();

    // Initialize the InputOverlayDrawableButton.
    final Bitmap defaultStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, defaultResId), scale);
    final Bitmap pressedStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, pressedResId), scale);
    final InputOverlayDrawableButton overlayDrawable =
            new InputOverlayDrawableButton(res, defaultStateBitmap, pressedStateBitmap, legacyId,
                    control, role);
    overlayDrawable.setScaleAtEditStart(position.getScale());

    if (legacyId == ButtonType.BUTTON_TOUCH_SWITCH)
    {
      final Bitmap bothStateBitmap = resizeBitmap(context, BitmapFactory.decodeResource(res, R.drawable.button_touch_fb), scale);
      overlayDrawable.setThirdStateBitmap(res, bothStateBitmap);
    }

    int width = overlayDrawable.getWidth();
    int height = overlayDrawable.getHeight();

    int centerX = Math.round(position.getNormalizedX() * layoutBounds.width);
    int centerY = Math.round(position.getNormalizedY() * layoutBounds.height);
    int drawableX = centerX - width / 2;
    int drawableY = centerY - height / 2;

    // Now set the bounds for the InputOverlayDrawableButton.
    // This will dictate where on the screen (and the what the size) the InputOverlayDrawableButton will be.
    overlayDrawable.setBounds(drawableX, drawableY, drawableX + width, drawableY + height);

    // Need to set the image's position
    overlayDrawable.setPosition(drawableX, drawableY);
    overlayDrawable.setOpacity((int) (globalOpacity * 0.01 * 255));

    return overlayDrawable;
  }

  /**
   * Initializes an {@link InputOverlayDrawableDpad}
   *
   * @param context                   The current {@link Context}.
   * @param defaultResId              The {@link Bitmap} resource ID of the default sate.
   * @param pressedOneDirectionResId  The {@link Bitmap} resource ID of the pressed sate in one direction.
   * @param pressedTwoDirectionsResId The {@link Bitmap} resource ID of the pressed sate in two directions.
   * @param legacyId                  Legacy identifier for the up button.
   * @param upControl                 Control identifier for the up button.
   * @param downControl               Control identifier for the down button.
   * @param leftControl               Control identifier for the left button.
   * @param rightControl              Control identifier for the right button.
   * @return the initialized {@link InputOverlayDrawableDpad}
   */
  private static InputOverlayDrawableDpad initializeOverlayDpad(Context context,
          int defaultResId,
          int pressedOneDirectionResId,
          int pressedTwoDirectionsResId,
          int legacyId,
          int upControl,
          int downControl,
          int leftControl,
          int rightControl,
          OverlayLayout layout,
          LayoutBounds layoutBounds,
          float globalScale,
          int globalOpacity,
          boolean isPortrait)
  {
    // Resources handle for fetching the initial Drawable resource.
    final Resources res = context.getResources();

    // Decide scale based on button ID and user preference
    float scale = 0.35f;

    scale *= globalScale;

    OverlayPosition position = layout.positionFor(legacyId, isPortrait);
    if (position == null)
      position = new OverlayPosition(0f, 0f);
    scale *= position.getScale();

    // Initialize the InputOverlayDrawableDpad.
    final Bitmap defaultStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, defaultResId), scale);
    final Bitmap pressedOneDirectionStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, pressedOneDirectionResId),
                    scale);
    final Bitmap pressedTwoDirectionsStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, pressedTwoDirectionsResId),
                    scale);
    final InputOverlayDrawableDpad overlayDrawable =
            new InputOverlayDrawableDpad(res, defaultStateBitmap,
                    pressedOneDirectionStateBitmap, pressedTwoDirectionsStateBitmap,
                    legacyId, upControl, downControl, leftControl, rightControl);
    overlayDrawable.setScaleAtEditStart(position.getScale());

    int width = overlayDrawable.getWidth();
    int height = overlayDrawable.getHeight();

    int centerX = Math.round(position.getNormalizedX() * layoutBounds.width);
    int centerY = Math.round(position.getNormalizedY() * layoutBounds.height);
    int drawableX = centerX - width / 2;
    int drawableY = centerY - height / 2;

    // Now set the bounds for the InputOverlayDrawableDpad.
    // This will dictate where on the screen (and the what the size) the InputOverlayDrawableDpad will be.
    overlayDrawable.setBounds(drawableX, drawableY, drawableX + width, drawableY + height);

    // Need to set the image's position
    overlayDrawable.setPosition(drawableX, drawableY);
    overlayDrawable.setOpacity((int) (globalOpacity * 0.01 * 255));

    return overlayDrawable;
  }

  /**
   * Initializes an {@link InputOverlayDrawableJoystick}
   *
   * @param context         The current {@link Context}
   * @param resOuter        Resource ID for the outer image of the joystick (the static image that shows the circular bounds).
   * @param defaultResInner Resource ID for the default inner image of the joystick (the one you actually move around).
   * @param pressedResInner Resource ID for the pressed inner image of the joystick.
   * @param legacyId        Legacy identifier (ButtonType) for which joystick this is.
   * @param xControl        Control identifier for the X axis.
   * @param yControl        Control identifier for the Y axis.
   * @return the initialized {@link InputOverlayDrawableJoystick}.
   */
  private static InputOverlayDrawableJoystick initializeOverlayJoystick(Context context,
          int resOuter, int defaultResInner, int pressedResInner, int legacyId, int xControl,
          int yControl, OverlayLayout layout, LayoutBounds layoutBounds, float globalScale,
          int globalOpacity, boolean isPortrait)
  {
    // Resources handle for fetching the initial Drawable resource.
    final Resources res = context.getResources();

    // Decide scale based on user preference
    float scale = 0.275f;
    scale *= globalScale;

    OverlayPosition position = layout.positionFor(legacyId, isPortrait);
    if (position == null)
      position = new OverlayPosition(0f, 0f);
    scale *= position.getScale();

    // Initialize the InputOverlayDrawableJoystick.
    final Bitmap bitmapOuter =
            resizeBitmap(context, BitmapFactory.decodeResource(res, resOuter), scale);
    final Bitmap bitmapInnerDefault = BitmapFactory.decodeResource(res, defaultResInner);
    final Bitmap bitmapInnerPressed = BitmapFactory.decodeResource(res, pressedResInner);

    int width = bitmapOuter.getWidth();
    int height = bitmapOuter.getHeight();

    int centerX = Math.round(position.getNormalizedX() * layoutBounds.width);
    int centerY = Math.round(position.getNormalizedY() * layoutBounds.height);
    int drawableX = centerX - width / 2;
    int drawableY = centerY - height / 2;

    // Decide inner scale based on joystick ID
    float innerScale = 1.375f;

    // Now set the bounds for the InputOverlayDrawableJoystick.
    // This will dictate where on the screen (and the what the size) the InputOverlayDrawableJoystick will be.
    Rect outerRect = new Rect(drawableX, drawableY, drawableX + width, drawableY + height);
    Rect innerRect = new Rect(0, 0, (int) (width / innerScale), (int) (height / innerScale));

    // Send the drawableId to the joystick so it can be referenced when saving control position.
    final InputOverlayDrawableJoystick overlayDrawable =
            new InputOverlayDrawableJoystick(res, bitmapOuter, bitmapInnerDefault,
                    bitmapInnerPressed, outerRect, innerRect, legacyId, xControl, yControl);
    overlayDrawable.setScaleAtEditStart(position.getScale());

    // Need to set the image's position
    overlayDrawable.setPosition(drawableX, drawableY);
    overlayDrawable.setOpacity((int) (globalOpacity * 0.01 * 255));

    return overlayDrawable;
  }

  private static InputOverlayDrawableTatacon initializeOverlayTatacon(Context context,
          int drawableResId, int legacyId,
          int upControl, int downControl, int circleControl, int crossControl,
          int rimLeftControl, int rimRightControl,
          OverlayLayout layout, LayoutBounds layoutBounds, float globalScale, int globalOpacity, boolean isPortrait)
  {
    final Resources res = context.getResources();

    float scale = 4.0f;
    // scale *= globalScale; // tatacon don,t need this

    OverlayPosition position = layout.positionFor(legacyId, isPortrait);
    if (position == null)
      position = new OverlayPosition(1f, 1f);
    scale *= position.getScale();

    final Bitmap baseBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, drawableResId), scale);

    final InputOverlayDrawableTatacon overlayDrawable =
            new InputOverlayDrawableTatacon(res, baseBitmap, legacyId,
                    upControl, downControl, circleControl, crossControl,
                    rimLeftControl, rimRightControl);
    overlayDrawable.setScaleAtEditStart(position.getScale());

    int width = overlayDrawable.getWidth();
    int height = overlayDrawable.getHeight();

	// Load the normalized coordinates based on the center point and restore the top-left corner based on current LayoutBounds.
    // Since it uses the same semantics (center fraction) as when saving, the visual center is preserved even if the layout size changes due to resizing, scaling, or screen rotation.
    int centerX = Math.round(position.getNormalizedX() * layoutBounds.width);
    int centerY = Math.round(position.getNormalizedY() * layoutBounds.height);
    int drawableX = centerX - width / 2;
    int drawableY = centerY - height / 2;

    android.util.Log.d("TataconDebug", "LOAD normX=" + position.getNormalizedX() + " normY=" + position.getNormalizedY()
            + " layoutW=" + layoutBounds.width + " layoutH=" + layoutBounds.height
            + " centerX=" + centerX + " centerY=" + centerY
            + " finalX=" + drawableX + " finalY=" + drawableY
            + " width=" + width + " height=" + height);

    overlayDrawable.setBounds(drawableX, drawableY, drawableX + width, drawableY + height);
    overlayDrawable.setPosition(drawableX, drawableY);
    overlayDrawable.setOpacity((int) (globalOpacity * 0.01 * 255));

    return overlayDrawable;
  }

  public void setEditMode(EditMode mode)
  {
    mEditMode = mode;
  }

  public EditMode getEditMode()
  {
    return mEditMode;
  }

  public void setIsInEditMode(boolean isInEditMode)
  {
    if (isInEditMode && !mIsInEditMode)
      mEditMode = EditMode.MOVE;

    mIsInEditMode = isInEditMode;
    if (mIsInEditMode) {
      mHideOverlayButtons = false;
      mShowingOverlay = true;
    }
  }

  public boolean isPointerOwned(int pointerId)
  {
    for (InputOverlayDrawableButton button : overlayButtons)
      if (button.getTrackId() == pointerId)
        return true;
    for (InputOverlayDrawableDpad dpad : overlayDpads)
      if (dpad.getTrackId() == pointerId)
        return true;
    for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
      if (joystick.getTrackId() == pointerId)
        return true;
    for (InputOverlayDrawableTatacon tatacon : overlayTatacons)
      if (tatacon.getTrackId() == pointerId)
        return true;
    return false;
  }

  private boolean isPointerOwnedByButtonDpadOrJoystick(int pointerId)
  {
    for (InputOverlayDrawableButton button : overlayButtons)
      if (button.getTrackId() == pointerId)
        return true;
    for (InputOverlayDrawableDpad dpad : overlayDpads)
      if (dpad.getTrackId() == pointerId)
        return true;
    for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
      if (joystick.getTrackId() == pointerId)
        return true;
    return false;
  }

  public boolean isInEditMode()
  {
    return mIsInEditMode;
  }

  public void setAutoHideEnabled(boolean autoHideEnabled)
  {
    mAutoHideEnabled = autoHideEnabled;
    if(!mAutoHideEnabled){
      mShowingOverlay = true;
      invalidate();
    } else {
      resetHideTimer();
    }
  }

  public void setAllowVirtualController(boolean allowVirtualController)
  {
    if(mAllowVirtualController == allowVirtualController)
      return;

    mAllowVirtualController = allowVirtualController;
    updateVirtualControllerState();
  }

  public void setLayout(OverlayLayout layout)
  {
    setLayout(layout, true);
  }

  public OverlayLayout captureLayout()
  {
    // Return both portrait and landscape layouts combined
    OverlayLayout result = OverlayLayout.createFromJavaMaps(mPortraitLayout.getPortraitPositionsJava(), mLandscapeLayout.getLandscapePositionsJava()).normalized();
    android.util.Log.d("TataconDebug", "CAPTURE portrait.size=" + result.getPortraitPositions().size()
            + " landscape.size=" + result.getLandscapePositions().size());
    java.util.Map<Integer, OverlayPosition> pp = result.getPortraitPositions();
    for (java.util.Map.Entry<Integer, OverlayPosition> e : pp.entrySet()) {
      android.util.Log.d("TataconDebug", "CAPTURE portrait btn=" + e.getKey()
              + " x=" + e.getValue().getNormalizedX() + " y=" + e.getValue().getNormalizedY());
    }
    return result;
  }

  public void setLayout(OverlayLayout layout, boolean refresh)
  {
    // Update the layout that was provided (portrait or landscape), keep the other unchanged
    boolean hasPortrait = !layout.getPortraitPositionsJava().isEmpty();
    boolean hasLandscape = !layout.getLandscapePositionsJava().isEmpty();

    // IMPORTANT: keep each orientation's layout pure - portrait layout holds ONLY portrait
    // positions, landscape layout holds ONLY landscape positions. The other orientation's
    // map must stay empty, because refreshControls() detects the current orientation by
    // checking which map is non-empty, and captureLayout() reads portrait from
    // mPortraitLayout and landscape from mLandscapeLayout. Cross-pollinating the maps
    // makes landscape rendering read portrait coordinates (positions appear to revert).
    OverlayLayout newPortraitLayout = hasPortrait
            ? OverlayLayout.createFromJavaMaps(layout.getPortraitPositionsJava(), Collections.emptyMap()).normalized()
            : mPortraitLayout;
    OverlayLayout newLandscapeLayout = hasLandscape
            ? OverlayLayout.createFromJavaMaps(Collections.emptyMap(), layout.getLandscapePositionsJava()).normalized()
            : mLandscapeLayout;
    // If neither has positions, don't update either (shouldn't happen normally)

    // mLayout keeps the full combined layout for backward compatibility
    OverlayLayout newLayout = OverlayLayout.createFromJavaMaps(
            newPortraitLayout.getPortraitPositionsJava(),
            newLandscapeLayout.getLandscapePositionsJava()
    ).normalized();

    // refreshControls() clears and rebuilds every overlay drawable, decoding and
    // scaling bitmaps, which can take hundreds of milliseconds. Skip the rebuild
    // when the layout is unchanged: applyOverlayState() re-applies the same layout
    // every time the pause menu closes, and that rebuild used to block the UI
    // thread long enough to keep the menu on screen after the game had resumed.
    boolean changed = !newLayout.equals(mLayout);

    mPortraitLayout = newPortraitLayout;
    mLandscapeLayout = newLandscapeLayout;
    mLayout = newLayout;

    if (refresh && changed)
      refreshControls();
  }

  public void refreshOverlayScope(String scopeId)
  {
    boolean allowGameOverride = OverlayStore.hasGameOverride(getContext(), scopeId);
    OverlayState state = OverlayStore.resolveState(getContext(), scopeId, allowGameOverride);
    applyResolvedState(state, true);
  }

  public void rebindController()
  {
    releaseAllInputs();
    detachVirtualController();
    updateVirtualControllerState();
  }

  private void updateVirtualControllerState()
  {
    boolean shouldAttach = mAllowVirtualController && mOverlayMask != 0 && getWindowToken() != null;
    if (shouldAttach == mControllerAttached)
      return;

    if (shouldAttach) {
      attachController();
      mControllerAttached = true;
    } else {
      detachVirtualController();
    }
  }

  private void detachVirtualController()
  {
    detachController();
    mControllerAttached = false;
  }

  public native void attachController();
  public native void detachController();
  public native void setAxis(int axis, short value);
  public native void setButton(int button, boolean value);
  public native void setTouchState(int mode); // 0 = front, 1 = back, 2 = front + back mirrored

  private LayoutBounds resolveLayoutBounds() {
    int w = getWidth();
    int h = getHeight();
    // 优先用当前 SurfaceView 实时尺寸，避免退出重进后沿用旧缓存导致归一化坐标乘错基数。
    if (w > 0 && h > 0) {
      return new LayoutBounds(w, h);
    }
    if (mStableWidth > 0 && mStableHeight > 0) {
      return new LayoutBounds(mStableWidth, mStableHeight);
    }
    return resolveLayoutBounds(w, h, getContext());
  }

  private static LayoutBounds resolveLayoutBounds(int width, int height, Context context) {
    if (width <= 0 || height <= 0) {
      DisplayMetrics metrics = context.getResources().getDisplayMetrics();
      width = metrics.widthPixels;
      height = metrics.heightPixels;
    }

    return new LayoutBounds(width, height);
  }

  private void applyResolvedState(OverlayState state, boolean refresh)
  {
    OverlayState resolvedState = state != null ? state : OverlayStore.defaultState(getContext());
    OverlayLayout normalizedLayout = resolvedState.getLayout().normalized();
    OverlayConfig normalizedConfig = resolvedState.getConfig().normalized();
	mOverlayConfig = normalizedConfig;
    float resolvedScale = normalizedConfig.overlayScale / 100f;
	int resolvedOpacity = normalizedConfig.overlayOpacity;
    boolean changed = !normalizedLayout.equals(mLayout)
            || Float.compare(mScale, resolvedScale) != 0
            || mOpacity != resolvedOpacity;

    // Initialize both portrait and landscape layouts from the normalized layout
    mPortraitLayout = OverlayLayout.createFromJavaMaps(normalizedLayout.getPortraitPositionsJava(), Collections.emptyMap());
    mLandscapeLayout = OverlayLayout.createFromJavaMaps(Collections.emptyMap(), normalizedLayout.getLandscapePositionsJava());
    mLayout = normalizedLayout; // Keep for backward compatibility

    mScale = resolvedScale;
    mOpacity = resolvedOpacity;

    if (refresh && changed)
      refreshControls();
  }

  private static final class LayoutBounds {
    final int width;
    final int height;

    LayoutBounds(int width, int height) {
      this.width = width;
      this.height = height;
    }
  }

  public static final class ButtonType
  {
    public static final int BUTTON_CROSS = 0;
    public static final int BUTTON_CIRCLE = 1;
    public static final int BUTTON_SQUARE = 2;
    public static final int BUTTON_TRIANGLE = 3;
    public static final int BUTTON_SELECT = 4;
    public static final int BUTTON_START = 5;
    public static final int DPAD_UP = 6;
    public static final int BUTTON_PS = 7;
    public static final int STICK_LEFT = 10;
    public static final int STICK_RIGHT = 15;
    public static final int TRIGGER_L = 20;
    public static final int TRIGGER_R = 21;
    public static final int TRIGGER_L2 = 22;
    public static final int TRIGGER_R2 = 23;
    public static final int TRIGGER_L3 = 24;
    public static final int TRIGGER_R3 = 25;
    public static final int BUTTON_TOUCH_HIDE = 1023;
    public static final int BUTTON_TOUCH_SWITCH = 1024;
	public static final int TATACON = 30;
  }

  // SDL values
  public static class ControlId {
    public static final int a = 0;
    public static final int b = 1;
    public static final int x = 2;
    public static final int y = 3;
    public static final int select = 4;
    public static final int guide = 5;
    public static final int start = 6;
    public static final int l1 = 9;
    public static final int r1 = 10;
    public static final int dup = 11;
    public static final int ddown = 12;
    public static final int dleft = 13;
    public static final int dright = 14;

    public static final int l3 = 7;
    public static final int r3 = 8;

    // they are axis for sdl but buttons for the ps vita
    public static final int l2 = -4;
    public static final int r2 = -5;

    // button to switch between front and back touch
    public static final int touch = 1024;

    public static final int axis_left_x = 0;
    public static final int axis_left_y = 1;
    public static final int axis_right_x = 2;
    public static final int axis_right_y = 3;
  }
}
