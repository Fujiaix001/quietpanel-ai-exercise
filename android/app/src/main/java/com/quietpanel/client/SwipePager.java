package com.quietpanel.client;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ViewFlipper;

public final class SwipePager extends ViewFlipper {
    public interface Listener {
        void onSwipe(int direction);
    }

    private final int touchSlop;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean swiping;
    private Listener listener;

    public SwipePager(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            lastX = downX;
            lastY = downY;
            swiping = false;
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            updateSwipeState(event);
            return swiping;
        }

        return swiping;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            lastX = downX;
            lastY = downY;
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            float dx = lastX - downX;
            int minimumSwipe = touchSlop * 4;
            if (swiping && Math.abs(dx) >= minimumSwipe && listener != null) {
                listener.onSwipe(dx < 0 ? 1 : -1);
            }
            swiping = false;
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            updateSwipeState(event);
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            swiping = false;
            return true;
        }

        return true;
    }

    private void updateSwipeState(MotionEvent event) {
        lastX = event.getX();
        lastY = event.getY();
        float dx = lastX - downX;
        float dy = lastY - downY;
        if (!swiping
                && Math.abs(dx) > touchSlop
                && Math.abs(dx) > Math.abs(dy) * 1.2f) {
            swiping = true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
