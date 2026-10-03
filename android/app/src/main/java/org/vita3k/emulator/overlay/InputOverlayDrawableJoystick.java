/*
 * Copyright 2013 Dolphin Emulator Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.vita3k.emulator.overlay;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.MotionEvent;
/**
 * Custom {@link BitmapDrawable} that is capable
 * of storing it's own ID.
 */
public final class InputOverlayDrawableJoystick
{
  private float mCurrentX = 0.0f;
  private float mCurrentY = 0.0f;
  private float mJoystickX = 0.0f;
  private float mJoystickY = 0.0f;
  private int trackId = -1;
  private final int mJoystickLegacyId;
  private final int mJoystickXControl;
  private final int mJoystickYControl;
  private int mControlPositionX, mControlPositionY;
  private int mPreviousTouchX, mPreviousTouchY;
  private int mWidth;
  private int mHeight;
  private final int mBaseWidth;
  private final int mBaseHeight;
  private final int mInnerBaseWidth;
  private final int mInnerBaseHeight;
  private float mCurrentScale = 1.0f;
  private float mScaleAtEditStart = 1.0f;
  private int mCenterX, mCenterY;
  private int mResizeStartDist;
  private Rect mVirtBounds;
  private Rect mOrigBounds;
  private int mOpacity;
  private final BitmapDrawable mOuterBitmap;
  private final BitmapDrawable mDefaultStateInnerBitmap;
  private final BitmapDrawable mPressedStateInnerBitmap;
  private final BitmapDrawable mBoundsBoxBitmap;
  private boolean mPressedState = false;
  private float mEditModeAlphaMultiplier = 1.0f;

  /**
   * Constructor
   *
   * @param res                {@link Resources} instance.
   * @param bitmapOuter        {@link Bitmap} which represents the outer non-movable part of the joystick.
   * @param bitmapInnerDefault {@link Bitmap} which represents the default inner movable part of the joystick.
   * @param bitmapInnerPressed {@link Bitmap} which represents the pressed inner movable part of the joystick.
   * @param rectOuter          {@link Rect} which represents the outer joystick bounds.
   * @param rectInner          {@link Rect} which represents the inner joystick bounds.
   * @param legacyId           Legacy identifier (ButtonType) for which joystick this is.
   * @param xControl           The control which the x value of the joystick will be written to.
   * @param yControl           The control which the y value of the joystick will be written to.
   */
  public InputOverlayDrawableJoystick(Resources res, Bitmap bitmapOuter, Bitmap bitmapInnerDefault,
          Bitmap bitmapInnerPressed, Rect rectOuter, Rect rectInner, int legacyId, int xControl,
          int yControl)
  {
    mJoystickLegacyId = legacyId;
    mJoystickXControl = xControl;
    mJoystickYControl = yControl;

    mOuterBitmap = new BitmapDrawable(res, bitmapOuter);
    mDefaultStateInnerBitmap = new BitmapDrawable(res, bitmapInnerDefault);
    mPressedStateInnerBitmap = new BitmapDrawable(res, bitmapInnerPressed);
    mBoundsBoxBitmap = new BitmapDrawable(res, bitmapOuter);
    mWidth = bitmapOuter.getWidth();
    mHeight = bitmapOuter.getHeight();
    mBaseWidth = mWidth;
    mBaseHeight = mHeight;
    mInnerBaseWidth = rectInner.width();
    mInnerBaseHeight = rectInner.height();

    setBounds(rectOuter);
    mDefaultStateInnerBitmap.setBounds(rectInner);
    mPressedStateInnerBitmap.setBounds(rectInner);
    mVirtBounds = getBounds();
    mOrigBounds = mOuterBitmap.copyBounds();
    mBoundsBoxBitmap.setAlpha(0);
    mBoundsBoxBitmap.setBounds(getVirtBounds());
    SetInnerBounds();
  }

  /**
   * Gets this InputOverlayDrawableJoystick's legacy ID.
   *
   * @return this InputOverlayDrawableJoystick's legacy ID.
   */
  public int getLegacyId()
  {
    return mJoystickLegacyId;
  }

  public void draw(Canvas canvas)
  {
    mOuterBitmap.draw(canvas);
    getCurrentStateBitmapDrawable().draw(canvas);
    mBoundsBoxBitmap.draw(canvas);
  }

