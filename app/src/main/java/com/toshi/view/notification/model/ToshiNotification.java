/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.notification.model;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.toshi.R;
import com.toshi.view.BaseApplication;

import java.util.ArrayList;
import java.util.List;

public abstract class ToshiNotification {
    public static final String DEFAULT_TAG = "unknown";

    /* package */ String id;
    private final ArrayList<String> messages;
    private List<String> lastFewMessages;
    private CharSequence lastMessage;
    private static final int MAXIMUM_NUMBER_OF_SHOWN_MESSAGES = 5;
    /* package */ Bitmap largeIcon;

    /* package */ ToshiNotification() {
        this.messages = new ArrayList<>();
        generateLatestMessages(this.messages);
        generateId();
    }

    public void addUnreadMessage(final String unreadMessage) {
        this.messages.add(unreadMessage);
        generateLatestMessages(this.messages);
    }

    private synchronized void generateLatestMessages(final ArrayList<String> messages) {
        if (messages.size() == 0) {
            this.lastMessage = "";
            this.lastFewMessages = new ArrayList<>(0);
            return;
        }

        this.lastMessage = messages.get(messages.size() -1);

        final int end = Math.max(messages.size(), 0);
        final int start = Math.max(end - MAXIMUM_NUMBER_OF_SHOWN_MESSAGES, 0);
        this.lastFewMessages = messages.subList(start, end);
    }

    /* package */ abstract void generateId();

    public String getId() {
        return this.id;
    }

    public String getTag() {
        return DEFAULT_TAG;
    }

    public abstract String getTitle();

    public Bitmap getLargeIcon() {
        return this.largeIcon;
    }

    public CharSequence getLastMessage() {
        return this.lastMessage;
    }

    public List<String> getLastFewMessages() {
        return new ArrayList<>(this.lastFewMessages);
    }

    public int getNumberOfUnreadMessages() {
        return this.messages.size();
    }

    /* package */ Bitmap setDefaultLargeIcon() {
        return this.largeIcon = BitmapFactory.decodeResource(BaseApplication.get().getResources(), R.mipmap.ic_launcher);
    }
}
