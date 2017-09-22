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


import android.app.PendingIntent;
import android.content.Intent;
import android.support.v4.app.TaskStackBuilder;

import com.bumptech.glide.Glide;
import com.toshi.R;
import com.toshi.model.local.Recipient;
import com.toshi.service.NotificationDismissedReceiver;
import com.toshi.view.BaseApplication;
import com.toshi.view.activity.ChatActivity;
import com.toshi.view.activity.MainActivity;
import com.toshi.view.activity.SplashActivity;
import com.toshi.view.custom.CropCircleTransformation;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

import rx.Completable;

public class ChatNotification extends ToshiNotification {

    private final Recipient sender;

    public ChatNotification(final Recipient sender) {
        super();
        this.sender = sender;
    }

    @Override
    /* package */ void generateId() {
        this.id = this.sender == null
                ? UUID.randomUUID().toString()
                : this.sender.getThreadId();
    }

    @Override
    public String getTag() {
        return this.sender == null ? DEFAULT_TAG : sender.getThreadId();
    }

    @Override
    public String getTitle() {
        return this.sender == null
                ? BaseApplication.get().getString(R.string.unknown_sender)
                : this.sender.getDisplayName();
    }

    public PendingIntent getPendingIntent() {
        final Intent mainIntent = new Intent(BaseApplication.get(), MainActivity.class);
        mainIntent.putExtra(MainActivity.EXTRA__ACTIVE_TAB, 1);

        if (this.sender == null || this.sender.getThreadId() == null) {
            return TaskStackBuilder.create(BaseApplication.get())
                    .addParentStack(MainActivity.class)
                    .addNextIntent(mainIntent)
                    .getPendingIntent(0, PendingIntent.FLAG_ONE_SHOT);
        }

        final Intent chatIntent = new Intent(BaseApplication.get(), ChatActivity.class)
                .putExtra(ChatActivity.EXTRA__THREAD_ID, this.sender.getThreadId())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        final PendingIntent nextIntent = TaskStackBuilder.create(BaseApplication.get())
                .addParentStack(MainActivity.class)
                .addNextIntent(mainIntent)
                .addNextIntent(chatIntent)
                .getPendingIntent(getTitle().hashCode(), PendingIntent.FLAG_ONE_SHOT);

        final Intent splashIntent = new Intent(BaseApplication.get(), SplashActivity.class);
        splashIntent.putExtra(SplashActivity.EXTRA__NEXT_INTENT, nextIntent);

        return PendingIntent.getActivity(
                BaseApplication.get(),
                getTitle().hashCode(),
                splashIntent,
                PendingIntent.FLAG_ONE_SHOT);
    }

    public PendingIntent getDeleteIntent() {
        final Intent intent =
                new Intent(BaseApplication.get(), NotificationDismissedReceiver.class)
                        .putExtra(NotificationDismissedReceiver.TAG, getTag());

        return PendingIntent.getBroadcast(
                BaseApplication.get(),
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public Completable generateLargeIcon() {
        if (this.largeIcon != null) return Completable.complete();
        if (getAvatarUri() == null) return Completable.fromAction(this::setDefaultLargeIcon);

        return Completable.fromAction(() -> {
            try {
                fetchUserAvatar();
            } catch (InterruptedException | ExecutionException e) {
                setDefaultLargeIcon();
            }
        });
    }

    private void fetchUserAvatar() throws InterruptedException, ExecutionException {
        this.largeIcon = Glide
                        .with(BaseApplication.get())
                        .load(getAvatarUri())
                        .asBitmap()
                        .transform(new CropCircleTransformation(BaseApplication.get()))
                        .into(200, 200)
                        .get();
    }

    private String getAvatarUri() {
        return this.sender == null
                ? null
                : this.sender.getAvatar();
    }
}
