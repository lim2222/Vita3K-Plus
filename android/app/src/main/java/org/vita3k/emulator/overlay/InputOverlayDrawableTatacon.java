package org.vita3k.emulator.overlay;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.view.MotionEvent;

public final class InputOverlayDrawableTatacon
{
  public static final int ZONE_NONE = -1;
  public static final int ZONE_UP = 0;         // 左上，方向键上
  public static final int ZONE_DOWN = 1;       // 左下，方向键下
  public static final int ZONE_CIRCLE = 2;     // 右上，Circle
  public static final int ZONE_CROSS = 3;      // 右下，Cross
  public static final int ZONE_RIM_LEFT = 4;   // 外圈左，L1
  public static final int ZONE_RIM_RIGHT = 5;  // 外圈右，R1

  private int mLegacyId;
  private int[] mControls = new int[6];
  private int mTrackId;
  private int mPreviousTouchX, mPreviousTouchY;
  private int mControlPositionX, mControlPositionY;
  private int mWidth;
  private int mHeight;
  private int mBaseWidth;
  private int mBaseHeight;
  private float mCurrentScale = 1.0f;
  private float mScaleAtEditStart = 1.0f;
  private int mCenterX, mCenterY;
  private int mResizeStartDist;
  private BitmapDrawable mBaseBitmap;
  private int mActiveZone = ZONE_NONE;
  private int mOpacity = 255;

  public InputOverlayDrawableTatacon(Resources res, Bitmap baseBitmap, int legacyId,
          int upControl, int downControl, int circleControl, int crossControl,
          int rimLeftControl, int rimRightControl)
  {
    mTrackId = -1;
    mBaseBitmap = new BitmapDrawable(res, baseBitmap);
    mWidth = mBaseBitmap.getIntrinsicWidth();
    mHeight = mBaseBitmap.getIntrinsicHeight();
    mBaseWidth = mWidth;
    mBaseHeight = mHeight;

    mLegacyId = legacyId;
    mControls[ZONE_UP] = upControl;
    mControls[ZONE_DOWN] = downControl;
    mControls[ZONE_CIRCLE] = circleControl;
    mControls[ZONE_CROSS] = crossControl;
    mControls[ZONE_RIM_LEFT] = rimLeftControl;
    mControls[ZONE_RIM_RIGHT] = rimRightControl;
  }

  public int getLegacyId() { return mLegacyId; }

  public int getControl(int zone) { return mControls[zone]; }

  public void setTrackId(int trackId) { mTrackId = trackId; }
  public int getTrackId() { return mTrackId; }

  public void setActiveZone(int zone) { mActiveZone = zone; }
  public int getActiveZone() { return mActiveZone; }

  /** 端口自 Cemu TaikoDrum.getZoneByPosition：椭圆判定，donRatio 取自 Cemu 已调好的比例。 */
  public static int zoneForTouch(Rect bounds, float x, float y)
  {
    float halfWidth = bounds.width() * 0.5f;
    float halfHeight = bounds.height() * 0.5f;
    float cx = bounds.exactCenterX();
    float cy = bounds.exactCenterY();

    float dx = (x - cx) / halfWidth;
    float dy = (y - cy) / halfHeight;
    float norm2 = dx * dx + dy * dy;

    if (norm2 > 1.0f) return ZONE_NONE;

    float donRatioX2 = 0.28f * 0.28f;
    float donRatioY2 = 0.37f * 0.37f;
    float donCheck = (dx * dx) / donRatioX2 + (dy * dy) / donRatioY2;

    if (donCheck < 1.0f)
    {
      if (dx < 0f) return dy < 0f ? ZONE_UP : ZONE_DOWN;
      else         return dy < 0f ? ZONE_CIRCLE : ZONE_CROSS;
    }

    return dx < 0f ? ZONE_RIM_LEFT : ZONE_RIM_RIGHT;
  }

  public void draw(Canvas canvas)
  {
    boolean isPressed = mActiveZone != ZONE_NONE;
    boolean isDonPressed = mActiveZone == ZONE_UP || mActiveZone == ZONE_DOWN
            || mActiveZone == ZONE_CIRCLE || mActiveZone == ZONE_CROSS;

    Rect bounds = mBaseBitmap.getBounds();
    float cx = bounds.exactCenterX();
    float cy = bounds.exactCenterY();
    float halfWidth = bounds.width() * 0.5f;
    float halfHeight = bounds.height() * 0.5f;

    if (isPressed)
    {
      int pressOffsetY = (int) (bounds.height() * 0.01f);
      canvas.save();
      canvas.translate(0, pressOffsetY);
      mBaseBitmap.draw(canvas);
      canvas.restore();
    }
    else
    {
      mBaseBitmap.draw(canvas);
    }

    if (isDonPressed)
    {
      float glowWidth = halfWidth * 0.46f * 1.1f;
      float glowHeight = halfHeight * 0.60f * 1.1f;
      float glowLeft = cx - glowWidth * 0.5f;
      float glowTop = cy - glowHeight * 0.5f;

      Paint glowPaint = new Paint();
      glowPaint.setAntiAlias(true);
      glowPaint.setShader(new RadialGradient(
              cx, cy, glowWidth * 0.5f,
              new int[]{ 0xFFFBAD96, 0x00FBAD96 },
              new float[]{ 0f, 1f },
              Shader.TileMode.CLAMP
      ));
      glowPaint.setAlpha(Math.min((int) (mOpacity * 1.45f), 255));

      canvas.drawOval(glowLeft, glowTop, glowLeft + glowWidth, glowTop + glowHeight, glowPaint);
    }
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
        mControlPositionX += (int) event.getX() - mPreviousTouchX;
        mControlPositionY += (int) event.getY() - mPreviousTouchY;
        setBounds(mControlPositionX, mControlPositionY, getWidth() + mControlPositionX,
                getHeight() + mControlPositionY);
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
        if (mCurrentScale < 0.2f) mCurrentScale = 0.2f;
        if (mCurrentScale > 20.0f) mCurrentScale = 20.0f;

        mWidth = (int) (mBaseWidth * mCurrentScale);
        mHeight = (int) (mBaseHeight * mCurrentScale);
        mControlPositionX = mCenterX - mWidth / 2;
        mControlPositionY = mCenterY - mHeight / 2;
        setBounds(mControlPositionX, mControlPositionY,
                mControlPositionX + mWidth, mControlPositionY + mHeight);

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

  public void setPosition(int x, int y)
  {
    mControlPositionX = x;
    mControlPositionY = y;
  }

  public void setBounds(int left, int top, int right, int bottom)
  {
    mBaseBitmap.setBounds(left, top, right, bottom);
  }

  public void setOpacity(int value)
  {
    mOpacity = value;
    mBaseBitmap.setAlpha(value);
  }

  public Rect getBounds() { return mBaseBitmap.getBounds(); }
  public int getWidth() { return mWidth; }
  public int getHeight() { return mHeight; }
}