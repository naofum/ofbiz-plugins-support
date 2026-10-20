/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.support.sla;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * STEP S3: adds minutes to a timestamp using a core TechDataCalendar.
 *
 * <p>The calendar week defines the start time and capacity (minutes) of each weekday.
 * {@code TechDataCalendarExcDay} can close a day (capacity 0) or change its capacity.
 * If the calendar or its week is not found, the calculator falls back to calendar minutes.
 * {@code TechDataCalendarExcWeek} is not applied in this step.
 */
public final class SupportSlaCalculator {

    private static final String[] DAY_NAMES = {"sunday", "monday", "tuesday", "wednesday",
            "thursday", "friday", "saturday"};

    private SupportSlaCalculator() {
    }

    public static Timestamp addMinutes(Delegator delegator, Timestamp start, long minutes, String calendarId)
            throws GenericEntityException {
        if (start == null) {
            start = new Timestamp(System.currentTimeMillis());
        }
        if (minutes <= 0 || calendarId == null || calendarId.isEmpty()) {
            return new Timestamp(start.getTime() + Math.max(minutes, 0) * 60_000L);
        }
        GenericValue calendar = EntityQuery.use(delegator).from("TechDataCalendar")
                .where("calendarId", calendarId).queryOne();
        if (calendar == null) {
            return new Timestamp(start.getTime() + minutes * 60_000L);
        }
        GenericValue week = EntityQuery.use(delegator).from("TechDataCalendarWeek")
                .where("calendarWeekId", calendar.getString("calendarWeekId")).queryOne();
        if (week == null) {
            return new Timestamp(start.getTime() + minutes * 60_000L);
        }

        Map<String, Double> exceptionCapacity = new HashMap<>();
        List<GenericValue> exceptions = EntityQuery.use(delegator).from("TechDataCalendarExcDay")
                .where("calendarId", calendarId).queryList();
        SimpleDateFormat dateKey = new SimpleDateFormat("yyyyMMdd");
        for (GenericValue exception : exceptions) {
            Timestamp exceptionDate = exception.getTimestamp("exceptionDateStartTime");
            if (exceptionDate != null) {
                exceptionCapacity.put(dateKey.format(exceptionDate), exception.getDouble("exceptionCapacity"));
            }
        }

        Calendar current = Calendar.getInstance();
        current.setTimeInMillis(start.getTime());
        long remaining = minutes;
        int guard = 0;
        while (remaining > 0 && guard++ < 1000) {
            String dayName = DAY_NAMES[current.get(Calendar.DAY_OF_WEEK) - 1];
            Object rawStartTime = week.get(dayName + "StartTime");
            Double capacity = week.getDouble(dayName + "Capacity");
            String key = dateKey.format(current.getTime());
            if (exceptionCapacity.containsKey(key)) {
                capacity = exceptionCapacity.get(key);
            }
            if (!(rawStartTime instanceof Date) || capacity == null || capacity <= 0) {
                nextDay(current);
                continue;
            }
            Calendar timeOfDay = Calendar.getInstance();
            timeOfDay.setTime((Date) rawStartTime);
            Calendar dayStart = (Calendar) current.clone();
            dayStart.set(Calendar.HOUR_OF_DAY, timeOfDay.get(Calendar.HOUR_OF_DAY));
            dayStart.set(Calendar.MINUTE, timeOfDay.get(Calendar.MINUTE));
            dayStart.set(Calendar.SECOND, 0);
            dayStart.set(Calendar.MILLISECOND, 0);
            if (current.before(dayStart)) {
                current.setTimeInMillis(dayStart.getTimeInMillis());
            }
            Calendar dayEnd = (Calendar) dayStart.clone();
            dayEnd.add(Calendar.MINUTE, (int) Math.round(capacity));
            if (!current.before(dayEnd)) {
                nextDay(current);
                continue;
            }
            long available = (dayEnd.getTimeInMillis() - current.getTimeInMillis()) / 60_000L;
            if (remaining <= available) {
                current.add(Calendar.MINUTE, (int) remaining);
                return new Timestamp(current.getTimeInMillis());
            }
            remaining -= available;
            nextDay(current);
        }
        return new Timestamp(start.getTime() + minutes * 60_000L);
    }

    private static void nextDay(Calendar calendar) {
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
