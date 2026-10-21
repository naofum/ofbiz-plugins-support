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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP S6: logical deletion, anonymization and retention.
 */
public final class SupportRetentionServices {

    private static final String RESOURCE = "SupportUiLabels";
    private static final String MODE_DELETE = "DELETE";
    private static final String MODE_ANONYMIZE = "ANONYMIZE";

    private SupportRetentionServices() {
    }

    public static Map<String, Object> deleteSupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        try {
            GenericValue supportCase = findCaseRaw(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            if ("Y".equals(supportCase.getString("isDeleted"))) {
                return ServiceUtil.returnSuccess(message(locale, "SupportCaseDeleted"));
            }
            markDeleted(delegator, context, supportCase, "Manual logical delete");
            return ServiceUtil.returnSuccess(message(locale, "SupportCaseDeleted"));
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> anonymizeSupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        try {
            GenericValue supportCase = findCaseRaw(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            anonymize(delegator, context, supportCase, "Manual anonymization");
            return ServiceUtil.returnSuccess(message(locale, "SupportCaseAnonymized"));
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Applies the configured retention rule to closed/cancelled cases. The
     * default is logical deletion; ANONYMIZE replaces subject, description and
     * message content while keeping the case and audit trail.
     */
    public static Map<String, Object> applySupportRetention(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            long days = context.get("retentionDays") != null
                    ? Long.parseLong(context.get("retentionDays").toString())
                    : (long) UtilProperties.getPropertyNumber("support", "support.retention.days", 2555);
            String mode = getString(context, "mode");
            if (UtilValidate.isEmpty(mode)) {
                mode = UtilProperties.getPropertyValue("support", "support.retention.mode", MODE_DELETE);
            }
            int limit = context.get("limit") == null ? 100 : Integer.parseInt(context.get("limit").toString());
            Timestamp cutoff = new Timestamp(UtilDateTime.nowTimestamp().getTime() - days * 24L * 60L * 60L * 1000L);

            List<EntityCondition> conditions = new ArrayList<>();
            conditions.add(EntityCondition.makeCondition(EntityOperator.OR,
                    EntityCondition.makeCondition("isDeleted", EntityOperator.EQUALS, null),
                    EntityCondition.makeCondition("isDeleted", "N")));
            conditions.add(EntityCondition.makeCondition("closedAt", EntityOperator.LESS_THAN_EQUAL_TO, cutoff));
            List<GenericValue> cases = EntityQuery.use(delegator).from("SupportCase")
                    .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                    .orderBy("closedAt").limit(limit).queryList();

            int deletedCount = 0;
            int anonymizedCount = 0;
            for (GenericValue supportCase : cases) {
                if (MODE_ANONYMIZE.equalsIgnoreCase(mode)) {
                    anonymize(delegator, context, supportCase, "Retention anonymization");
                    anonymizedCount++;
                } else {
                    markDeleted(delegator, context, supportCase, "Retention logical delete");
                    deletedCount++;
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("deletedCount", deletedCount);
            result.put("anonymizedCount", anonymizedCount);
            result.put("retentionDays", days);
            result.put("mode", mode);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void markDeleted(Delegator delegator, Map<String, Object> context, GenericValue supportCase,
            String summary) throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        String actor = actor(context);
        supportCase.set("isDeleted", "Y");
        supportCase.set("deletedAt", now);
        supportCase.set("deletedByUserLogin", actor);
        supportCase.set("lastModifiedDate", now);
        supportCase.set("lastModifiedByUserLogin", actor);
        supportCase.store();
        SupportAuditServices.record(delegator, context, supportCase.getString("supportCaseId"),
                SupportAuditServices.ACTION_DELETE, SupportAuditServices.CHANNEL_SYSTEM, null, summary, null);
    }

    private static void anonymize(Delegator delegator, Map<String, Object> context, GenericValue supportCase,
            String summary) throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        String actor = actor(context);
        supportCase.set("subject", "[anonymized]");
        supportCase.set("description", "[anonymized]");
        supportCase.set("satisfactionComment", null);
        supportCase.set("anonymizedAt", now);
        supportCase.set("lastModifiedDate", now);
        supportCase.set("lastModifiedByUserLogin", actor);
        supportCase.store();

        List<GenericValue> links = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                .where("supportCaseId", supportCase.getString("supportCaseId")).queryList();
        for (GenericValue link : links) {
            GenericValue event = EntityQuery.use(delegator).from("CommunicationEvent")
                    .where("communicationEventId", link.getString("communicationEventId")).queryOne();
            if (event == null) {
                continue;
            }
            event.set("subject", "[anonymized]");
            event.set("content", "[anonymized]");
            event.set("fromString", null);
            event.set("toString", null);
            event.set("contactMechIdFrom", null);
            event.set("contactMechIdTo", null);
            event.store();
        }
        SupportAuditServices.record(delegator, context, supportCase.getString("supportCaseId"),
                SupportAuditServices.ACTION_ANONYMIZE, SupportAuditServices.CHANNEL_SYSTEM, null, summary, null);
    }

    private static GenericValue findCaseRaw(Delegator delegator, String supportCaseId) throws GenericEntityException {
        if (UtilValidate.isEmpty(supportCaseId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("SupportCase").where("supportCaseId", supportCaseId).queryOne();
    }

    private static String actor(Map<String, Object> context) {
        GenericValue userLogin = context == null ? null : (GenericValue) context.get("userLogin");
        return userLogin == null ? "system" : userLogin.getString("userLoginId");
    }

    private static String getString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }
}
