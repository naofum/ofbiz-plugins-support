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
package org.apache.ofbiz.support.audit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ofbiz.base.util.UtilValidate;

/**
 * Masks personal data for places where a caller has no permission to see the
 * original value. Audit details never contain message bodies or addresses;
 * these helpers are used at API boundaries and by operational tooling.
 */
public final class SupportDataMasker {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b([A-Z0-9._%+-])([A-Z0-9._%+-]*)@([A-Z0-9.-]+\\.[A-Z]{2,})\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(\\+?\\d{1,4}[-\\s]?)?(\\d{2,4})[-\\s]?(\\d{3,4})[-\\s]?(\\d{4})(?!\\d)");
    private static final Pattern ACCOUNT = Pattern.compile("(?<!\\d)(\\d{8,})(?!\\d)");

    private SupportDataMasker() {
    }

    public static String maskEmail(String value) {
        if (UtilValidate.isEmpty(value)) {
            return value;
        }
        Matcher matcher = EMAIL.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, matcher.group(1) + "***@" + matcher.group(3));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String maskPhone(String value) {
        if (UtilValidate.isEmpty(value)) {
            return value;
        }
        Matcher matcher = PHONE.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String lastFour = matcher.group(4);
            matcher.appendReplacement(result, "***-****-" + lastFour);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String maskAccount(String value) {
        if (UtilValidate.isEmpty(value)) {
            return value;
        }
        Matcher matcher = ACCOUNT.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String digits = matcher.group(1);
            matcher.appendReplacement(result, "****" + digits.substring(digits.length() - 4));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Masks common email, phone and long account-number shapes in free text.
     * The original value is never logged by this class.
     */
    public static String maskText(String value) {
        if (UtilValidate.isEmpty(value)) {
            return value;
        }
        return maskAccount(maskPhone(maskEmail(value)));
    }
}
