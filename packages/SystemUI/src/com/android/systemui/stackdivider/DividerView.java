/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.Nullable;
import android.app.ActivityManager.StackId;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManager;
import android.util.AttributeSet;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DividerSnapAlgorithm.SnapTarget;
import com.android.internal.policy.DockedDividerUtils;
import com.android.systemui.R;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DockingTopTaskEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.ui.RecentsDrawnEvent;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;

import static android.view.PointerIcon.STYLE_HORIZONTAL_DOUBLE_ARROW;
import static android.view.PointerIcon.STYLE_VERTICAL_DOUBLE_ARROW;

/**
 * Docked stack divider.
 */
public class DividerView extends FrameLayout implements OnTouchListener,
        OnComputeInternalInsetsListener {

    static final long TOUCH_ANIMATION_DURATION = 150;
    static final long TOUCH_RELEASE_ANIMATION_DURATION = 200;
    static final Interpolator TOUCH_RESPONSE_INTERPOLATOR =
            new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    private static final String TAG = "DividerView";

    private static final int TASK_POSITION_SAME = Integer.MAX_VALUE;

    /**
     * Fraction of the divider position between two snap targets to switch to the full-screen
     * target.
     */
    private static final float SWITCH_FULLSCREEN_FRACTION = 0.12f;

    /**
     * Fraction of the divider position between two snap targets to switch to the larger target
     * for the bottom/right app layout.
     */
    private static final float BOTTOM_RIGHT_SWITCH_BIGGER_FRACTION = 0.2f;

    private static final PathInterpolator SLOWDOWN_INTERPOLATOR =
            new PathInterpolator(0.5f, 1f, 0.5f, 1f);
    private static final PathInterpolator DIM_INTERPOLATOR =
            new PathInterpolator(.23f, .87f, .52f, -0.11f);

    private DividerHandleView mHandle;
    private View mBackground;
    private int mStartX;
    private int mStartY;
    private int mStartPosition;
    private int mDockSide;
    private final int[] mTempInt2 = new int[2];
    private boolean mMoving;
    private int mTouchSlop;

    private int mDividerInsets;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private int mDividerWindowWidth;
    private int mDividerSize;
    private int mTouchElevation;

    private final Rect mDockedRect = new Rect();
    private final Rect mDockedTaskRect = new Rect();
    private final Rect mOtherTaskRect = new Rect();
    private final Rect mOtherRect = new Rect();
    private final Rect mDockedInsetRect = new Rect();
    private final Rect mOtherInsetRect = new Rect();
    private final Rect mLastResizeRect = new Rect();
    private final WindowManagerProxy mWindowManagerProxy = WindowManagerProxy.getInstance();
    private Interpolator mFastOutSlowInInterpolator;
    private DividerWindowManager mWindowManager;
    private VelocityTracker mVelocityTracker;
    private FlingAnimationUtils mFlingAnimationUtils;
    private DividerSnapAlgorithm mSnapAlgorithm;
    private final Rect mStableInsets = new Rect();

    private boolean mAnimateAfterRecentsDrawn;
    private boolean mGrowAfterRecentsDrawn;
    private boolean mGrowRecents;

    public DividerView(Context context) {
        super(context);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHandle = (DividerHandleView) findViewById(R.id.docked_divider_handle);
        mBackground = findViewById(R.id.docked_divider_background);
        mHandle.setOnTouchListener(this);
        mDividerWindowWidth = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerInsets = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        mDividerSize = mDividerWindowWidth - 2 * mDividerInsets;
        mTouchElevation = getResources().getDimensionPixelSize(
                R.dimen.docked_stack_divider_lift_elevation);
        mGrowRecents = getResources().getBoolean(R.bool.recents_grow_in_multiwindow);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        mFlingAnimationUtils = new FlingAnimationUtils(getContext(), 0.3f);
        updateDisplayInfo();
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mHandle.setPointerIcon(PointerIcon.getSystemIcon(getContext(),
                landscape ? STYLE_HORIZONTAL_DOUBLE_ARROW : STYLE_VERTICAL_DOUBLE_ARROW));
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mStableInsets.set(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                insets.getStableInsetRight(), insets.getStableInsetBottom());
        return super.onApplyWindowInsets(insets);
    }

    public void setWindowManager(DividerWindowManager windowManager) {
        mWindowManager = windowManager;
    }

    public WindowManagerProxy getWindowManagerProxy() {
        return mWindowManagerProxy;
    }

    public boolean startDragging(boolean animate, boolean touching) {
        if (touching) {
            mHandle.setTouching(true, animate);
        }
        mDockSide = mWindowManagerProxy.getDockSide();
        getSnapAlgorithm();
        if (mDockSide != WindowManager.DOCKED_INVALID) {
            mWindowManagerProxy.setResizing(true);
            mWindowManager.setSlippery(false);
            if (touching) {
                liftBackground();
            }
            return true;
        } else {
            return false;
        }
    }

    public void stopDragging(int position, float velocity, boolean avoidDismissStart) {
        mHandle.setTouching(false, true /* animate */);
        fling(position, velocity, avoidDismissStart);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    public void stopDragging(int position, SnapTarget target, long duration,
            Interpolator interpolator) {
        mHandle.setTouching(false, true /* animate */);
        flingTo(position, target, duration, interpolator);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    public DividerSnapAlgorithm getSnapAlgorithm() {
        if (mSnapAlgorithm == null) {
            mSnapAlgorithm = new DividerSnapAlgorithm(getContext().getResources(), mDisplayWidth,
                    mDisplayHeight, mDividerSize, isHorizontalDivision(), mStableInsets);
        }
        return mSnapAlgorithm;
    }

    public int getCurrentPosition() {
        getLocationOnScreen(mTempInt2);
        if (isHorizontalDivision()) {
            return mTempInt2[1] + mDividerInsets;
        } else {
            return mTempInt2[0] + mDividerInsets;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        convertToScreenCoordinates(event);
        final int action = event.getAction() & MotionEvent.ACTION_MASK;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(event);
                mStartX = (int) event.getX();
                mStartY = (int) event.getY();
                boolean result = startDragging(true /* animate */, true /* touching */);
                mStartPosition = getCurrentPosition();
                mMoving = false;
                return result;
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                int x = (int) event.getX();
                int y = (int) event.getY();
                boolean exceededTouchSlop =
                        isHorizontalDivision() && Math.abs(y - mStartY) > mTouchSlop
                                || (!isHorizontalDivision() && Math.abs(x - mStartX) > mTouchSlop);
                if (!mMoving && exceededTouchSlop) {
                    mStartX = x;
                    mStartY = y;
                    mMoving = true;
                }
                if (mMoving && mDockSide != WindowManager.DOCKED_INVALID) {
                    int position = calculatePosition(x, y);
                    SnapTarget snapTarget = mSnapAlgorithm.calculateSnapTarget(position,
                            0 /* velocity */, false /* hardDismiss */);
                    resizeStack(calculatePosition(x, y), snapTarget.position, snapTarget);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mVelocityTracker.addMovement(event);

                x = (int) event.getRawX();
                y = (int) event.getRawY();

                mVelocityTracker.computeCurrentVelocity(1000);
                int position = calculatePosition(x, y);
                stopDragging(position, isHorizontalDivision() ? mVelocityTracker.getYVelocity()
                        : mVelocityTracker.getXVelocity(), false /* avoidDismissStart */);
                mMoving = false;
                break;
        }
        return true;
    }

    private void convertToScreenCoordinates(MotionEvent event) {
        event.setLocation(event.getRawX(), event.getRawY());
    }

    private void fling(int position, float velocity, boolean avoidDismissStart) {
        SnapTarget snapTarget = mSnapAlgorithm.calculateSnapTarget(position, velocity);
        if (avoidDismissStart && snapTarget == mSnapAlgorithm.getDismissStartTarget()) {
            snapTarget = mSnapAlgorithm.getFirstSplitTarget();
        }
        ValueAnimator anim = getFlingAnimator(position, snapTarget);
        mFlingAnimationUtils.apply(anim, position, snapTarget.position, velocity);
        anim.start();
    }

    private void flingTo(int position, SnapTarget target, long duration,
            Interpolator interpolator) {
        ValueAnimator anim = getFlingAnimator(position, target);
        anim.setDuration(duration);
        anim.setInterpolator(interpolator);
        anim.start();
    }

    private ValueAnimator getFlingAnimator(int position, final SnapTarget snapTarget) {
        ValueAnimator anim = ValueAnimator.ofInt(position, snapTarget.position);
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                resizeStack((Integer) animation.getAnimatedValue(),
                        animation.getAnimatedFraction() == 1f
                                ? TASK_POSITION_SAME
                                : snapTarget.position, snapTarget);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                commitSnapFlags(snapTarget);
                mWindowManagerProxy.setResizing(false);
                mDockSide = WindowManager.DOCKED_INVALID;
            }
        });
        return anim;
    }

    private void commitSnapFlags(SnapTarget target) {
        if (target.flag == SnapTarget.FLAG_NONE) {
            return;
        }
        boolean dismissOrMaximize;
        if (target.flag == SnapTarget.FLAG_DISMISS_START) {
            dismissOrMaximize = mDockSide == WindowManager.DOCKED_LEFT
                    || mDockSide == WindowManager.DOCKED_TOP;
        } else {
            dismissOrMaximize = mDockSide == WindowManager.DOCKED_RIGHT
                    || mDockSide == WindowManager.DOCKED_BOTTOM;
        }
        if (dismissOrMaximize) {
            mWindowManagerProxy.dismissDockedStack();
        } else {
            mWindowManagerProxy.maximizeDockedStack();
        }
        mWindowManagerProxy.setResizeDimLayer(false, -1, 0f);
    }

    private void liftBackground() {
        if (isHorizontalDivision()) {
            mBackground.animate().scaleY(1.4f);
        } else {
            mBackground.animate().scaleX(1.4f);
        }
        mBackground.animate()
                .setInterpolator(TOUCH_RESPONSE_INTERPOLATOR)
                .setDuration(TOUCH_ANIMATION_DURATION)
                .translationZ(mTouchElevation)
                .start();

        // Lift handle as well so it doesn't get behind the background, even though it doesn't
        // cast shadow.
        mHandle.animate()
                .setInterpolator(TOUCH_RESPONSE_INTERPOLATOR)
                .setDuration(TOUCH_ANIMATION_DURATION)
                .translationZ(mTouchElevation)
                .start();
    }

    private void releaseBackground() {
        mBackground.animate()
                .setInterpolator(mFastOutSlowInInterpolator)
                .setDuration(TOUCH_RELEASE_ANIMATION_DURATION)
                .translationZ(0)
                .scaleX(1f)
                .scaleY(1f)
                .start();
        mHandle.animate()
                .setInterpolator(mFastOutSlowInInterpolator)
                .setDuration(TOUCH_RELEASE_ANIMATION_DURATION)
                .translationZ(0)
                .start();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDisplayInfo();
    }

    private void updateDisplayInfo() {
        final DisplayManager displayManager =
                (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        final DisplayInfo info = new DisplayInfo();
        display.getDisplayInfo(info);
        mDisplayWidth = info.logicalWidth;
        mDisplayHeight = info.logicalHeight;
        mSnapAlgorithm = null;
    }

    private int calculatePosition(int touchX, int touchY) {
        return isHorizontalDivision() ? calculateYPosition(touchY) : calculateXPosition(touchX);
    }

    public boolean isHorizontalDivision() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private int calculateXPosition(int touchX) {
        return mStartPosition + touchX - mStartX;
    }

    private int calculateYPosition(int touchY) {
        return mStartPosition + touchY - mStartY;
    }

    private void alignTopLeft(Rect containingRect, Rect rect) {
        int width = rect.width();
        int height = rect.height();
        rect.set(containingRect.left, containingRect.top,
                containingRect.left + width, containingRect.top + height);
    }

    private void alignBottomRight(Rect containingRect, Rect rect) {
        int width = rect.width();
        int height = rect.height();
        rect.set(containingRect.right - width, containingRect.bottom - height,
                containingRect.right, containingRect.bottom);
    }

    public void calculateBoundsForPosition(int position, int dockSide, Rect outRect) {
        DockedDividerUtils.calculateBoundsForPosition(position, dockSide, outRect, mDisplayWidth,
                mDisplayHeight, mDividerSize);
    }

    public void resizeStack(int position, int taskPosition, SnapTarget taskSnapTarget) {
        calculateBoundsForPosition(position, mDockSide, mDockedRect);

        if (mDockedRect.equals(mLastResizeRect)) {
            return;
        }

        // Make sure shadows are updated
        mBackground.invalidate();

        mLastResizeRect.set(mDockedRect);
        if (taskPosition != TASK_POSITION_SAME) {
            calculateBoundsForPosition(position, DockedDividerUtils.invertDockSide(mDockSide),
                    mOtherRect);
            int dockSideInverted = DockedDividerUtils.invertDockSide(mDockSide);
            int taskPositionDocked =
                    restrictDismissingTaskPosition(taskPosition, mDockSide, taskSnapTarget);
            int taskPositionOther =
                    restrictDismissingTaskPosition(taskPosition, dockSideInverted, taskSnapTarget);

            taskPositionDocked = minimizeHoles(position, taskPositionDocked, mDockSide,
                    taskSnapTarget);
            taskPositionOther = minimizeHoles(position, taskPositionOther, dockSideInverted,
                    taskSnapTarget);

            calculateBoundsForPosition(taskPositionDocked, mDockSide, mDockedTaskRect);
            calculateBoundsForPosition(taskPositionOther, dockSideInverted, mOtherTaskRect);
            alignTopLeft(mDockedRect, mDockedTaskRect);
            alignTopLeft(mOtherRect, mOtherTaskRect);
            mDockedInsetRect.set(mDockedTaskRect);
            mOtherInsetRect.set(mOtherTaskRect);
            if (dockSideTopLeft(mDockSide)) {
                alignTopLeft(mDockedRect, mDockedInsetRect);
                alignBottomRight(mOtherRect, mOtherInsetRect);
            } else {
                alignBottomRight(mDockedRect, mDockedInsetRect);
                alignTopLeft(mOtherRect, mOtherInsetRect);
            }
            applyDismissingParallax(mDockedTaskRect, mDockSide, taskSnapTarget, position,
                    taskPositionDocked);
            applyDismissingParallax(mOtherTaskRect, dockSideInverted, taskSnapTarget, position,
                    taskPositionOther);
            mWindowManagerProxy.resizeDockedStack(mDockedRect, mDockedTaskRect, mDockedInsetRect,
                    mOtherTaskRect, mOtherInsetRect);
        } else {
            mWindowManagerProxy.resizeDockedStack(mDockedRect, null, null, null, null);
        }
        float fraction = mSnapAlgorithm.calculateDismissingFraction(position);
        fraction = Math.max(0, Math.min(fraction, 1f));
        fraction = DIM_INTERPOLATOR.getInterpolation(fraction);
        mWindowManagerProxy.setResizeDimLayer(fraction != 0f,
                getStackIdForDismissTarget(mSnapAlgorithm.getClosestDismissTarget(position)),
                fraction);
    }

    /**
     * Given the current split position and the task position calculated by dragging, this
     * method calculates a "better" task position in a sense so holes get smaller while dragging.
     *
     * @return the new task position
     */
    private int minimizeHoles(int position, int taskPosition, int dockSide,
            SnapTarget taskSnapTarget) {
        if (dockSideTopLeft(dockSide)) {
            if (position > taskPosition) {
                SnapTarget nextTarget = mSnapAlgorithm.getNextTarget(taskSnapTarget);

                // If the next target is the dismiss end target, switch earlier to make the hole
                // smaller.
                if (nextTarget != taskSnapTarget
                        && nextTarget == mSnapAlgorithm.getDismissEndTarget()) {
                    float t = (float) (position - taskPosition)
                            / (nextTarget.position - taskPosition);
                    if (t > SWITCH_FULLSCREEN_FRACTION) {
                        return nextTarget.position;
                    }
                }
            }
        } else if (dockSideBottomRight(dockSide)) {
            if (position < taskPosition) {
                SnapTarget previousTarget = mSnapAlgorithm.getPreviousTarget(taskSnapTarget);
                if (previousTarget != taskSnapTarget) {
                    float t = (float) (taskPosition - position)
                            / (taskPosition - previousTarget.position);

                    // In general, switch a bit earlier (at 20% instead of 50%), but if we are
                    // dismissing the top, switch really early.
                    float threshold = previousTarget == mSnapAlgorithm.getDismissStartTarget()
                            ? SWITCH_FULLSCREEN_FRACTION
                            : BOTTOM_RIGHT_SWITCH_BIGGER_FRACTION;
                    if (t > threshold) {
                        return previousTarget.position;
                    }

                }
            }
        }
        return taskPosition;
    }

    /**
     * When the snap target is dismissing one side, make sure that the dismissing side doesn't get
     * 0 size.
     */
    private int restrictDismissingTaskPosition(int taskPosition, int dockSide,
            SnapTarget snapTarget) {
        if (snapTarget.flag == SnapTarget.FLAG_DISMISS_START && dockSideTopLeft(dockSide)) {
            return mSnapAlgorithm.getFirstSplitTarget().position;
        } else if (snapTarget.flag == SnapTarget.FLAG_DISMISS_END
                && dockSideBottomRight(dockSide)) {
            return mSnapAlgorithm.getLastSplitTarget().position;
        } else {
            return taskPosition;
        }
    }

    /**
     * Applies a parallax to the task when dismissing.
     */
    private void applyDismissingParallax(Rect taskRect, int dockSide, SnapTarget snapTarget,
            int position, int taskPosition) {
        float fraction = Math.min(1, Math.max(0,
                mSnapAlgorithm.calculateDismissingFraction(position)));
        SnapTarget dismissTarget = null;
        SnapTarget splitTarget = null;
        if ((snapTarget.flag == SnapTarget.FLAG_DISMISS_START
                || snapTarget == mSnapAlgorithm.getFirstSplitTarget())
                && dockSideTopLeft(dockSide)) {
            dismissTarget = mSnapAlgorithm.getDismissStartTarget();
            splitTarget = mSnapAlgorithm.getFirstSplitTarget();
        } else if ((snapTarget.flag == SnapTarget.FLAG_DISMISS_END
                || snapTarget == mSnapAlgorithm.getLastSplitTarget())
                && dockSideBottomRight(dockSide)) {
            dismissTarget = mSnapAlgorithm.getDismissEndTarget();
            splitTarget = mSnapAlgorithm.getLastSplitTarget();
        }
        if (dismissTarget != null && fraction > 0f
                && isDismissing(splitTarget, position, dockSide)) {
            fraction = calculateParallaxDismissingFraction(fraction);
            int offsetPosition = (int) (taskPosition +
                    fraction * (dismissTarget.position - splitTarget.position));
            int width = taskRect.width();
            int height = taskRect.height();
            switch (dockSide) {
                case WindowManager.DOCKED_LEFT:
                    taskRect.left = offsetPosition - width;
                    taskRect.right = offsetPosition;
                    break;
                case WindowManager.DOCKED_RIGHT:
                    taskRect.left = offsetPosition + mDividerSize;
                    taskRect.right = offsetPosition + width + mDividerSize;
                    break;
                case WindowManager.DOCKED_TOP:
                    taskRect.top = offsetPosition - height;
                    taskRect.bottom = offsetPosition;
                    break;
                case WindowManager.DOCKED_BOTTOM:
                    taskRect.top = offsetPosition + mDividerSize;
                    taskRect.bottom = offsetPosition + height + mDividerSize;
                    break;
            }
        }
    }

    /**
     * @return for a specified {@code fraction}, this returns an adjusted value that simulates a
     *         slowing down parallax effect
     */
    private static float calculateParallaxDismissingFraction(float fraction) {
        return SLOWDOWN_INTERPOLATOR.getInterpolation(fraction) / 3.5f;
    }

    private static boolean isDismissing(SnapTarget snapTarget, int position, int dockSide) {
        if (dockSide == WindowManager.DOCKED_TOP || dockSide == WindowManager.DOCKED_LEFT) {
            return position < snapTarget.position;
        } else {
            return position > snapTarget.position;
        }
    }

    private int getStackIdForDismissTarget(SnapTarget dismissTarget) {
        if (dismissTarget.flag == SnapTarget.FLAG_DISMISS_START &&
                (mDockSide == WindowManager.DOCKED_LEFT || mDockSide == WindowManager.DOCKED_TOP)) {
            return StackId.DOCKED_STACK_ID;
        } else {
            return StackId.FULLSCREEN_WORKSPACE_STACK_ID;
        }
    }

    /**
     * @return true if and only if {@code dockSide} is top or left
     */
    private static boolean dockSideTopLeft(int dockSide) {
        return dockSide == WindowManager.DOCKED_TOP || dockSide == WindowManager.DOCKED_LEFT;
    }

    /**
     * @return true if and only if {@code dockSide} is bottom or right
     */
    private static boolean dockSideBottomRight(int dockSide) {
        return dockSide == WindowManager.DOCKED_BOTTOM || dockSide == WindowManager.DOCKED_RIGHT;
    }

    @Override
    public void onComputeInternalInsets(InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        inoutInfo.touchableRegion.set(mHandle.getLeft(), mHandle.getTop(), mHandle.getRight(),
                mHandle.getBottom());
        inoutInfo.touchableRegion.op(mBackground.getLeft(), mBackground.getTop(),
                mBackground.getRight(), mBackground.getBottom(), Op.UNION);
    }

    public final void onBusEvent(RecentsActivityStartingEvent recentsActivityStartingEvent) {
        if (mGrowRecents && getWindowManagerProxy().getDockSide() == WindowManager.DOCKED_TOP
                && getCurrentPosition() == getSnapAlgorithm().getLastSplitTarget().position) {
            mGrowAfterRecentsDrawn = true;
            startDragging(false /* animate */, false /* touching */);
        }
    }

    public final void onBusEvent(DockingTopTaskEvent dockingEvent) {
        if (dockingEvent.dragMode == NavigationBarGestureHelper.DRAG_MODE_NONE) {
            mGrowAfterRecentsDrawn = false;
            mAnimateAfterRecentsDrawn = true;
            startDragging(false /* animate */, false /* touching */);
        }
    }

    public final void onBusEvent(RecentsDrawnEvent drawnEvent) {
        if (mAnimateAfterRecentsDrawn) {
            mAnimateAfterRecentsDrawn = false;
            stopDragging(getCurrentPosition(), mSnapAlgorithm.getMiddleTarget(), 250,
                    TOUCH_RESPONSE_INTERPOLATOR);
        }
        if (mGrowAfterRecentsDrawn) {
            mGrowAfterRecentsDrawn = false;
            stopDragging(getCurrentPosition(), mSnapAlgorithm.getMiddleTarget(), 250,
                    TOUCH_RESPONSE_INTERPOLATOR);
        }
    }
}
