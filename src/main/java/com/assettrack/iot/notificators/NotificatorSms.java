/*
 * Copyright 2017 - 2024 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 - 2018 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.assettrack.iot.notificators;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import com.assettrack.iot.database.StatisticsManager;
import com.assettrack.iot.model.Event;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.model.User;
import com.assettrack.iot.notification.MessageException;
import com.assettrack.iot.notification.NotificationFormatter;
import com.assettrack.iot.notification.NotificationMessage;
import com.assettrack.iot.sms.SmsManager;

@Singleton
public class NotificatorSms extends Notificator {

    private final SmsManager smsManager;
    private final StatisticsManager statisticsManager;

    @Inject
    public NotificatorSms(
            SmsManager smsManager, NotificationFormatter notificationFormatter, StatisticsManager statisticsManager) {
        super(notificationFormatter, "short");
        this.smsManager = smsManager;
        this.statisticsManager = statisticsManager;
    }

    @Override
    public void send(User user, NotificationMessage message, Event event, Position position) throws MessageException {
        if (user.getPhone() != null) {
            statisticsManager.registerSms();
            smsManager.sendMessage(user.getPhone(), message.getBody(), false);
        }
    }

}