  public boolean TrackEvent(MotionEvent event)
  {
    boolean reCenter = false;
    int action = event.getActionMasked();
    boolean firstPointer = action != MotionEvent.ACTION_POINTER_DOWN &&
            action != MotionEvent.ACTION_POINTER_UP;
    int pointerIndex = firstPointer ? 0 : event.getActionIndex();
    boolean concerned = false;

    switch (action)
    {
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_POINTER_DOWN:
        if (getBounds().contains((int) event.getX(pointerIndex), (int) event.getY(pointerIndex)))
        {
          concerned = true;
          mPressedState = true;
          mOuterBitmap.setAlpha(0);
          mBoundsBoxBitmap.setAlpha(mOpacity);
          if (reCenter)
          {
            getVirtBounds().offset((int) event.getX(pointerIndex) - getVirtBounds().centerX(),
                    (int) event.getY(pointerIndex) - getVirtBounds().centerY());
          }
          mBoundsBoxBitmap.setBounds(getVirtBounds());
          trackId = event.getPointerId(pointerIndex);
        }
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_POINTER_UP:
        if (trackId == event.getPointerId(pointerIndex))
        {
          concerned = true;
          mPressedState = false;
          mCurrentX = mCurrentY = 0.0f;
          mJoystickX = mJoystickY = 0.0f;
          mOuterBitmap.setAlpha(mOpacity);
          mBoundsBoxBitmap.setAlpha(0);
          setVirtBounds(new Rect(mOrigBounds.left, mOrigBounds.top, mOrigBounds.right,
                  mOrigBounds.bottom));
          setBounds(new Rect(mOrigBounds.left, mOrigBounds.top, mOrigBounds.right,
                  mOrigBounds.bottom));
          SetInnerBounds();
          trackId = -1;
        }
        break;
    }

    if (trackId == -1)
      return concerned;

    for (int i = 0; i < event.getPointerCount(); i++)
    {
      if (trackId == event.getPointerId(i))
      {
        concerned = true;
        float touchX = event.getX(i);
        float touchY = event.getY(i);
        float maxY = getVirtBounds().bottom;
        float maxX = getVirtBounds().right;
        touchX -= getVirtBounds().centerX();
        maxX -= getVirtBounds().centerX();
        touchY -= getVirtBounds().centerY();
        maxY -= getVirtBounds().centerY();
        mCurrentX = touchX / maxX;
        mCurrentY = touchY / maxY;

        SetInnerBounds();
      }
    }
    return concerned;
  }

  public void onConfigureTouch(MotionEvent event)
  {
    switch (event.getAction())
    {
      case MotionEvent.ACTION_DOWN:
        mPreviousTouchX = (int) event.getX();
        mPreviousTouchY = (int) event.getY();
        break;
      case MotionEvent.ACTION_MOVE:
        int deltaX = (int) event.getX() - mPreviousTouchX;
        int deltaY = (int) event.getY() - mPreviousTouchY;
        mControlPositionX += deltaX;
        mControlPositionY += deltaY;
        setBounds(new Rect(mControlPositionX, mControlPositionY,
                mWidth + mControlPositionX,
                mHeight + mControlPositionY));
        setVirtBounds(new Rect(mControlPositionX, mControlPositionY,
                mWidth + mControlPositionX,
                mHeight + mControlPositionY));
        SetInnerBounds();
        setOrigBounds(new Rect(mControlPositionX, mControlPositionY,
                mWidth + mControlPositionX,
                mHeight + mControlPositionY));
        mPreviousTouchX = (int) event.getX();
        mPreviousTouchY = (int) event.getY();
        break;
    }
  }

  public void onConfigureResize(MotionEvent event)
  {
    switch (event.getAction())
    {
      case MotionEvent.ACTION_DOWN:
        mCenterX = mControlPositionX + mWidth / 2;
        mCenterY = mControlPositionY + mHeight / 2;
        int dx0 = (int) event.getX() - mCenterX;
        int dy0 = (int) event.getY() - mCenterY;
        mResizeStartDist = (int) Math.sqrt(dx0 * dx0 + dy0 * dy0);
        if (mResizeStartDist < 10) mResizeStartDist = 10;
        break;
      case MotionEvent.ACTION_MOVE:
        int dx = (int) event.getX() - mCenterX;
        int dy = (int) event.getY() - mCenterY;
        int dist = (int) Math.sqrt(dx * dx + dy * dy);
        float ratio = (float) dist / mResizeStartDist;
        mCurrentScale = mCurrentScale * ratio;
        if (mCurrentScale < 0.5f) mCurrentScale = 0.5f;
        if (mCurrentScale > 2.5f) mCurrentScale = 2.5f;

        mWidth = (int) (mBaseWidth * mCurrentScale);
        mHeight = (int) (mBaseHeight * mCurrentScale);
        mControlPositionX = mCenterX - mWidth / 2;
        mControlPositionY = mCenterY - mHeight / 2;

        Rect outer = new Rect(mControlPositionX, mControlPositionY,
                mControlPositionX + mWidth, mControlPositionY + mHeight);
        setBounds(new Rect(outer));
        setVirtBounds(new Rect(outer));
        setOrigBounds(new Rect(outer));

        int innerW = (int) (mInnerBaseWidth * mCurrentScale);
        int innerH = (int) (mInnerBaseHeight * mCurrentScale);
        mDefaultStateInnerBitmap.setBounds(0, 0, innerW, innerH);
        mPressedStateInnerBitmap.setBounds(0, 0, innerW, innerH);
        SetInnerBounds();

        mResizeStartDist = dist;
        break;
    }
  }

