/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;
import android.util.DisplayMetrics;

import java.text.NumberFormat;

public class BatteryMeterHorizontalView extends AbstractBatteryView {
    public static final String TAG = BatteryMeterHorizontalView.class.getSimpleName();

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction
    private static final int FULL = 96;

    private float mButtonHeightFraction;
    private float mSubpixelSmoothingLeft;
    private float mSubpixelSmoothingRight;
    private final Paint mFramePaint, mBatteryPaint, mTextPaint, mBoltPaint;
    private float mTextHeight;
    private int mTextSize;
    private int mTextWidth;
    private int mBarWidth;
    private int mBarSpaceWidth;
    private int mBarHeight;
    private int mHeight;
    private int mWidth;
    private int mPercentOffsetY;

    private final Path mBoltPath = new Path();

    private final RectF mFrame = new RectF();
    private final RectF mButtonFrame = new RectF();
    private final RectF mBoltFrame = new RectF();

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();
    private final Path mTextPath = new Path();

    public BatteryMeterHorizontalView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterHorizontalView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterHorizontalView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mButtonHeightFraction = getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);
        mSubpixelSmoothingLeft = getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_left, 1, 1);
        mSubpixelSmoothingRight = getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_right, 1, 1);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(mFrameColor);
        mFramePaint.setDither(true);
        mFramePaint.setStrokeWidth(3);
        mFramePaint.setStyle(Paint.Style.STROKE);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);
        mBatteryPaint.setStrokeWidth(0);
        mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextSize = getResources().getDimensionPixelSize(R.dimen.battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);

        mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBoltPaint.setColor(getResources().getColor(R.color.batterymeter_bolt_color));

        Rect bounds = new Rect();
        final String text = "100%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();

        // bar width is hardcoded  android:layout_width="14.5dp"
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mBarWidth = (int) (20 * metrics.density + 0.5f);
        mBarSpaceWidth = (int) (22 * metrics.density + 0.5f);
        mBarHeight = (int) (10 * metrics.density + 0.5f);
        mPercentOffsetY = (int) (1 * metrics.density + 0.5f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = (isWideDisplay() ? mTextWidth : 0) + mBarSpaceWidth;
        mHeight = getMeasuredHeight();
        setMeasuredDimension(mWidth, mHeight);
    }

    @Override
    public void draw(Canvas c) {
        BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
        final int level = tracker.level;

        if (level == BatteryTracker.UNKNOWN_LEVEL) return;

        float drawFrac = (float) level / 100f;
        final int height = mHeight;
        final int width = mBarWidth;
        final int insetTop = (height - mBarHeight) / 2 + mPercentOffsetY;
        final int insetBottom = (height - mBarHeight) / 2 - mPercentOffsetY;
        final int buttonHeight = (int) (width * mButtonHeightFraction);

        mFrame.set(0, 0, width, height);

        // button-frame: area above the battery body
        mButtonFrame.set(
                mFrame.right - buttonHeight,
                mFrame.top  + insetTop + Math.round(mBarHeight * 0.25f),
                mFrame.right,
                mFrame.bottom - insetBottom - Math.round(mBarHeight * 0.25f));

        // frame: battery body area
        mFrame.right -= buttonHeight;

        // set the battery charging color
        int fillColor = tracker.plugged ? mChargeColor : getColorForLevel(level);
        if (mShowPercent && mPercentInside && !showChargingImage()) {
            fillColor = Color.argb(0x5f, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor));
        }
        mBatteryPaint.setColor(fillColor);
        mFramePaint.setColor(tracker.plugged ? mChargeColor : getColorForLevel(level));

        if (level >= FULL) {
            drawFrac = 1f;
        } else if (level <= mCriticalLevel) {
            drawFrac = 0f;
        }

        final float levelTop = drawFrac == 1f ? mButtonFrame.right
                : (mFrame.right - (mFrame.width() * (1f - drawFrac)));

        // define the battery shape
        mShapePath.reset();
        mShapePath.moveTo(mButtonFrame.left, mButtonFrame.top);
        mShapePath.lineTo(mButtonFrame.right, mButtonFrame.top);
        mShapePath.lineTo(mButtonFrame.right, mButtonFrame.bottom);
        mShapePath.lineTo(mButtonFrame.left, mButtonFrame.bottom);
        mShapePath.lineTo(mFrame.right, mFrame.bottom - insetBottom);
        mShapePath.lineTo(mFrame.left, mFrame.bottom - insetBottom);
        mShapePath.lineTo(mFrame.left, mFrame.top + insetTop);
        mShapePath.lineTo(mFrame.right, mFrame.top + insetTop);
        mShapePath.lineTo(mButtonFrame.left, mButtonFrame.top);

        if (showChargingImage()) {
            int boltColor = tracker.plugged ? mChargeColor : getColorForLevel(level);
            mBoltPaint.setColor(boltColor);
            // define the bolt shape
            final float bl = mFrame.left + mFrame.width() / 2.5f;
            final float bt = mFrame.top + insetTop + 5;
            final float br = mFrame.right - mFrame.width() / 4.5f;
            final float bb = mFrame.bottom - insetBottom - 2;
            if (mBoltFrame.left != bl || mBoltFrame.top != bt
                    || mBoltFrame.right != br || mBoltFrame.bottom != bb) {
                mBoltFrame.set(bl, bt, br, bb);
                mBoltPath.reset();
                mBoltPath.moveTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                for (int i = 2; i < mBoltPoints.length; i += 2) {
                    mBoltPath.lineTo(
                            mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                }
                mBoltPath.lineTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
            }

            if (drawFrac <= BOLT_LEVEL_THRESHOLD) {
                // draw the bolt if opaque
                c.drawPath(mBoltPath, mBoltPaint);
            } else {
                // otherwise cut the bolt out of the overall shape
                mShapePath.op(mBoltPath, Path.Op.DIFFERENCE);
            }
        }

        // draw the battery shape background
        c.drawPath(mShapePath, mFramePaint);

        // draw the battery shape, clipped to charging level
        mFrame.right = levelTop;
        mClipPath.reset();
        mClipPath.addRect(mFrame,  Path.Direction.CCW);
        mShapePath.op(mClipPath, Path.Op.INTERSECT);
        c.drawPath(mShapePath, mBatteryPaint);

        if (mShowPercent) {
            mTextPaint.setColor(tracker.plugged ? mChargeColor : getColorForLevel(level));

            float textHeight = 0f;
            float textOffset = 0f;
            RectF bounds = null;
            String percentage = null;

            if (mPercentInside) {
                if (!showChargingImage()) {
                    percentage = String.valueOf(level);
                    textHeight = mTextPaint.descent() - mTextPaint.ascent();
                    textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
                    bounds = new RectF(0, 0, mBarWidth - buttonHeight, mHeight);
                }
            } else {
                percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
                textHeight = mTextPaint.descent() - mTextPaint.ascent();
                textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
                bounds = new RectF(mBarSpaceWidth, 0, mWidth, mHeight);
            }
            if (percentage != null) {
                c.drawText(percentage, bounds.centerX(), bounds.centerY() + textOffset, mTextPaint);
            }
        }
    }

    @Override
    protected void applyStyle() {
        if (mPercentInside) {
            DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
            mTextSize = (int) (11 * metrics.density + 0.5f);
            mTextPaint.setShadowLayer(5.0f, 0.0f, 0.0f, Color.BLACK);
        } else {
            mTextSize = getResources().getDimensionPixelSize(R.dimen.battery_level_text_size);
            mTextPaint.setShadowLayer(0.0f, 0.0f, 0.0f, Color.BLACK);
        }
        mTextPaint.setTextSize(mTextSize);
        Rect bounds = new Rect();
        final String text = "100%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
    }
}
