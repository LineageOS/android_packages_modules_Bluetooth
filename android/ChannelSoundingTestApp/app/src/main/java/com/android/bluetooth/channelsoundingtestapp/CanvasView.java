/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.bluetooth.channelsoundingtestapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

class CanvasView extends View {
    private static final String LOG_TAG = "CanvasView";
    private static final int VIEW_HEIGHT = 750;
    private static final int VIEW_WIDTH = 1000;
    private static final int START_X = 100;
    private static final int END_X = VIEW_WIDTH - 50;
    private static final int START_Y = 80;
    private static final int END_Y = VIEW_HEIGHT - 100;
    private static final int MAX_NODE_SIZE = 20;
    private static final int INITIAL_MAX_Y = 5;

    private final ArrayList<Node> mDataList;
    private final Paint mPaint;
    private final Paint mTextPaint;
    private final Paint mPointPaint;
    private final String mTitle;

    private int mMaxYValue = INITIAL_MAX_Y;
    private int mNodeCount = 1;
    private int mPreviousY = END_Y;

    CanvasView(Context context, String title) {
        super(context);
        setLayoutParams(new ViewGroup.LayoutParams(VIEW_WIDTH, VIEW_WIDTH));
        mDataList = new ArrayList<Node>();
        mTitle = title;
        mPaint = new Paint();
        mTextPaint = new Paint();
        mPointPaint = new Paint();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        mTextPaint.setTextSize(24);

        mPaint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);

        mPaint.setColor(Color.GRAY);
        mPaint.setStrokeWidth(3);
        canvas.drawLine(START_X, START_Y, START_X, END_Y, mPaint);
        canvas.drawLine(START_X, END_Y, END_X, END_Y, mPaint);

        // Draw line
        mPaint.setStrokeWidth(1);
        mPaint.setColor(Color.GRAY);
        int intervalY = (END_Y - START_Y) / 5;
        for (int i = 1; i <= 5; i++) {
            int y = END_Y - intervalY * i;
            int yValue = mMaxYValue / 5 * i;
            canvas.drawLine(START_X, y, END_X, y, mPaint);
            canvas.drawText(yValue + "", 40, y, mTextPaint);
        }
        canvas.drawText("0", 40, END_Y, mTextPaint);

        // DrawTitle
        mTextPaint.setTextSize(32);
        canvas.drawText(mTitle, getWidth() / 2 - mTitle.length() * 6, START_Y - 30, mTextPaint);

        // DrawNode
        int intervalX = (END_X - START_X) / MAX_NODE_SIZE;
        int currentX = START_X + intervalX;
        mPaint.reset();
        mPaint.setTextSize(16);
        mTextPaint.setTextSize(24);

        mPointPaint.setColor(Color.BLUE);
        mPointPaint.setStrokeWidth(3);

        // Draw first node
        for (int i = 0; i < mDataList.size(); i++) {
            if (mDataList.get(i).abort) {
                mPointPaint.setColor(Color.RED);
                canvas.drawLine(
                        currentX - intervalX, mPreviousY, currentX, mPreviousY, mPointPaint);
                canvas.drawCircle(currentX, mPreviousY, 5, mPointPaint);
                canvas.drawText("abort", currentX - 15, mPreviousY - 10, mPaint);
            } else {
                mPointPaint.setColor(Color.BLUE);
                double distance = mDataList.get(i).value;
                int y = END_Y - (int) ((END_Y - START_Y) * (distance / mMaxYValue));
                canvas.drawLine(currentX - intervalX, mPreviousY, currentX, y, mPointPaint);
                canvas.drawCircle(currentX, y, 5, mPointPaint);
                canvas.drawText(distance + "", currentX - 15, y - 10, mPaint);
                mPreviousY = y;
            }

            // number text
            String number = mDataList.get(i).number + "";
            if (mDataList.get(i).number % (MAX_NODE_SIZE / 20 * 5) == 0) {
                if (number.length() > 2) {
                    canvas.rotate(-60, currentX - 15, END_Y + 50);
                    canvas.drawText(number, currentX - 15, END_Y + 50, mTextPaint);
                    canvas.rotate(60, currentX - 15, END_Y + 50);
                } else {
                    canvas.drawText(number, currentX, END_Y + 30, mTextPaint);
                }
            }
            currentX += intervalX;
        }
    }

    void cleanUp() {
        mDataList.clear();
        mNodeCount = 0;
        mMaxYValue = INITIAL_MAX_Y;
        invalidate();
    }

    void addNode(double distance, boolean abort) {
        Log.d(LOG_TAG, "Add Node " + mNodeCount + " with distance:" + distance);
        if (abort && !mDataList.isEmpty()) {
            distance = mDataList.get(mDataList.size() - 1).value;
        }
        mDataList.add(new Node(distance, mNodeCount++, abort));
        if (distance > mMaxYValue) {
            mMaxYValue = ((int) (distance / 10)) * 10 + 10;
        }

        if (mDataList.size() > MAX_NODE_SIZE) {
            mPreviousY = END_Y - (int) ((END_Y - START_Y) * (mDataList.get(0).value / mMaxYValue));
            mDataList.remove(0);
        } else {
            mPreviousY = END_Y;
        }
        invalidate();
    }

    static class Node {
        private final double value;
        private final int number;
        private final boolean abort;

        Node(double value, int number, boolean abort) {
            this.value = value;
            this.number = number;
            this.abort = abort;
        }
    }
}