  public void setScaleAtEditStart(float scale)
  {
    mScaleAtEditStart = scale;
    mCurrentScale = 1.0f;
  }

  public float getFinalScale()
  {
    return mScaleAtEditStart * mCurrentScale;
  }

  public float getX()
  {
    return mJoystickX;
  }

  public float getY()
  {
    return mJoystickY;
  }

  public int getXControl()
  {
    return mJoystickXControl;
  }

  public int getYControl()
  {
    return mJoystickYControl;
  }

  private void SetInnerBounds()
  {
    double x = mCurrentX;
    double y = mCurrentY;

    double angle = Math.atan2(y, x) + Math.PI + Math.PI;
    double radius = Math.hypot(y, x);
    double maxRadius = 1.0f;
    if (radius > maxRadius)
    {
      y = maxRadius * Math.sin(angle);
      x = maxRadius * Math.cos(angle);
      mCurrentY = (float) y;
      mCurrentX = (float) x;
    }

    int pixelX = getVirtBounds().centerX() + (int) (x * (getVirtBounds().width() / 2));
    int pixelY = getVirtBounds().centerY() + (int) (y * (getVirtBounds().height() / 2));

    int width = mPressedStateInnerBitmap.getBounds().width() / 2;
    int height = mPressedStateInnerBitmap.getBounds().height() / 2;
    mDefaultStateInnerBitmap.setBounds(pixelX - width, pixelY - height, pixelX + width,
            pixelY + height);
    mPressedStateInnerBitmap.setBounds(mDefaultStateInnerBitmap.getBounds());

    // x,y is projected onto a circle while we want the values to be projected on the bounding square
    // for example, when (x,y) = (1/sqrt(2), 1/sqrt(2)) we want to set (joyX, joyY) to (1,1)
    x = Math.abs(x);
    y = Math.abs(y);
    if(x < y){
      double temp = x;
      x = y;
      y = temp;
    }
    if(x < 0.01){
      // too centered, scaling does not make sense
      mJoystickX = mCurrentX;
      mJoystickY = mCurrentY;
    } else {
      // (x,y) projected on the bounding square is (1, y_proj)
      float y_proj = (float)(y / x);
      float scaling = (float)Math.sqrt(1.0f + y_proj * y_proj);
      mJoystickX = mCurrentX * scaling;
      mJoystickY = mCurrentY * scaling;
    }
  }

  public void setPosition(int x, int y)
  {
    mControlPositionX = x;
    mControlPositionY = y;
  }

  private BitmapDrawable getCurrentStateBitmapDrawable()
  {
    return mPressedState ? mPressedStateInnerBitmap : mDefaultStateInnerBitmap;
  }

  public void setBounds(Rect bounds)
  {
    mOuterBitmap.setBounds(bounds);
  }

  public void setOpacity(int value)
  {
    mOpacity = value;
    int adjustedValue = (int) (value * mEditModeAlphaMultiplier);

    mDefaultStateInnerBitmap.setAlpha(adjustedValue);
    mPressedStateInnerBitmap.setAlpha(adjustedValue);

    if (trackId == -1)
    {
      mOuterBitmap.setAlpha(adjustedValue);
      mBoundsBoxBitmap.setAlpha(0);
    }
    else
    {
      mOuterBitmap.setAlpha(0);
      mBoundsBoxBitmap.setAlpha(adjustedValue);
    }
  }

  public void setEditModeAlphaMultiplier(float multiplier)
  {
    mEditModeAlphaMultiplier = multiplier;
  }

  public Rect getBounds()
  {
    return mOuterBitmap.getBounds();
  }

  private void setVirtBounds(Rect bounds)
  {
    mVirtBounds = bounds;
  }

  private void setOrigBounds(Rect bounds)
  {
    mOrigBounds = bounds;
  }

  private Rect getVirtBounds()
  {
    return mVirtBounds;
  }

  public int getWidth()
  {
    return mWidth;
  }

  public int getHeight()
  {
    return mHeight;
  }

  public int getTrackId()
  {
    return trackId;
  }

  public void release()
  {
    mPressedState = false;
    mCurrentX = mCurrentY = 0.0f;
    mJoystickX = mJoystickY = 0.0f;
    mOuterBitmap.setAlpha(mOpacity);
    mBoundsBoxBitmap.setAlpha(0);
    setVirtBounds(new Rect(mOrigBounds.left, mOrigBounds.top, mOrigBounds.right,
            mOrigBounds.bottom));
    setBounds(new Rect(mOrigBounds.left, mOrigBounds.top, mOrigBounds.right,
            mOrigBounds.bottom));
    SetInnerBounds();
    trackId = -1;
  }
}