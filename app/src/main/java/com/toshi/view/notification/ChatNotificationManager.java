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

package com.toshi.view.notification;


import android.app.NotificationManager;
import android.content.Context;
import android.support.v4.app.NotificationCompat;

import com.toshi.crypto.signal.model.DecryptedSignalMessage;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.User;
import com.toshi.model.sofa.SofaAdapters;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.model.sofa.SofaType;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.notification.model.ChatNotification;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ChatNotificationManager extends ToshiNotificationBuilder {

    private static String currentlyOpenConversation;
    private static final Map<String, ChatNotification> activeNotifications = new HashMap<>();

    public static void suppressNotificationsForConversation(final String conversationId) {
        currentlyOpenConversation = conversationId;
        final NotificationManager manager = (NotificationManager) BaseApplication.get().getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(conversationId, 1);
        handleNotificationDismissed(conversationId);
    }

    public static void handleNotificationDismissed(final String notificationTag) {
        activeNotifications.remove(notificationTag);
    }

    public static void stopNotificationSuppression(final String conversationId) {
        // By passing conversationId we ensure that a second activity
        // doesn't accidentally get unsubscribed by the first activity
        // being destroyed
        if (conversationId.equals(currentlyOpenConversation)) {
            currentlyOpenConversation = null;
        }
    }

    public static void showNotification(final DecryptedSignalMessage signalMessage) {
        if (signalMessage == null) return;

        BaseApplication
            .get()
            .getRecipientManager()
            .getUserFromToshiId(signalMessage.getSource())
            .subscribe(
                    (user) -> handleUserLookup(user, signalMessage),
                    ChatNotificationManager::handleUserError
            );
    }

    private static void handleUserLookup(final User user, final DecryptedSignalMessage signalMessage) {
        final String body = getBodyFromMessage(signalMessage);
        if (body == null) {
            // This wasn't a SOFA::Message. Do not render.
            LogUtil.i(ChatNotificationManager.class, "Not rendering PN");
            return;
        }
        final Recipient recipient = new Recipient(user);
        // Todo - pass group avatar here (if it's a group)
        showChatNotification(recipient, body);
    }

    private static void handleUserError(final Throwable throwable) {
        LogUtil.exception(ChatNotificationManager.class, "Error during fetching user", throwable);
    }

    private static String getBodyFromMessage(final DecryptedSignalMessage dsm) {
        final SofaMessage sofaMessage = new SofaMessage().makeNew(dsm.getBody());
        try {
            if (sofaMessage.getType() == SofaType.PLAIN_TEXT) {
                return SofaAdapters.get().messageFrom(sofaMessage.getPayload()).getBody();
            }
        } catch (final IOException ex) {
            // Nop
        }
        return null;
    }

    public static void showChatNotification(
            final Recipient sender,
            final String content) {

        // Sender will be null if the transaction came from outside of the Toshi platform.
        final String notificationKey = sender == null ? ChatNotification.DEFAULT_TAG : sender.getThreadId();

        if (notificationKey.equals(currentlyOpenConversation) && !BaseApplication.get().isInBackground()) {
            return;
        }

        final ChatNotification activeChatNotification
                = activeNotifications.get(notificationKey) != null
                ? activeNotifications.get(notificationKey)
                : new ChatNotification(sender);

        activeNotifications.put(notificationKey, activeChatNotification);

        activeChatNotification.addUnreadMessage(content);
        activeChatNotification
                .generateLargeIcon()
                .subscribe(() -> showNotification(activeChatNotification, getChatNotificationBuilder(activeChatNotification)));
    }

    private static NotificationCompat.Builder getChatNotificationBuilder(final ChatNotification activeChatNotification) {
        return buildNotification(activeChatNotification)
                .setDeleteIntent(activeChatNotification.getDeleteIntent())
                .setContentIntent(activeChatNotification.getPendingIntent());
    }
}
