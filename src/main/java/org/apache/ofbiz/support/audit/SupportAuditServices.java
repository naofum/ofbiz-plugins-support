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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilDateTime;
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
 * STEP S6: support audit logging.
 *
 * <p>Audit rows intentionally contain IDs, action codes and field names only.
 * Message bodies, email addresses, phone numbers and account numbers must not
 * be written to this table. Use {@link SupportDataMasker} at API boundaries
 * when a caller is not allowed to see the original value.</p>
 */
public final class SupportAuditServices {

    public static final String ACTION_CREATE = "SUPAUD_CREATE";
    public static final String ACTION_UPDATE = "SUPAUD_UPDATE";
    public static final String ACTION_ASSIGN = "SUPAUD_ASSIGN";
    public static final String ACTION_STATUS = "SUPAUD_STATUS";
    public static final String ACTION_VIEW = "SUPAUD_VIEW";
    public static final String ACTION_DOWNLOAD = "SUPAUD_DOWNLOAD";
    public static final String ACTION_UPLOAD = "SUPAUD_UPLOAD";
    public static final String ACTION_DELETE = "SUPAUD_DELETE";
    public static final String ACTION_ANONYMIZE = "SUPAUD_ANONYMIZE";
    public static final String ACTION_RETENTION = "SUPAUD_RETENTION";

    public static final String CHANNEL_STAFF = "SUPAUD_CH_STAFF";
    public static final String CHANNEL_API = "SUPAUD_CH_API";
    public static final String CHANNEL_SYSTEM = "SUPAUD_CH_SYSTEM";

    private SupportAuditServices() {
    }

    /**
     * Writes an audit row in the current transaction. Callers pass only safe
     * identifiers and summaries, never message bodies or contact information.
     */
    public static void record(Delegator delegator, Map<String, Object> context, String supportCaseId,
            String actionEnumId, String channelEnumId, String referenceId, String summary,
            String changedFields) throws GenericEntityException {
        String channel = UtilValidate.isNotEmpty(channelEnumId) ? channelEnumId
                : getString(context, "auditChannelEnumId");
        if (UtilValidate.isEmpty(channel)) {
            channel = CHANNEL_STAFF;
        }
        GenericValue userLogin = context == null ? null : (GenericValue) context.get("userLogin");
        String actorUserLoginId = userLogin == null ? "system" : userLogin.getString("userLoginId");
        String actorPartyId = userLogin == null ? null : userLogin.getString("partyId");
        if (UtilValidate.isEmpty(actorPartyId) && context != null) {
            actorPartyId = getString(context, "auditActorPartyId");
            if (UtilValidate.isEmpty(actorPartyId)) {
                actorPartyId = getString(context, "customerPartyId");
            }
        }
        String safeSummary = SupportDataMasker.maskText(truncate(summary, 255));
        String safeFields = truncate(SupportDataMasker.maskText(changedFields), 255);
        GenericValue audit = delegator.makeValue("SupportAuditLog");
        audit.set("supportAuditLogId", delegator.getNextSeqId("SupportAuditLog"));
        audit.set("supportCaseId", UtilValidate.isEmpty(supportCaseId) ? null : supportCaseId);
        audit.set("actionEnumId", actionEnumId);
        audit.set("channelEnumId", channel);
        audit.set("actorUserLoginId", actorUserLoginId);
        audit.set("actorPartyId", UtilValidate.isEmpty(actorPartyId) ? null : actorPartyId);
        audit.set("referenceId", UtilValidate.isEmpty(referenceId) ? null : referenceId);
        audit.set("summary", safeSummary);
        audit.set("changedFields", safeFields);
        audit.set("actionDate", UtilDateTime.nowTimestamp());
        audit.set("success", "Y");
        audit.create();
    }

    /**
     * Screen/controller hook for a staff case view. The service permission
     * service enforces SUPPORT_VIEW before this method is called.
     */
    public static Map<String, Object> logSupportCaseAccess(DispatchContext dctx, Map<String, Object> context) {
        String supportCaseId = getString(context, "supportCaseId");
        if (UtilValidate.isEmpty(supportCaseId)) {
            return ServiceUtil.returnSuccess();
        }
        try {
            record(dctx.getDelegator(), context, supportCaseId, ACTION_VIEW, CHANNEL_STAFF, null,
                    "Case viewed", null);
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> findSupportAuditLogs(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            List<EntityCondition> conditions = new ArrayList<>();
            addEquals(conditions, "supportCaseId", getString(context, "supportCaseId"));
            addEquals(conditions, "actionEnumId", getString(context, "actionEnumId"));
            addEquals(conditions, "actorUserLoginId", getString(context, "actorUserLoginId"));
            addDateRange(conditions, "actionDate", context.get("fromDate"), context.get("thruDate"));
            EntityQuery query = EntityQuery.use(delegator).from("SupportAuditLog")
                    .orderBy("-actionDate");
            if (!conditions.isEmpty()) {
                query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            }
            List<GenericValue> logs = query.queryList();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("auditLogs", logs);
            result.put("auditLogCount", logs.size());
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(UtilValidate.isEmpty(e.getMessage()) ? "Audit query failed" : e.getMessage());
        }
    }

    private static void addEquals(List<EntityCondition> conditions, String field, String value) {
        if (UtilValidate.isNotEmpty(value)) {
            conditions.add(EntityCondition.makeCondition(field, value));
        }
    }

    private static void addDateRange(List<EntityCondition> conditions, String field, Object from, Object thru) {
        if (from != null) {
            conditions.add(EntityCondition.makeCondition(field, EntityOperator.GREATER_THAN_EQUAL_TO, from));
        }
        if (thru != null) {
            conditions.add(EntityCondition.makeCondition(field, EntityOperator.LESS_THAN_EQUAL_TO, thru));
        }
    }

    private static String getString(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        return value == null ? null : value.toString();
    }

    private static String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }
}
