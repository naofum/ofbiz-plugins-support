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
package org.apache.ofbiz.support.casemgmt;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.support.sla.SupportSlaCalculator;

/**
 * STEP S1: support case lifecycle services.
 *
 * <p>The case status is changed only by the status services in this class. The
 * generic CRUD service never changes it. Messages, routing, SLA escalation and
 * the customer API are implemented in later steps.
 */
public final class SupportCaseServices {

    private static final String RESOURCE = "SupportUiLabels";
    private static final String STATUS_NEW = "SUPCS_NEW";
    private static final String STATUS_OPEN = "SUPCS_OPEN";
    private static final String STATUS_PENDING = "SUPCS_PENDING";
    private static final String STATUS_RESOLVED = "SUPCS_RESOLVED";
    private static final String STATUS_CLOSED = "SUPCS_CLOSED";
    private static final String STATUS_CANCELLED = "SUPCS_CANCELLED";

    private SupportCaseServices() {
    }

    private static String getString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }

    private static String getUserLoginId(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return userLogin == null ? "system" : userLogin.getString("userLoginId");
    }

    private static GenericValue getUserLogin(Delegator delegator, Map<String, Object> context)
            throws GenericEntityException {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin == null) {
            userLogin = EntityQuery.use(delegator).from("UserLogin")
                    .where("userLoginId", "system").queryOne();
        }
        return userLogin;
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }

    private static GenericValue findCase(Delegator delegator, String supportCaseId) throws GenericEntityException {
        if (UtilValidate.isEmpty(supportCaseId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("SupportCase")
                .where("supportCaseId", supportCaseId).queryOne();
    }

    private static void createStatusHistory(Delegator delegator, String supportCaseId, String statusId,
            Timestamp statusDate, String userLoginId, String note) throws GenericEntityException {
        GenericValue history = delegator.makeValue("SupportCaseStatus");
        history.set("supportCaseStatusId", delegator.getNextSeqId("SupportCaseStatus"));
        history.set("supportCaseId", supportCaseId);
        history.set("statusId", statusId);
        history.set("statusDate", statusDate);
        history.set("changedByUserLogin", userLoginId);
        history.set("note", note);
        history.create();
    }

    /**
     * Selects the most general active SLA for the first step. The product/type/priority
     * selection and the business-calendar calculation are added in STEP S3.
     */
    private static GenericValue selectSla(Delegator delegator, GenericValue supportCase) throws GenericEntityException {
        String productId = supportCase.getString("productId");
        String financialProductTypeId = null;
        if (UtilValidate.isNotEmpty(productId)) {
            GenericValue financialProduct = EntityQuery.use(delegator).from("FinancialProduct")
                    .where("productId", productId).queryOne();
            financialProductTypeId = financialProduct == null ? null : financialProduct.getString("financialProductTypeId");
        }
        String contractTypeId = null;
        if (UtilValidate.isNotEmpty(supportCase.getString("financeContractId"))) {
            GenericValue contract = EntityQuery.use(delegator).from("FinanceContract")
                    .where("financeContractId", supportCase.getString("financeContractId")).queryOne();
            contractTypeId = contract == null ? null : contract.getString("contractTypeId");
        }
        BigDecimal priority = decimal(supportCase, "priority");
        String sourceEnumId = supportCase.getString("sourceEnumId");
        Timestamp now = UtilDateTime.nowTimestamp();
        List<GenericValue> slas = EntityQuery.use(delegator).from("SupportSla")
                .where("active", "Y").orderBy("sequenceNum").queryList();
        for (GenericValue sla : slas) {
            if (!effective(sla, now)) {
                continue;
            }
            if (UtilValidate.isNotEmpty(sla.getString("productId")) && !sla.getString("productId").equals(productId)) {
                continue;
            }
            if (UtilValidate.isNotEmpty(sla.getString("financialProductTypeId"))
                    && !sla.getString("financialProductTypeId").equals(financialProductTypeId)) {
                continue;
            }
            if (UtilValidate.isNotEmpty(sla.getString("contractTypeId"))
                    && !sla.getString("contractTypeId").equals(contractTypeId)) {
                continue;
            }
            if (sla.get("priority") != null
                    && (priority == null || decimal(sla, "priority").compareTo(priority) != 0)) {
                continue;
            }
            if (UtilValidate.isNotEmpty(sla.getString("sourceEnumId"))
                    && !sla.getString("sourceEnumId").equals(sourceEnumId)) {
                continue;
            }
            return sla;
        }
        return null;
    }

    private static boolean effective(GenericValue value, Timestamp now) {
        Timestamp fromDate = value.getTimestamp("fromDate");
        Timestamp thruDate = value.getTimestamp("thruDate");
        return (fromDate == null || !fromDate.after(now)) && (thruDate == null || !thruDate.before(now));
    }

    private static BigDecimal decimal(GenericValue value, String field) {
        Object raw = value.get(field);
        return raw == null ? null : new BigDecimal(raw.toString());
    }

    private static void applySla(Delegator delegator, GenericValue supportCase) throws GenericEntityException {
        GenericValue sla = selectSla(delegator, supportCase);
        long firstResponseMinutes = (long) UtilProperties.getPropertyNumber("support",
                "support.sla.default.firstResponseMinutes", 240);
        long resolutionMinutes = (long) UtilProperties.getPropertyNumber("support",
                "support.sla.default.resolutionMinutes", 2880);
        String businessCalendarId = null;
        if (sla != null) {
            if (sla.get("firstResponseMinutes") != null) {
                firstResponseMinutes = sla.getLong("firstResponseMinutes");
            }
            if (sla.get("resolutionMinutes") != null) {
                resolutionMinutes = sla.getLong("resolutionMinutes");
            }
            businessCalendarId = sla.getString("businessCalendarId");
            supportCase.set("slaId", sla.getString("slaId"));
        }
        Timestamp openedAt = supportCase.getTimestamp("openedAt");
        if (openedAt == null) {
            openedAt = UtilDateTime.nowTimestamp();
            supportCase.set("openedAt", openedAt);
        }
        supportCase.set("firstResponseDueAt", SupportSlaCalculator.addMinutes(delegator, openedAt,
                firstResponseMinutes, businessCalendarId));
        supportCase.set("resolutionDueAt", SupportSlaCalculator.addMinutes(delegator, openedAt,
                resolutionMinutes, businessCalendarId));
    }

    private static GenericValue findQueueMapping(Delegator delegator, String productId, String financialProductTypeId,
            String contractTypeId, Timestamp now) throws GenericEntityException {
        List<GenericValue> mappings = EntityQuery.use(delegator).from("SupportQueueProduct")
                .where("active", "Y").orderBy("sequenceNum").queryList();
        for (GenericValue mapping : mappings) {
            if (!effective(mapping, now)) {
                continue;
            }
            if (UtilValidate.isNotEmpty(mapping.getString("productId"))
                    && !mapping.getString("productId").equals(productId)) {
                continue;
            }
            if (UtilValidate.isNotEmpty(mapping.getString("financialProductTypeId"))
                    && !mapping.getString("financialProductTypeId").equals(financialProductTypeId)) {
                continue;
            }
            if (UtilValidate.isNotEmpty(mapping.getString("contractTypeId"))
                    && !mapping.getString("contractTypeId").equals(contractTypeId)) {
                continue;
            }
            return mapping;
        }
        return null;
    }

    private static GenericValue chooseQueueMember(Delegator delegator, String queueId, Timestamp now)
            throws GenericEntityException {
        List<GenericValue> members = EntityQuery.use(delegator).from("SupportQueueMember")
                .where("queueId", queueId, "active", "Y").queryList();
        GenericValue selected = null;
        long selectedLoad = Long.MAX_VALUE;
        Timestamp selectedLastAssigned = null;
        for (GenericValue member : members) {
            if (!effective(member, now)) {
                continue;
            }
            long openCases = EntityQuery.use(delegator).from("SupportCase")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("assigneePartyId", member.getString("partyId")),
                            EntityCondition.makeCondition("statusId", EntityOperator.IN,
                                    List.of(STATUS_NEW, STATUS_OPEN, "SUPCS_PENDING"))))
                    .queryCount();
            if (member.get("capacity") != null && openCases >= member.getLong("capacity")) {
                continue;
            }
            Timestamp lastAssigned = member.getTimestamp("lastAssignedDate");
            if (selected == null || openCases < selectedLoad
                    || (openCases == selectedLoad
                            && (selectedLastAssigned == null
                                    || (lastAssigned != null && lastAssigned.before(selectedLastAssigned))))) {
                selected = member;
                selectedLoad = openCases;
                selectedLastAssigned = lastAssigned;
            }
        }
        return selected;
    }

    private static void routeSupportCase(Delegator delegator, GenericValue supportCase, Timestamp now)
            throws GenericEntityException {
        String productId = supportCase.getString("productId");
        String financialProductTypeId = null;
        if (UtilValidate.isNotEmpty(productId)) {
            GenericValue financialProduct = EntityQuery.use(delegator).from("FinancialProduct")
                    .where("productId", productId).queryOne();
            financialProductTypeId = financialProduct == null ? null : financialProduct.getString("financialProductTypeId");
        }
        String contractTypeId = null;
        if (UtilValidate.isNotEmpty(supportCase.getString("financeContractId"))) {
            GenericValue contract = EntityQuery.use(delegator).from("FinanceContract")
                    .where("financeContractId", supportCase.getString("financeContractId")).queryOne();
            contractTypeId = contract == null ? null : contract.getString("contractTypeId");
        }
        String queueId = supportCase.getString("queueId");
        if (UtilValidate.isEmpty(queueId)) {
            GenericValue mapping = findQueueMapping(delegator, productId, financialProductTypeId, contractTypeId, now);
            if (mapping != null) {
                queueId = mapping.getString("queueId");
            } else if (UtilValidate.isEmpty(productId) && UtilValidate.isEmpty(supportCase.getString("financeContractId"))) {
                queueId = UtilProperties.getPropertyValue("support", "support.triage.queue.id", "SUPPORT_TRIAGE");
            } else {
                queueId = UtilProperties.getPropertyValue("support", "support.default.queue.id", "SUPPORT_DEFAULT");
            }
            supportCase.set("queueId", queueId);
        }
        if (UtilValidate.isEmpty(supportCase.getString("assigneePartyId"))) {
            GenericValue queue = EntityQuery.use(delegator).from("SupportQueue").where("queueId", queueId).queryOne();
            if (queue == null || !"Y".equals(queue.getString("active"))) {
                return;
            }
            GenericValue member = chooseQueueMember(delegator, queueId, now);
            if (member != null) {
                supportCase.set("assigneePartyId", member.getString("partyId"));
                member.set("lastAssignedDate", now);
                member.store();
            }
        }
    }

    private static void setIfPresent(GenericValue value, Map<String, Object> context, String field) {
        if (context.containsKey(field)) {
            value.set(field, context.get(field));
        }
    }

    public static Map<String, Object> createSupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        Timestamp now = UtilDateTime.nowTimestamp();
        String userLoginId = getUserLoginId(context);
        try {
            GenericValue supportCase = delegator.makeValue("SupportCase");
            supportCase.setNonPKFields(context);
            String supportCaseId = delegator.getNextSeqId("SupportCase");
            supportCase.set("supportCaseId", supportCaseId);
            supportCase.set("statusId", STATUS_NEW);
            if (UtilValidate.isEmpty(supportCase.getString("caseTypeEnumId"))) {
                supportCase.set("caseTypeEnumId", "SUPCT_INQUIRY");
            }
            if (UtilValidate.isEmpty(supportCase.getString("sourceEnumId"))) {
                supportCase.set("sourceEnumId", "SUPCSRC_STAFF");
            }
            if (supportCase.get("priority") == null) {
                supportCase.set("priority", BigDecimal.valueOf(5));
            }
            if (supportCase.get("openedAt") == null) {
                supportCase.set("openedAt", now);
            }
            routeSupportCase(delegator, supportCase, now);
            supportCase.set("escalationLevel", BigDecimal.ZERO);
            supportCase.set("reopenedCount", BigDecimal.ZERO);
            supportCase.set("createdDate", now);
            supportCase.set("lastModifiedDate", now);
            supportCase.set("createdByUserLogin", userLoginId);
            supportCase.set("lastModifiedByUserLogin", userLoginId);
            applySla(delegator, supportCase);
            supportCase.create();
            createStatusHistory(delegator, supportCaseId, STATUS_NEW, now, userLoginId, null);
            Map<String, Object> result = ServiceUtil.returnSuccess(message(locale, "SupportCaseCreated"));
            result.put("supportCaseId", supportCaseId);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> updateSupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            for (String field : new String[] {"caseTypeEnumId", "priority", "severity", "customerPartyId",
                    "financeContractId", "applicationTypeId", "applicationId", "productId", "subject",
                    "description", "sourceEnumId", "assigneePartyId", "queueId", "resolutionEnumId"}) {
                setIfPresent(supportCase, context, field);
            }
            supportCase.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            supportCase.set("lastModifiedByUserLogin", getUserLoginId(context));
            supportCase.store();
            return ServiceUtil.returnSuccess(message(locale, "SupportCaseUpdated"));
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> assignSupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String queueId = getString(context, "queueId");
        String assigneePartyId = getString(context, "assigneePartyId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            supportCase.set("queueId", UtilValidate.isEmpty(queueId) ? null : queueId);
            supportCase.set("assigneePartyId", UtilValidate.isEmpty(assigneePartyId) ? null : assigneePartyId);
            supportCase.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            supportCase.set("lastModifiedByUserLogin", getUserLoginId(context));
            supportCase.store();

            if (UtilValidate.isNotEmpty(queueId) && UtilValidate.isNotEmpty(assigneePartyId)) {
                GenericValue member = EntityQuery.use(delegator).from("SupportQueueMember")
                        .where("queueId", queueId, "partyId", assigneePartyId).queryOne();
                if (member != null) {
                    member.set("lastAssignedDate", UtilDateTime.nowTimestamp());
                    member.store();
                }
            }
            return ServiceUtil.returnSuccess(message(locale, "SupportCaseAssigned"));
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> changeStatus(DispatchContext dctx, Map<String, Object> context,
            String statusId, String resolutionEnumId, String note) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        Timestamp now = UtilDateTime.nowTimestamp();
        String userLoginId = getUserLoginId(context);
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            String oldStatusId = supportCase.getString("statusId");
            if (!statusId.equals(oldStatusId)) {
                GenericValue validChange = EntityQuery.use(delegator).from("StatusValidChange")
                        .where("statusId", oldStatusId, "statusIdTo", statusId).queryOne();
                if (validChange == null) {
                    return ServiceUtil.returnError(message(locale, "SupportInvalidStatusChange"));
                }
            }
            supportCase.set("statusId", statusId);
            if ("Y".equalsIgnoreCase(UtilProperties.getPropertyValue("support",
                    "support.sla.pauseOnPending", "N"))) {
                if (STATUS_PENDING.equals(statusId) && !STATUS_PENDING.equals(oldStatusId)) {
                    supportCase.set("slaPausedAt", now);
                } else if (STATUS_PENDING.equals(oldStatusId) && !STATUS_PENDING.equals(statusId)
                        && supportCase.get("slaPausedAt") != null) {
                    long pausedMinutes = (now.getTime() - supportCase.getTimestamp("slaPausedAt").getTime()) / 60_000L;
                    BigDecimal pausedTotal = supportCase.get("slaPausedMinutes") == null
                            ? BigDecimal.ZERO : decimal(supportCase, "slaPausedMinutes");
                    supportCase.set("slaPausedMinutes", pausedTotal.add(BigDecimal.valueOf(pausedMinutes)));
                    Timestamp firstResponseDueAt = supportCase.getTimestamp("firstResponseDueAt");
                    if (firstResponseDueAt != null) {
                        supportCase.set("firstResponseDueAt",
                                new Timestamp(firstResponseDueAt.getTime() + pausedMinutes * 60_000L));
                    }
                    Timestamp resolutionDueAt = supportCase.getTimestamp("resolutionDueAt");
                    if (resolutionDueAt != null) {
                        supportCase.set("resolutionDueAt",
                                new Timestamp(resolutionDueAt.getTime() + pausedMinutes * 60_000L));
                    }
                    supportCase.set("slaPausedAt", null);
                }
            }
            if (STATUS_RESOLVED.equals(statusId)) {
                supportCase.set("resolvedAt", now);
                if (UtilValidate.isNotEmpty(resolutionEnumId)) {
                    supportCase.set("resolutionEnumId", resolutionEnumId);
                }
            } else if (STATUS_CLOSED.equals(statusId)) {
                supportCase.set("closedAt", now);
                if (supportCase.get("resolvedAt") == null) {
                    supportCase.set("resolvedAt", now);
                }
            } else if (STATUS_CANCELLED.equals(statusId)) {
                supportCase.set("closedAt", now);
                if (UtilValidate.isNotEmpty(resolutionEnumId)) {
                    supportCase.set("resolutionEnumId", resolutionEnumId);
                }
            } else if (STATUS_OPEN.equals(statusId)
                    && (STATUS_RESOLVED.equals(oldStatusId) || STATUS_CLOSED.equals(oldStatusId))) {
                long reopened = supportCase.get("reopenedCount") == null ? 0L : supportCase.getLong("reopenedCount");
                supportCase.set("reopenedCount", BigDecimal.valueOf(reopened).add(BigDecimal.ONE));
                supportCase.set("resolvedAt", null);
                supportCase.set("closedAt", null);
                supportCase.set("resolutionEnumId", null);
            }
            supportCase.set("lastModifiedDate", now);
            supportCase.set("lastModifiedByUserLogin", userLoginId);
            supportCase.store();
            createStatusHistory(delegator, supportCaseId, statusId, now, userLoginId, note);
            return ServiceUtil.returnSuccess(message(locale, "SupportCaseStatusChanged"));
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> changeSupportCaseStatus(DispatchContext dctx, Map<String, Object> context) {
        return changeStatus(dctx, context, getString(context, "statusId"),
                getString(context, "resolutionEnumId"), getString(context, "note"));
    }

    public static Map<String, Object> resolveSupportCase(DispatchContext dctx, Map<String, Object> context) {
        return changeStatus(dctx, context, STATUS_RESOLVED, getString(context, "resolutionEnumId"),
                getString(context, "note"));
    }

    public static Map<String, Object> closeSupportCase(DispatchContext dctx, Map<String, Object> context) {
        return changeStatus(dctx, context, STATUS_CLOSED, null, getString(context, "note"));
    }

    public static Map<String, Object> reopenSupportCase(DispatchContext dctx, Map<String, Object> context) {
        return changeStatus(dctx, context, STATUS_OPEN, null, getString(context, "reason"));
    }

    private static int getMaxEscalationLevel() {
        String levels = UtilProperties.getPropertyValue("support", "support.escalation.levels", "1,2,3");
        int count = 0;
        for (String level : levels.split(",")) {
            if (UtilValidate.isNotEmpty(level.trim())) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private static String createEscalationTask(DispatchContext dctx, Map<String, Object> context,
            GenericValue supportCase, int level) throws GenericEntityException {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = supportCase.getString("supportCaseId");
        String userLoginId = getUserLoginId(context);
        String taskName = "Support escalation SUP-" + supportCaseId + " L" + level;
        String taskDescription = message(locale, "SupportEscalationTaskDescription");
        Timestamp now = UtilDateTime.nowTimestamp();
        String financeContractId = supportCase.getString("financeContractId");
        if (UtilValidate.isNotEmpty(financeContractId)) {
            Map<String, Object> taskContext = new HashMap<>();
            taskContext.put("userLogin", getUserLogin(delegator, context));
            taskContext.put("financeContractId", financeContractId);
            taskContext.put("financeWorkTypeEnumId", "FINWE_OTHER");
            taskContext.put("triggerKey", "SUPESC:" + supportCaseId + ":" + level);
            taskContext.put("workEffortName", taskName);
            taskContext.put("description", taskDescription);
            taskContext.put("dueDate", now);
            taskContext.put("assigneePartyId", supportCase.getString("assigneePartyId"));
            try {
                Map<String, Object> result = dispatcher.runSync("createFinanceContractTask", taskContext);
                if (ServiceUtil.isSuccess(result) && result.get("workEffortId") != null) {
                    return (String) result.get("workEffortId");
                }
            } catch (GenericServiceException e) {
                // fall through to the generic WorkEffort below
            }
        }
        GenericValue workEffort = delegator.makeValue("WorkEffort");
        String workEffortId = delegator.getNextSeqId("WorkEffort");
        workEffort.set("workEffortId", workEffortId);
        workEffort.set("workEffortTypeId", "TASK");
        workEffort.set("currentStatusId", "CAL_NEEDS_ACTION");
        workEffort.set("workEffortName", taskName);
        workEffort.set("description", taskDescription);
        workEffort.set("priority", supportCase.get("priority"));
        workEffort.set("estimatedCompletionDate", now);
        workEffort.set("createdDate", now);
        workEffort.set("lastModifiedDate", now);
        workEffort.set("createdByUserLogin", userLoginId);
        workEffort.set("lastModifiedByUserLogin", userLoginId);
        workEffort.create();
        return workEffortId;
    }

    private static void createEscalationNote(Delegator delegator, GenericValue supportCase, int level,
            String userLoginId) throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        GenericValue event = delegator.makeValue("CommunicationEvent");
        String communicationEventId = delegator.getNextSeqId("CommunicationEvent");
        GenericValue userLogin = EntityQuery.use(delegator).from("UserLogin")
                .where("userLoginId", userLoginId).queryOne();
        event.set("communicationEventId", communicationEventId);
        event.set("communicationEventTypeId", "COMMENT_NOTE");
        event.set("statusId", "COM_COMPLETE");
        event.set("subject", "Escalation level " + level);
        event.set("content", "Support case SUP-" + supportCase.getString("supportCaseId")
                + " was escalated to level " + level + ".");
        event.set("contentMimeTypeId", "text/plain");
        event.set("entryDate", now);
        event.set("partyIdFrom", userLogin == null ? null : userLogin.getString("partyId"));
        event.set("partyIdTo", supportCase.getString("customerPartyId"));
        event.set("roleTypeIdFrom", "_NA_");
        event.set("roleTypeIdTo", "_NA_");
        event.create();
        linkMessage(delegator, supportCase, event, "SUPMSG_INTERNAL", true, true, userLoginId);
    }

    public static Map<String, Object> escalateOverdueSupportCases(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Timestamp now = UtilDateTime.nowTimestamp();
        String userLoginId = getUserLoginId(context);
        int escalated = 0;
        try {
            EntityCondition openStatus = EntityCondition.makeCondition("statusId", EntityOperator.IN,
                    List.of(STATUS_NEW, STATUS_OPEN, STATUS_PENDING));
            EntityCondition firstResponseOverdue = EntityCondition.makeCondition(EntityOperator.AND,
                    EntityCondition.makeCondition("firstResponseAt", EntityOperator.EQUALS, null),
                    EntityCondition.makeCondition("firstResponseDueAt", EntityOperator.LESS_THAN, now));
            EntityCondition resolutionOverdue = EntityCondition.makeCondition(EntityOperator.AND,
                    EntityCondition.makeCondition("resolvedAt", EntityOperator.EQUALS, null),
                    EntityCondition.makeCondition("resolutionDueAt", EntityOperator.LESS_THAN, now));
            EntityCondition overdue = EntityCondition.makeCondition(EntityOperator.OR,
                    firstResponseOverdue, resolutionOverdue);
            List<GenericValue> cases = EntityQuery.use(delegator).from("SupportCase")
                    .where(EntityCondition.makeCondition(EntityOperator.AND, openStatus, overdue))
                    .orderBy("openedAt").queryList();
            long repeatMinutes = (long) UtilProperties.getPropertyNumber("support",
                    "support.escalation.repeatMinutes", 1440);
            int maxLevel = getMaxEscalationLevel();
            for (GenericValue supportCase : cases) {
                Timestamp lastEscalatedAt = supportCase.getTimestamp("lastEscalatedAt");
                if (lastEscalatedAt != null
                        && lastEscalatedAt.getTime() > now.getTime() - repeatMinutes * 60_000L) {
                    continue;
                }
                int currentLevel = supportCase.get("escalationLevel") == null
                        ? 0 : supportCase.getLong("escalationLevel").intValue();
                int level = Math.min(currentLevel + 1, maxLevel);
                supportCase.set("escalationLevel", BigDecimal.valueOf(level));
                supportCase.set("lastEscalatedAt", now);
                supportCase.set("lastModifiedDate", now);
                supportCase.set("lastModifiedByUserLogin", userLoginId);
                supportCase.store();

                String workEffortId = createEscalationTask(dctx, context, supportCase, level);
                GenericValue existingLink = EntityQuery.use(delegator).from("SupportCaseWorkEffort")
                        .where("supportCaseId", supportCase.getString("supportCaseId"),
                                "workEffortId", workEffortId).queryOne();
                if (existingLink == null) {
                    GenericValue link = delegator.makeValue("SupportCaseWorkEffort");
                    link.set("supportCaseId", supportCase.getString("supportCaseId"));
                    link.set("workEffortId", workEffortId);
                    link.create();
                }
                createEscalationNote(delegator, supportCase, level, userLoginId);
                escalated++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("escalatedCount", escalated);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getSupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            List<GenericValue> statuses = EntityQuery.use(delegator).from("SupportCaseStatus")
                    .where("supportCaseId", supportCaseId)
                    .orderBy("-statusDate")
                    .queryList();
            List<GenericValue> messages = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                    .where("supportCaseId", supportCaseId)
                    .orderBy("addedDate")
                    .queryList();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCase", supportCase);
            result.put("supportCaseStatuses", statuses);
            result.put("supportCaseMessages", messages);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =============================================================
    // STEP S2: messages, thread and inbound email
    // =============================================================

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "Y".equalsIgnoreCase(String.valueOf(value))
                || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static GenericValue getPrimaryEmail(Delegator delegator, String partyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(partyId)) {
            return null;
        }
        List<GenericValue> emails = EntityQuery.use(delegator).from("PartyAndContactMech")
                .where("partyId", partyId, "contactMechTypeId", "EMAIL_ADDRESS")
                .filterByDate()
                .orderBy("-fromDate")
                .queryList();
        return emails.isEmpty() ? null : emails.get(0);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String findCaseByEvent(Delegator delegator, String communicationEventId) throws GenericEntityException {
        if (UtilValidate.isEmpty(communicationEventId)) {
            return null;
        }
        GenericValue link = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                .where("communicationEventId", communicationEventId).queryFirst();
        return link == null ? null : link.getString("supportCaseId");
    }

    private static String findCaseBySubjectToken(Delegator delegator, String subject, String customerPartyId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(subject) || UtilValidate.isEmpty(customerPartyId)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\[SUP-(\\d+)\\]").matcher(subject);
        if (!matcher.find()) {
            return null;
        }
        String supportCaseId = matcher.group(1);
        GenericValue supportCase = findCase(delegator, supportCaseId);
        if (supportCase != null && customerPartyId.equals(supportCase.getString("customerPartyId"))) {
            return supportCaseId;
        }
        return null;
    }

    private static boolean linkMessage(Delegator delegator, GenericValue supportCase, GenericValue communicationEvent,
            String directionEnumId, boolean isInternal, boolean isAuto, String userLoginId) throws GenericEntityException {
        if (supportCase == null || communicationEvent == null) {
            return false;
        }
        String supportCaseId = supportCase.getString("supportCaseId");
        String communicationEventId = communicationEvent.getString("communicationEventId");
        GenericValue link = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                .where("supportCaseId", supportCaseId, "communicationEventId", communicationEventId).queryOne();
        if (link == null) {
            link = delegator.makeValue("SupportCaseCommEvent");
            link.set("supportCaseId", supportCaseId);
            link.set("communicationEventId", communicationEventId);
            link.set("directionEnumId", directionEnumId);
            link.set("isInternal", isInternal ? "Y" : "N");
            link.set("isAuto", isAuto ? "Y" : "N");
            link.set("addedByUserLogin", userLoginId);
            link.set("addedDate", UtilDateTime.nowTimestamp());
            link.create();
        }
        Timestamp entryDate = communicationEvent.getTimestamp("entryDate");
        supportCase.set("lastMessageAt", entryDate == null ? UtilDateTime.nowTimestamp() : entryDate);
        if (UtilValidate.isEmpty(supportCase.getString("rootCommunicationEventId"))) {
            supportCase.set("rootCommunicationEventId", communicationEventId);
        }
        supportCase.store();
        return true;
    }

    public static Map<String, Object> addSupportCaseMessage(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String communicationEventId = getString(context, "communicationEventId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            GenericValue communicationEvent = EntityQuery.use(delegator).from("CommunicationEvent")
                    .where("communicationEventId", communicationEventId).queryOne();
            if (supportCase == null || communicationEvent == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            String direction = getString(context, "directionEnumId");
            if (UtilValidate.isEmpty(direction)) {
                direction = "COMMENT_NOTE".equals(communicationEvent.getString("communicationEventTypeId"))
                        ? "SUPMSG_INTERNAL" : "SUPMSG_IN";
            }
            linkMessage(delegator, supportCase, communicationEvent, direction, isTrue(context.get("isInternal")),
                    false, getUserLoginId(context));
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> addSupportCaseInternalNote(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String subject = getString(context, "subject");
        String content = getString(context, "content");
        Timestamp now = UtilDateTime.nowTimestamp();
        String userLoginId = getUserLoginId(context);
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            String partyIdFrom = userLogin == null ? null : userLogin.getString("partyId");
            GenericValue communicationEvent = delegator.makeValue("CommunicationEvent");
            String communicationEventId = delegator.getNextSeqId("CommunicationEvent");
            communicationEvent.set("communicationEventId", communicationEventId);
            communicationEvent.set("communicationEventTypeId", "COMMENT_NOTE");
            communicationEvent.set("statusId", "COM_COMPLETE");
            communicationEvent.set("subject", UtilValidate.isEmpty(subject) ? "Internal note" : subject);
            communicationEvent.set("content", UtilValidate.isEmpty(content) ? " " : content);
            communicationEvent.set("contentMimeTypeId", "text/plain");
            communicationEvent.set("entryDate", now);
            communicationEvent.set("partyIdFrom", partyIdFrom);
            communicationEvent.set("partyIdTo", supportCase.getString("customerPartyId"));
            communicationEvent.set("roleTypeIdFrom", "_NA_");
            communicationEvent.set("roleTypeIdTo", "_NA_");
            communicationEvent.create();
            linkMessage(delegator, supportCase, communicationEvent, "SUPMSG_INTERNAL", true, false, userLoginId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("communicationEventId", communicationEventId);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> openCaseIfNeeded(DispatchContext dctx, Map<String, Object> context,
            GenericValue supportCase, String note) {
        String statusId = supportCase.getString("statusId");
        if (STATUS_OPEN.equals(statusId)) {
            return ServiceUtil.returnSuccess();
        }
        if (STATUS_CANCELLED.equals(statusId)) {
            return ServiceUtil.returnError("Cancelled support case cannot be reopened");
        }
        Map<String, Object> statusContext = new HashMap<>(context);
        statusContext.put("supportCaseId", supportCase.getString("supportCaseId"));
        return changeStatus(dctx, statusContext, STATUS_OPEN, null, note);
    }

    public static Map<String, Object> replySupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String userLoginId = getUserLoginId(context);
        Timestamp now = UtilDateTime.nowTimestamp();
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            String customerPartyId = supportCase.getString("customerPartyId");
            GenericValue toEmail = getPrimaryEmail(delegator, customerPartyId);
            if (toEmail == null) {
                return ServiceUtil.returnError(message(locale, "SupportNoCustomerEmail"));
            }
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            String partyIdFrom = userLogin == null ? null : userLogin.getString("partyId");
            GenericValue fromEmail = getPrimaryEmail(delegator, partyIdFrom);
            if (fromEmail == null) {
                partyIdFrom = UtilProperties.getPropertyValue("support", "support.inbound.partyId", "");
                fromEmail = getPrimaryEmail(delegator, partyIdFrom);
            }
            if (fromEmail == null) {
                return ServiceUtil.returnError(message(locale, "SupportNoFromEmail"));
            }

            GenericValue lastInboundLink = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                    .where("supportCaseId", supportCaseId, "directionEnumId", "SUPMSG_IN")
                    .orderBy("-addedDate").queryFirst();
            GenericValue parentEvent = lastInboundLink == null ? null
                    : EntityQuery.use(delegator).from("CommunicationEvent")
                            .where("communicationEventId", lastInboundLink.getString("communicationEventId")).queryOne();
            String rootEventId = supportCase.getString("rootCommunicationEventId");
            if (UtilValidate.isEmpty(rootEventId) && parentEvent != null) {
                rootEventId = parentEvent.getString("origCommEventId");
                if (UtilValidate.isEmpty(rootEventId)) {
                    rootEventId = parentEvent.getString("communicationEventId");
                }
            }

            String subject = getString(context, "subject");
            if (UtilValidate.isEmpty(subject)) {
                subject = supportCase.getString("subject");
            }
            subject = subject == null ? "" : subject;
            subject = subject.replaceAll("(?i)^\\s*re:\\s*", "").replaceAll("\\[SUP-\\d+\\]\\s*", "");
            subject = "Re: [SUP-" + supportCaseId + "] " + subject;

            GenericValue event = delegator.makeValue("CommunicationEvent");
            String communicationEventId = delegator.getNextSeqId("CommunicationEvent");
            event.set("communicationEventId", communicationEventId);
            event.set("communicationEventTypeId", "EMAIL_COMMUNICATION");
            event.set("statusId", "COM_IN_PROGRESS");
            event.set("partyIdFrom", partyIdFrom);
            event.set("partyIdTo", customerPartyId);
            event.set("contactMechIdFrom", fromEmail.getString("contactMechId"));
            event.set("contactMechIdTo", toEmail.getString("contactMechId"));
            event.set("entryDate", now);
            event.set("datetimeStarted", now);
            event.set("subject", subject);
            event.set("content", UtilValidate.isEmpty(getString(context, "content")) ? " " : getString(context, "content"));
            event.set("contentMimeTypeId", "text/plain");
            event.set("parentCommEventId", parentEvent == null ? null : parentEvent.getString("communicationEventId"));
            event.set("origCommEventId", rootEventId);
            event.set("roleTypeIdFrom", "_NA_");
            event.set("roleTypeIdTo", "_NA_");
            event.create();

            linkMessage(delegator, supportCase, event, "SUPMSG_OUT", false, false, userLoginId);
            Map<String, Object> statusResult = openCaseIfNeeded(dctx, context, supportCase, "Reply");
            if (ServiceUtil.isError(statusResult)) {
                return statusResult;
            }
            supportCase = findCase(delegator, supportCaseId);
            if (supportCase.get("firstResponseAt") == null) {
                supportCase.set("firstResponseAt", now);
            }
            supportCase.set("lastMessageAt", now);
            supportCase.set("lastModifiedDate", now);
            supportCase.set("lastModifiedByUserLogin", userLoginId);
            supportCase.store();

            if (isTrue(context.get("sendNow"))) {
                try {
                    Map<String, Object> sendContext = new HashMap<>();
                    sendContext.put("communicationEventId", communicationEventId);
                    sendContext.put("userLogin", context.get("userLogin"));
                    Map<String, Object> sendResult = dispatcher.runSync("sendCommEventAsEmail", sendContext);
                    if (ServiceUtil.isError(sendResult)) {
                        return sendResult;
                    }
                } catch (GenericServiceException e) {
                    return ServiceUtil.returnError(e.getMessage());
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("communicationEventId", communicationEventId);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getSupportCaseThread(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String supportCaseId = getString(context, "supportCaseId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                Map<String, Object> emptyResult = ServiceUtil.returnSuccess();
                emptyResult.put("supportCaseThread", new ArrayList<>());
                return emptyResult;
            }
            List<GenericValue> links = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                    .where("supportCaseId", supportCaseId).orderBy("addedDate").queryList();
            Map<String, GenericValue> events = new LinkedHashMap<>();
            for (GenericValue link : links) {
                GenericValue event = EntityQuery.use(delegator).from("CommunicationEvent")
                        .where("communicationEventId", link.getString("communicationEventId")).queryOne();
                if (event != null) {
                    events.put(event.getString("communicationEventId"), event);
                }
            }
            String rootEventId = supportCase.getString("rootCommunicationEventId");
            if (UtilValidate.isEmpty(rootEventId)) {
                GenericValue firstInbound = links.stream()
                        .filter(link -> "SUPMSG_IN".equals(link.getString("directionEnumId")))
                        .findFirst().orElse(null);
                if (firstInbound != null) {
                    rootEventId = firstInbound.getString("communicationEventId");
                } else if (!links.isEmpty()) {
                    rootEventId = links.get(0).getString("communicationEventId");
                }
            }
            if (UtilValidate.isNotEmpty(rootEventId)) {
                List<GenericValue> threadEvents = EntityQuery.use(delegator).from("CommunicationEvent")
                        .where(EntityCondition.makeCondition(EntityOperator.OR,
                                EntityCondition.makeCondition("communicationEventId", rootEventId),
                                EntityCondition.makeCondition("origCommEventId", rootEventId)))
                        .orderBy("entryDate").queryList();
                for (GenericValue event : threadEvents) {
                    events.putIfAbsent(event.getString("communicationEventId"), event);
                }
            }

            String customerPartyId = supportCase.getString("customerPartyId");
            List<Map<String, Object>> thread = new ArrayList<>();
            for (GenericValue event : events.values()) {
                String eventId = event.getString("communicationEventId");
                GenericValue link = links.stream()
                        .filter(row -> eventId.equals(row.getString("communicationEventId")))
                        .findFirst().orElse(null);
                String direction = link == null ? null : link.getString("directionEnumId");
                if (UtilValidate.isEmpty(direction)) {
                    direction = "COMMENT_NOTE".equals(event.getString("communicationEventTypeId"))
                            ? "SUPMSG_INTERNAL"
                            : (customerPartyId.equals(event.getString("partyIdFrom")) ? "SUPMSG_IN" : "SUPMSG_OUT");
                }
                boolean internal = link != null ? "Y".equals(link.getString("isInternal"))
                        : "COMMENT_NOTE".equals(event.getString("communicationEventTypeId"));
                boolean auto = link != null && "Y".equals(link.getString("isAuto"));
                int depth = 0;
                String parentId = event.getString("parentCommEventId");
                Set<String> seen = new HashSet<>();
                while (UtilValidate.isNotEmpty(parentId) && events.containsKey(parentId) && seen.add(parentId) && depth < 20) {
                    depth++;
                    parentId = events.get(parentId).getString("parentCommEventId");
                }
                long attachmentCount = EntityQuery.use(delegator).from("CommEventContentAssoc")
                        .where("communicationEventId", eventId).queryCount();
                Map<String, Object> row = new HashMap<>();
                row.put("communicationEventId", eventId);
                row.put("subject", event.getString("subject"));
                row.put("content", event.getString("content"));
                row.put("entryDate", event.getTimestamp("entryDate"));
                row.put("directionEnumId", direction);
                row.put("isInternal", internal ? "Y" : "N");
                row.put("isAuto", auto ? "Y" : "N");
                row.put("partyIdFrom", event.getString("partyIdFrom"));
                row.put("partyIdTo", event.getString("partyIdTo"));
                row.put("fromString", event.getString("fromString"));
                row.put("toString", event.getString("toString"));
                row.put("hasAttachments", attachmentCount > 0 ? "Y" : "N");
                row.put("depth", depth);
                thread.add(row);
            }
            thread.sort((left, right) -> {
                Timestamp leftDate = (Timestamp) left.get("entryDate");
                Timestamp rightDate = (Timestamp) right.get("entryDate");
                if (leftDate == null && rightDate == null) {
                    return 0;
                }
                if (leftDate == null) {
                    return 1;
                }
                if (rightDate == null) {
                    return -1;
                }
                return leftDate.compareTo(rightDate);
            });
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCaseThread", thread);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> createAutoAck(DispatchContext dctx, Map<String, Object> context,
            GenericValue supportCase, GenericValue inboundEvent) throws GenericEntityException {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = supportCase.getString("supportCaseId");
        String supportPartyId = UtilProperties.getPropertyValue("support", "support.inbound.partyId", "");
        if (UtilValidate.isEmpty(supportPartyId)) {
            supportPartyId = inboundEvent.getString("partyIdTo");
        }
        String fromContactMechId = inboundEvent.getString("contactMechIdTo");
        if (UtilValidate.isEmpty(fromContactMechId)) {
            GenericValue fromEmail = getPrimaryEmail(delegator, supportPartyId);
            fromContactMechId = fromEmail == null ? null : fromEmail.getString("contactMechId");
        }
        String toContactMechId = inboundEvent.getString("contactMechIdFrom");
        if (UtilValidate.isEmpty(toContactMechId)) {
            GenericValue toEmail = getPrimaryEmail(delegator, inboundEvent.getString("partyIdFrom"));
            toContactMechId = toEmail == null ? null : toEmail.getString("contactMechId");
        }
        if (UtilValidate.isEmpty(supportPartyId) || UtilValidate.isEmpty(fromContactMechId)
                || UtilValidate.isEmpty(toContactMechId)) {
            return ServiceUtil.returnSuccess();
        }
        String body = MessageFormat.format(message(locale, "SupportAutoAckBody"), supportCaseId);
        String subject = inboundEvent.getString("subject");
        subject = "[SUP-" + supportCaseId + "] " + (UtilValidate.isEmpty(subject) ? "" : subject);
        Timestamp now = UtilDateTime.nowTimestamp();
        GenericValue event = delegator.makeValue("CommunicationEvent");
        String communicationEventId = delegator.getNextSeqId("CommunicationEvent");
        event.set("communicationEventId", communicationEventId);
        event.set("communicationEventTypeId", "EMAIL_COMMUNICATION");
        event.set("statusId", "COM_IN_PROGRESS");
        event.set("partyIdFrom", supportPartyId);
        event.set("partyIdTo", inboundEvent.getString("partyIdFrom"));
        event.set("contactMechIdFrom", fromContactMechId);
        event.set("contactMechIdTo", toContactMechId);
        event.set("entryDate", now);
        event.set("datetimeStarted", now);
        event.set("subject", subject);
        event.set("content", body);
        event.set("contentMimeTypeId", "text/plain");
        event.set("parentCommEventId", inboundEvent.getString("communicationEventId"));
        String rootEventId = supportCase.getString("rootCommunicationEventId");
        event.set("origCommEventId", UtilValidate.isEmpty(rootEventId)
                ? inboundEvent.getString("communicationEventId") : rootEventId);
        event.set("roleTypeIdFrom", "_NA_");
        event.set("roleTypeIdTo", "_NA_");
        event.create();
        linkMessage(delegator, supportCase, event, "SUPMSG_OUT", false, true, "system");
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("communicationEventId", communicationEventId);
        return result;
    }

    private static GenericValue getSystemUser(Delegator delegator) throws GenericEntityException {
        return EntityQuery.use(delegator).from("UserLogin").where("userLoginId", "system").queryOne();
    }

    private static boolean ownsCase(GenericValue supportCase, String customerPartyId) {
        return supportCase != null && UtilValidate.isNotEmpty(customerPartyId)
                && customerPartyId.equals(supportCase.getString("customerPartyId"));
    }

    private static Map<String, Object> caseSummary(GenericValue supportCase) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("supportCaseId", supportCase.getString("supportCaseId"));
        summary.put("subject", supportCase.getString("subject"));
        summary.put("description", supportCase.getString("description"));
        summary.put("statusId", supportCase.getString("statusId"));
        summary.put("caseTypeEnumId", supportCase.getString("caseTypeEnumId"));
        summary.put("financeContractId", supportCase.getString("financeContractId"));
        summary.put("applicationTypeId", supportCase.getString("applicationTypeId"));
        summary.put("applicationId", supportCase.getString("applicationId"));
        summary.put("productId", supportCase.getString("productId"));
        summary.put("openedAt", supportCase.getTimestamp("openedAt"));
        summary.put("lastMessageAt", supportCase.getTimestamp("lastMessageAt"));
        summary.put("resolvedAt", supportCase.getTimestamp("resolvedAt"));
        summary.put("closedAt", supportCase.getTimestamp("closedAt"));
        summary.put("satisfactionScore", supportCase.get("satisfactionScore"));
        return summary;
    }

    public static Map<String, Object> createSupportCaseForCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        String customerPartyId = getString(context, "customerPartyId");
        String subject = getString(context, "subject");
        if (UtilValidate.isEmpty(customerPartyId) || UtilValidate.isEmpty(subject)) {
            return ServiceUtil.returnError(message(locale, "SupportApiCustomerAndSubjectRequired"));
        }
        try {
            String externalId = getString(context, "externalId");
            if (UtilValidate.isNotEmpty(externalId)) {
                GenericValue existing = EntityQuery.use(delegator).from("SupportCase")
                        .where("externalId", externalId).queryOne();
                if (existing != null) {
                    if (!customerPartyId.equals(existing.getString("customerPartyId"))) {
                        return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
                    }
                    Map<String, Object> result = ServiceUtil.returnSuccess();
                    result.put("supportCaseId", existing.getString("supportCaseId"));
                    result.put("statusId", existing.getString("statusId"));
                    result.put("firstResponseDueAt", existing.getTimestamp("firstResponseDueAt"));
                    result.put("resolutionDueAt", existing.getTimestamp("resolutionDueAt"));
                    return result;
                }
            }
            Map<String, Object> createContext = new HashMap<>();
            createContext.put("userLogin", getSystemUser(delegator));
            createContext.put("customerPartyId", customerPartyId);
            createContext.put("caseTypeEnumId", getString(context, "caseTypeEnumId"));
            createContext.put("sourceEnumId", "SUPCSRC_PORTAL");
            createContext.put("financeContractId", getString(context, "financeContractId"));
            createContext.put("applicationTypeId", getString(context, "applicationTypeId"));
            createContext.put("applicationId", getString(context, "applicationId"));
            createContext.put("productId", getString(context, "productId"));
            createContext.put("subject", subject);
            createContext.put("description", getString(context, "description"));
            createContext.put("externalId", externalId);
            Map<String, Object> createResult = dispatcher.runSync("createSupportCase", createContext);
            if (ServiceUtil.isError(createResult)) {
                return createResult;
            }
            String supportCaseId = (String) createResult.get("supportCaseId");
            GenericValue supportCase = findCase(delegator, supportCaseId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCaseId", supportCaseId);
            result.put("statusId", supportCase.getString("statusId"));
            result.put("firstResponseDueAt", supportCase.getTimestamp("firstResponseDueAt"));
            result.put("resolutionDueAt", supportCase.getTimestamp("resolutionDueAt"));
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> listSupportCasesForCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String customerPartyId = getString(context, "customerPartyId");
        if (UtilValidate.isEmpty(customerPartyId)) {
            return ServiceUtil.returnError(message(locale, "SupportApiCustomerRequired"));
        }
        try {
            List<EntityCondition> conditions = new ArrayList<>();
            conditions.add(EntityCondition.makeCondition("customerPartyId", customerPartyId));
            addEquals(conditions, "financeContractId", getString(context, "financeContractId"));
            addEquals(conditions, "productId", getString(context, "productId"));
            Object statusIds = context.get("statusIds");
            if (statusIds instanceof List && !((List<?>) statusIds).isEmpty()) {
                conditions.add(EntityCondition.makeCondition("statusId", EntityOperator.IN, statusIds));
            }
            List<GenericValue> allCases = EntityQuery.use(delegator).from("SupportCase")
                    .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                    .orderBy("-openedAt").queryList();
            int viewIndex = context.get("viewIndex") == null ? 0 : Integer.parseInt(context.get("viewIndex").toString());
            int viewSize = context.get("viewSize") == null ? 20 : Integer.parseInt(context.get("viewSize").toString());
            int fromIndex = Math.min(Math.max(viewIndex * viewSize, 0), allCases.size());
            int toIndex = Math.min(fromIndex + viewSize, allCases.size());
            List<Map<String, Object>> cases = new ArrayList<>();
            for (GenericValue supportCase : allCases.subList(fromIndex, toIndex)) {
                cases.add(caseSummary(supportCase));
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCases", cases);
            result.put("caseCount", allCases.size());
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getSupportCaseForCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String customerPartyId = getString(context, "customerPartyId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (!ownsCase(supportCase, customerPartyId)) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            Map<String, Object> threadContext = new HashMap<>();
            threadContext.put("userLogin", getSystemUser(delegator));
            threadContext.put("supportCaseId", supportCaseId);
            Map<String, Object> threadResult = dispatcher.runSync("getSupportCaseThread", threadContext);
            if (ServiceUtil.isError(threadResult)) {
                return threadResult;
            }
            List<Map<String, Object>> visibleMessages = new ArrayList<>();
            Object thread = threadResult.get("supportCaseThread");
            if (thread instanceof List) {
                for (Object item : (List<?>) thread) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) item;
                        if (!"Y".equals(message.get("isInternal"))) {
                            visibleMessages.add(message);
                        }
                    }
                }
            }
            String statusId = supportCase.getString("statusId");
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCase", caseSummary(supportCase));
            result.put("messages", visibleMessages);
            result.put("canReply", !STATUS_CANCELLED.equals(statusId));
            result.put("canClose", !STATUS_CLOSED.equals(statusId) && !STATUS_CANCELLED.equals(statusId));
            result.put("canRate", (STATUS_RESOLVED.equals(statusId) || STATUS_CLOSED.equals(statusId))
                    && supportCase.get("satisfactionScore") == null);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> replySupportCaseFromCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String customerPartyId = getString(context, "customerPartyId");
        String content = getString(context, "content");
        if (UtilValidate.isEmpty(content)) {
            return ServiceUtil.returnError(message(locale, "SupportApiMessageRequired"));
        }
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (!ownsCase(supportCase, customerPartyId)) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            if (STATUS_CANCELLED.equals(supportCase.getString("statusId"))) {
                return ServiceUtil.returnError(message(locale, "SupportInvalidStatusChange"));
            }
            String externalId = getString(context, "externalId");
            if (UtilValidate.isNotEmpty(externalId)) {
                GenericValue existing = EntityQuery.use(delegator).from("CommunicationEvent")
                        .where("messageId", externalId).queryOne();
                if (existing != null) {
                    GenericValue link = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                            .where("supportCaseId", supportCaseId,
                                    "communicationEventId", existing.getString("communicationEventId")).queryOne();
                    if (link != null) {
                        Map<String, Object> result = ServiceUtil.returnSuccess();
                        result.put("communicationEventId", existing.getString("communicationEventId"));
                        result.put("statusId", supportCase.getString("statusId"));
                        return result;
                    }
                }
            }
            GenericValue lastLink = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                    .where("supportCaseId", supportCaseId).orderBy("-addedDate").queryFirst();
            GenericValue parentEvent = lastLink == null ? null
                    : EntityQuery.use(delegator).from("CommunicationEvent")
                            .where("communicationEventId", lastLink.getString("communicationEventId")).queryOne();
            String rootEventId = supportCase.getString("rootCommunicationEventId");
            if (UtilValidate.isEmpty(rootEventId) && parentEvent != null) {
                rootEventId = parentEvent.getString("origCommEventId");
                if (UtilValidate.isEmpty(rootEventId)) {
                    rootEventId = parentEvent.getString("communicationEventId");
                }
            }
            String subject = getString(context, "subject");
            if (UtilValidate.isEmpty(subject)) {
                subject = supportCase.getString("subject");
            }
            subject = subject == null ? "" : subject;
            subject = subject.replaceAll("(?i)^\\s*re:\\s*", "").replaceAll("\\[SUP-\\d+\\]\\s*", "");
            subject = "Re: [SUP-" + supportCaseId + "] " + subject;
            String supportPartyId = supportCase.getString("assigneePartyId");
            if (UtilValidate.isEmpty(supportPartyId)) {
                supportPartyId = UtilProperties.getPropertyValue("support", "support.inbound.partyId", "");
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue event = delegator.makeValue("CommunicationEvent");
            String communicationEventId = delegator.getNextSeqId("CommunicationEvent");
            event.set("communicationEventId", communicationEventId);
            event.set("communicationEventTypeId", "WEB_SITE_COMMUNICATI");
            event.set("statusId", "COM_COMPLETE");
            event.set("partyIdFrom", customerPartyId);
            event.set("partyIdTo", UtilValidate.isEmpty(supportPartyId) ? null : supportPartyId);
            event.set("entryDate", now);
            event.set("subject", subject);
            event.set("content", content);
            event.set("contentMimeTypeId", "text/plain");
            event.set("messageId", UtilValidate.isEmpty(externalId) ? null : externalId);
            event.set("parentCommEventId", parentEvent == null ? null : parentEvent.getString("communicationEventId"));
            event.set("origCommEventId", rootEventId);
            event.set("roleTypeIdFrom", "_NA_");
            event.set("roleTypeIdTo", "_NA_");
            event.create();
            linkMessage(delegator, supportCase, event, "SUPMSG_IN", false, false, customerPartyId);
            Map<String, Object> statusContext = new HashMap<>(context);
            statusContext.put("supportCaseId", supportCaseId);
            Map<String, Object> statusResult = openCaseIfNeeded(dctx, statusContext, supportCase, "Customer reply");
            if (ServiceUtil.isError(statusResult)) {
                return statusResult;
            }
            supportCase = findCase(delegator, supportCaseId);
            supportCase.set("lastMessageAt", now);
            supportCase.set("lastModifiedDate", now);
            supportCase.set("lastModifiedByUserLogin", customerPartyId);
            supportCase.store();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("communicationEventId", communicationEventId);
            result.put("statusId", supportCase.getString("statusId"));
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> closeSupportCaseByCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String customerPartyId = getString(context, "customerPartyId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (!ownsCase(supportCase, customerPartyId)) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            String statusId = supportCase.getString("statusId");
            if (STATUS_CLOSED.equals(statusId) || STATUS_CANCELLED.equals(statusId)) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("statusId", statusId);
                return result;
            }
            Map<String, Object> statusContext = new HashMap<>(context);
            statusContext.put("supportCaseId", supportCaseId);
            statusContext.put("note", getString(context, "reason"));
            Map<String, Object> statusResult;
            if (STATUS_RESOLVED.equals(statusId)) {
                statusResult = changeStatus(dctx, statusContext, STATUS_CLOSED, null, getString(context, "reason"));
            } else {
                statusResult = changeStatus(dctx, statusContext, STATUS_CANCELLED, "SUPRES_WITHDRAWN",
                        getString(context, "reason"));
            }
            if (ServiceUtil.isError(statusResult)) {
                return statusResult;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("statusId", findCase(delegator, supportCaseId).getString("statusId"));
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> rateSupportCase(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String customerPartyId = getString(context, "customerPartyId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (!ownsCase(supportCase, customerPartyId)) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            String statusId = supportCase.getString("statusId");
            if (!STATUS_RESOLVED.equals(statusId) && !STATUS_CLOSED.equals(statusId)) {
                return ServiceUtil.returnError(message(locale, "SupportInvalidStatusChange"));
            }
            String scoreValue = getString(context, "satisfactionScore");
            int score;
            try {
                score = Integer.parseInt(scoreValue);
            } catch (NumberFormatException e) {
                return ServiceUtil.returnError(message(locale, "SupportApiInvalidScore"));
            }
            if (score < 1 || score > 5) {
                return ServiceUtil.returnError(message(locale, "SupportApiInvalidScore"));
            }
            supportCase.set("satisfactionScore", BigDecimal.valueOf(score));
            supportCase.set("satisfactionComment", getString(context, "satisfactionComment"));
            supportCase.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            supportCase.set("lastModifiedByUserLogin", customerPartyId);
            supportCase.store();
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getSupportCaseFormOptions(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String customerPartyId = getString(context, "customerPartyId");
        if (UtilValidate.isEmpty(customerPartyId)) {
            return ServiceUtil.returnError(message(locale, "SupportApiCustomerRequired"));
        }
        try {
            List<Map<String, Object>> contracts = new ArrayList<>();
            List<Map<String, Object>> products = new ArrayList<>();
            List<String> productIds = new ArrayList<>();
            List<GenericValue> contractValues = EntityQuery.use(delegator).from("FinanceContract")
                    .where("customerPartyId", customerPartyId).orderBy("contractDate").queryList();
            for (GenericValue contract : contractValues) {
                Map<String, Object> row = new HashMap<>();
                row.put("financeContractId", contract.getString("financeContractId"));
                row.put("contractTypeId", contract.getString("contractTypeId"));
                row.put("productId", contract.getString("productId"));
                contracts.add(row);
                String productId = contract.getString("productId");
                if (UtilValidate.isNotEmpty(productId) && !productIds.contains(productId)) {
                    productIds.add(productId);
                    GenericValue product = EntityQuery.use(delegator).from("Product")
                            .where("productId", productId).queryOne();
                    Map<String, Object> productRow = new HashMap<>();
                    productRow.put("productId", productId);
                    productRow.put("productName", product == null ? productId : product.getString("productName"));
                    products.add(productRow);
                }
            }
            List<Map<String, Object>> applications = new ArrayList<>();
            List<GenericValue> loanApplications = EntityQuery.use(delegator).from("LoanApplication")
                    .where("applicantPartyId", customerPartyId).orderBy("-applicationDate").queryList();
            for (GenericValue application : loanApplications) {
                Map<String, Object> row = new HashMap<>();
                row.put("applicationTypeId", "LOAN");
                row.put("applicationId", application.getString("loanApplicationId"));
                row.put("productId", application.getString("productId"));
                row.put("statusId", application.getString("statusId"));
                applications.add(row);
            }
            List<GenericValue> leaseApplications = EntityQuery.use(delegator).from("LeaseApplication")
                    .where("applicantPartyId", customerPartyId).orderBy("-applicationDate").queryList();
            for (GenericValue application : leaseApplications) {
                Map<String, Object> row = new HashMap<>();
                row.put("applicationTypeId", "LEASE");
                row.put("applicationId", application.getString("leaseApplicationId"));
                row.put("productId", application.getString("productId"));
                row.put("statusId", application.getString("statusId"));
                applications.add(row);
            }
            List<Map<String, Object>> caseTypes = new ArrayList<>();
            List<GenericValue> typeValues = EntityQuery.use(delegator).from("Enumeration")
                    .where("enumTypeId", "SUPPORT_CASE_TYPE").orderBy("sequenceId").queryList();
            for (GenericValue type : typeValues) {
                Map<String, Object> row = new HashMap<>();
                row.put("caseTypeEnumId", type.getString("enumId"));
                row.put("description", type.getString("description"));
                caseTypes.add(row);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("contracts", contracts);
            result.put("products", products);
            result.put("applications", applications);
            result.put("caseTypes", caseTypes);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> uploadSupportAttachment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String customerPartyId = getString(context, "customerPartyId");
        String communicationEventId = getString(context, "communicationEventId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (!ownsCase(supportCase, customerPartyId)) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            if (UtilValidate.isEmpty(communicationEventId)) {
                GenericValue lastLink = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                        .where("supportCaseId", supportCaseId, "isInternal", "N")
                        .orderBy("-addedDate").queryFirst();
                if (lastLink == null) {
                    return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
                }
                communicationEventId = lastLink.getString("communicationEventId");
            } else {
                GenericValue link = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                        .where("supportCaseId", supportCaseId,
                                "communicationEventId", communicationEventId).queryOne();
                if (link == null) {
                    return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
                }
            }
            String fileName = getString(context, "fileName");
            String contentType = getString(context, "contentType");
            Map<String, Object> contentContext = new HashMap<>();
            contentContext.put("userLogin", getSystemUser(delegator));
            contentContext.put("dataResourceTypeId", "LOCAL_FILE");
            contentContext.put("dataTemplateTypeId", "NONE");
            contentContext.put("dataResourceName", UtilValidate.isEmpty(fileName) ? "attachment" : fileName);
            contentContext.put("mimeTypeId", UtilValidate.isEmpty(contentType) ? "application/octet-stream" : contentType);
            contentContext.put("contentTypeId", "DOCUMENT");
            contentContext.put("uploadedFile", context.get("uploadedFile"));
            contentContext.put("_uploadedFile_fileName", fileName);
            contentContext.put("_uploadedFile_contentType", contentType);
            Map<String, Object> contentResult = dispatcher.runSync("createContentFromUploadedFile", contentContext);
            if (ServiceUtil.isError(contentResult)) {
                return contentResult;
            }
            String contentId = (String) contentResult.get("contentId");
            GenericValue assoc = delegator.makeValue("CommEventContentAssoc");
            assoc.set("contentId", contentId);
            assoc.set("communicationEventId", communicationEventId);
            assoc.set("fromDate", UtilDateTime.nowTimestamp());
            assoc.set("sequenceNum", BigDecimal.ONE);
            assoc.create();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("contentId", contentId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> routeInboundSupportCommunication(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        String communicationEventId = getString(context, "communicationEventId");
        try {
            GenericValue inboundEvent = EntityQuery.use(delegator).from("CommunicationEvent")
                    .where("communicationEventId", communicationEventId).queryOne();
            if (inboundEvent == null || !"AUTO_EMAIL_COMM".equals(inboundEvent.getString("communicationEventTypeId"))) {
                return ServiceUtil.returnSuccess();
            }
            String customerPartyId = inboundEvent.getString("partyIdFrom");
            if (UtilValidate.isEmpty(customerPartyId)) {
                return ServiceUtil.returnSuccess();
            }
            boolean forced = isTrue(context.get("force"));
            if (!forced) {
                if (!"Y".equalsIgnoreCase(UtilProperties.getPropertyValue("support", "support.auto.create.cases", "Y"))) {
                    return ServiceUtil.returnSuccess();
                }
                String supportPartyId = UtilProperties.getPropertyValue("support", "support.inbound.partyId", "");
                if (UtilValidate.isNotEmpty(supportPartyId) && !supportPartyId.equals(inboundEvent.getString("partyIdTo"))) {
                    return ServiceUtil.returnSuccess();
                }
                String addresses = UtilProperties.getPropertyValue("support", "support.inbound.email.addresses", "");
                if (UtilValidate.isNotEmpty(addresses)) {
                    String toAddress = inboundEvent.getString("toString");
                    if (UtilValidate.isEmpty(toAddress) && UtilValidate.isNotEmpty(inboundEvent.getString("contactMechIdTo"))) {
                        GenericValue toContactMech = EntityQuery.use(delegator).from("ContactMech")
                                .where("contactMechId", inboundEvent.getString("contactMechIdTo")).queryOne();
                        toAddress = toContactMech == null ? null : toContactMech.getString("infoString");
                    }
                    boolean matched = false;
                    for (String address : addresses.split(",")) {
                        if (UtilValidate.isNotEmpty(address) && toAddress != null && toAddress.contains(address.trim())) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        return ServiceUtil.returnSuccess();
                    }
                }
            }

            String supportCaseId = findCaseByEvent(delegator, inboundEvent.getString("parentCommEventId"));
            if (UtilValidate.isEmpty(supportCaseId)) {
                supportCaseId = findCaseByEvent(delegator, inboundEvent.getString("origCommEventId"));
            }
            if (UtilValidate.isEmpty(supportCaseId)) {
                supportCaseId = findCaseBySubjectToken(delegator, inboundEvent.getString("subject"), customerPartyId);
            }
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase != null && STATUS_CANCELLED.equals(supportCase.getString("statusId"))) {
                supportCase = null;
                supportCaseId = null;
            }
            boolean created = false;
            if (supportCase == null) {
                Map<String, Object> createContext = new HashMap<>();
                createContext.put("userLogin", getUserLogin(delegator, context));
                createContext.put("customerPartyId", customerPartyId);
                createContext.put("caseTypeEnumId", "SUPCT_INQUIRY");
                createContext.put("sourceEnumId", "SUPCSRC_EMAIL");
                createContext.put("subject", inboundEvent.getString("subject"));
                createContext.put("description", truncate(inboundEvent.getString("content"), 4000));
                createContext.put("openedAt", inboundEvent.getTimestamp("entryDate"));
                createContext.put("rootCommunicationEventId", communicationEventId);
                Map<String, Object> createResult = dispatcher.runSync("createSupportCase", createContext);
                if (ServiceUtil.isError(createResult)) {
                    return createResult;
                }
                supportCaseId = (String) createResult.get("supportCaseId");
                supportCase = findCase(delegator, supportCaseId);
                created = true;
            } else {
                Map<String, Object> statusContext = new HashMap<>(context);
                statusContext.put("supportCaseId", supportCaseId);
                Map<String, Object> statusResult = openCaseIfNeeded(dctx, statusContext, supportCase, "Inbound message");
                if (ServiceUtil.isError(statusResult)) {
                    return statusResult;
                }
                supportCase = findCase(delegator, supportCaseId);
            }
            boolean linked = linkMessage(delegator, supportCase, inboundEvent, "SUPMSG_IN", false, false,
                    getUserLoginId(context));
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCaseId", supportCaseId);
            result.put("caseCreated", created);
            result.put("messageLinked", linked);

            String autoAck = getString(context, "autoAck");
            if (!"N".equalsIgnoreCase(autoAck)
                    && "Y".equalsIgnoreCase(UtilProperties.getPropertyValue("support", "support.email.auto.ack", "Y"))) {
                Map<String, Object> ackResult = createAutoAck(dctx, context, supportCase, inboundEvent);
                if (ServiceUtil.isSuccess(ackResult) && ackResult.get("communicationEventId") != null) {
                    result.put("autoAckCommunicationEventId", ackResult.get("communicationEventId"));
                }
            }
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> linkOrphanInboundSupportEmails(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        int processed = 0;
        try {
            Timestamp threshold = new Timestamp(UtilDateTime.nowTimestamp().getTime() - 5 * 60_000L);
            List<GenericValue> events = EntityQuery.use(delegator).from("CommunicationEvent")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("communicationEventTypeId", "AUTO_EMAIL_COMM"),
                            EntityCondition.makeCondition("entryDate", EntityOperator.LESS_THAN, threshold)))
                    .orderBy("entryDate").queryList();
            for (GenericValue event : events) {
                if (findCaseByEvent(delegator, event.getString("communicationEventId")) != null) {
                    continue;
                }
                Map<String, Object> routeContext = new HashMap<>();
                routeContext.put("communicationEventId", event.getString("communicationEventId"));
                routeContext.put("force", true);
                routeContext.put("autoAck", "N");
                routeContext.put("userLogin", getUserLogin(delegator, context));
                Map<String, Object> routeResult = dispatcher.runSync("routeInboundSupportCommunication", routeContext);
                if (ServiceUtil.isSuccess(routeResult) && routeResult.get("supportCaseId") != null) {
                    processed++;
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("processedCount", processed);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> findSupportCases(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        List<EntityCondition> conditions = new ArrayList<>();
        addEquals(conditions, "supportCaseId", getString(context, "supportCaseId"));
        addEquals(conditions, "customerPartyId", getString(context, "customerPartyId"));
        addEquals(conditions, "statusId", getString(context, "statusId"));
        addEquals(conditions, "assigneePartyId", getString(context, "assigneePartyId"));
        addEquals(conditions, "queueId", getString(context, "queueId"));
        addEquals(conditions, "financeContractId", getString(context, "financeContractId"));
        addEquals(conditions, "productId", getString(context, "productId"));
        String subject = getString(context, "subject");
        if (UtilValidate.isNotEmpty(subject)) {
            conditions.add(EntityCondition.makeCondition("subject", EntityOperator.LIKE, "%" + subject + "%"));
        }
        try {
            EntityQuery query = EntityQuery.use(delegator).from("SupportCase");
            if (!conditions.isEmpty()) {
                query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            }
            List<GenericValue> allCases = query.orderBy("-openedAt").queryList();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCases", allCases);
            result.put("caseCount", allCases.size());
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getSupportMetrics(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Timestamp now = UtilDateTime.nowTimestamp();
        Timestamp fromDate = context.get("fromDate") instanceof Timestamp
                ? (Timestamp) context.get("fromDate") : new Timestamp(now.getTime() - 30L * 24 * 60 * 60 * 1000);
        Timestamp thruDate = context.get("thruDate") instanceof Timestamp
                ? (Timestamp) context.get("thruDate") : now;
        Timestamp asOf = thruDate.after(now) ? now : thruDate;
        try {
            List<EntityCondition> conditions = new ArrayList<>();
            addEquals(conditions, "queueId", getString(context, "queueId"));
            addEquals(conditions, "assigneePartyId", getString(context, "assigneePartyId"));
            addEquals(conditions, "productId", getString(context, "productId"));
            addEquals(conditions, "caseTypeEnumId", getString(context, "caseTypeEnumId"));
            addEquals(conditions, "financeContractId", getString(context, "financeContractId"));
            EntityQuery query = EntityQuery.use(delegator).from("SupportCase");
            if (!conditions.isEmpty()) {
                query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            }
            List<GenericValue> cases = query.queryList();

            int openedCount = 0;
            int resolvedCount = 0;
            int closedCount = 0;
            int unresolvedCount = 0;
            int overdueCount = 0;
            long firstResponseTotalMinutes = 0;
            int firstResponseCount = 0;
            long resolutionTotalMinutes = 0;
            int resolutionCount = 0;
            int firstResponseSlaMet = 0;
            int firstResponseSlaTotal = 0;
            int resolutionSlaMet = 0;
            int resolutionSlaTotal = 0;
            long csatTotal = 0;
            int csatCount = 0;
            int[] csatScores = new int[6];

            Map<String, Map<String, Object>> byQueue = new LinkedHashMap<>();
            Map<String, Map<String, Object>> byAssignee = new LinkedHashMap<>();
            Map<String, Map<String, Object>> byCaseType = new LinkedHashMap<>();
            Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();

            for (GenericValue supportCase : cases) {
                Timestamp openedAt = supportCase.getTimestamp("openedAt");
                Timestamp resolvedAt = supportCase.getTimestamp("resolvedAt");
                Timestamp closedAt = supportCase.getTimestamp("closedAt");
                boolean opened = inPeriod(openedAt, fromDate, thruDate);
                boolean resolved = inPeriod(resolvedAt, fromDate, thruDate);
                boolean closed = inPeriod(closedAt, fromDate, thruDate);
                boolean unresolved = isUnresolvedAt(supportCase, thruDate);
                boolean overdue = unresolved && isOverdueAt(supportCase, asOf);

                if (opened) {
                    openedCount++;
                }
                if (resolved) {
                    resolvedCount++;
                }
                if (closed) {
                    closedCount++;
                }
                if (unresolved) {
                    unresolvedCount++;
                }
                if (overdue) {
                    overdueCount++;
                }
                if (opened && supportCase.get("firstResponseAt") != null) {
                    firstResponseTotalMinutes += (supportCase.getTimestamp("firstResponseAt").getTime()
                            - openedAt.getTime()) / 60_000L;
                    firstResponseCount++;
                }
                if (resolved && resolvedAt != null && openedAt != null) {
                    resolutionTotalMinutes += (resolvedAt.getTime() - openedAt.getTime()) / 60_000L;
                    resolutionCount++;
                }
                Timestamp firstResponseDueAt = supportCase.getTimestamp("firstResponseDueAt");
                if (opened && firstResponseDueAt != null) {
                    if (supportCase.get("firstResponseAt") != null) {
                        firstResponseSlaTotal++;
                        if (!supportCase.getTimestamp("firstResponseAt").after(firstResponseDueAt)) {
                            firstResponseSlaMet++;
                        }
                    } else if (!firstResponseDueAt.after(asOf)) {
                        firstResponseSlaTotal++;
                    }
                }
                Timestamp resolutionDueAt = supportCase.getTimestamp("resolutionDueAt");
                if (resolved && resolutionDueAt != null) {
                    resolutionSlaTotal++;
                    if (!resolvedAt.after(resolutionDueAt)) {
                        resolutionSlaMet++;
                    }
                }
                BigDecimal score = decimal(supportCase, "satisfactionScore");
                if (score != null && (resolved || closed)) {
                    csatTotal += score.longValue();
                    csatCount++;
                    int rating = score.intValue();
                    if (rating >= 1 && rating <= 5) {
                        csatScores[rating]++;
                    }
                }

                if (opened || resolved || closed || unresolved || overdue) {
                    incrementGroup(byQueue, supportCase.getString("queueId"), "queueId", opened, unresolved, overdue);
                    incrementGroup(byAssignee, supportCase.getString("assigneePartyId"), "assigneePartyId",
                            opened, unresolved, overdue);
                    incrementGroup(byCaseType, supportCase.getString("caseTypeEnumId"), "caseTypeEnumId",
                            opened, unresolved, overdue);
                    incrementGroup(byProduct, supportCase.getString("productId"), "productId",
                            opened, unresolved, overdue);
                }
            }

            enrichGroupNames(delegator, byQueue, "queue", "queueId");
            enrichGroupNames(delegator, byAssignee, "assignee", "assigneePartyId");
            enrichGroupNames(delegator, byCaseType, "caseType", "caseTypeEnumId");
            enrichGroupNames(delegator, byProduct, "product", "productId");

            int slaMet = firstResponseSlaMet + resolutionSlaMet;
            int slaTotal = firstResponseSlaTotal + resolutionSlaTotal;
            List<Map<String, Object>> csvRows = new ArrayList<>();
            addCsvSummary(csvRows, "fromDate", fromDate.toString());
            addCsvSummary(csvRows, "thruDate", thruDate.toString());
            addCsvSummary(csvRows, "openedCount", openedCount);
            addCsvSummary(csvRows, "resolvedCount", resolvedCount);
            addCsvSummary(csvRows, "closedCount", closedCount);
            addCsvSummary(csvRows, "unresolvedCount", unresolvedCount);
            addCsvSummary(csvRows, "overdueCount", overdueCount);
            addCsvSummary(csvRows, "avgFirstResponseMinutes", firstResponseCount == 0 ? 0.0
                    : round1((double) firstResponseTotalMinutes / firstResponseCount));
            addCsvSummary(csvRows, "avgResolutionMinutes", resolutionCount == 0 ? 0.0
                    : round1((double) resolutionTotalMinutes / resolutionCount));
            addCsvSummary(csvRows, "slaComplianceRate", slaTotal == 0 ? 0.0 : round1(100.0 * slaMet / slaTotal));
            addCsvSummary(csvRows, "csatAverage", csatCount == 0 ? 0.0 : round1((double) csatTotal / csatCount));
            addCsvSummary(csvRows, "csatCount", csatCount);
            for (int rating = 1; rating <= 5; rating++) {
                addCsvSummary(csvRows, "csatScore" + rating, csatScores[rating]);
            }
            for (Map<String, Object> group : byQueue.values()) {
                addCsvGroup(csvRows, "QUEUE", group);
            }
            for (Map<String, Object> group : byAssignee.values()) {
                addCsvGroup(csvRows, "ASSIGNEE", group);
            }
            for (Map<String, Object> group : byCaseType.values()) {
                addCsvGroup(csvRows, "CASE_TYPE", group);
            }
            for (Map<String, Object> group : byProduct.values()) {
                addCsvGroup(csvRows, "PRODUCT", group);
            }

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("fromDate", fromDate);
            metrics.put("thruDate", thruDate);
            metrics.put("openedCount", openedCount);
            metrics.put("resolvedCount", resolvedCount);
            metrics.put("closedCount", closedCount);
            metrics.put("unresolvedCount", unresolvedCount);
            metrics.put("overdueCount", overdueCount);
            metrics.put("avgFirstResponseMinutes", firstResponseCount == 0 ? 0.0
                    : round1((double) firstResponseTotalMinutes / firstResponseCount));
            metrics.put("avgResolutionMinutes", resolutionCount == 0 ? 0.0
                    : round1((double) resolutionTotalMinutes / resolutionCount));
            metrics.put("firstResponseSlaMet", firstResponseSlaMet);
            metrics.put("firstResponseSlaTotal", firstResponseSlaTotal);
            metrics.put("resolutionSlaMet", resolutionSlaMet);
            metrics.put("resolutionSlaTotal", resolutionSlaTotal);
            metrics.put("slaComplianceRate", slaTotal == 0 ? 0.0 : round1(100.0 * slaMet / slaTotal));
            metrics.put("csatAverage", csatCount == 0 ? 0.0 : round1((double) csatTotal / csatCount));
            metrics.put("csatCount", csatCount);
            metrics.put("csatScore1", csatScores[1]);
            metrics.put("csatScore2", csatScores[2]);
            metrics.put("csatScore3", csatScores[3]);
            metrics.put("csatScore4", csatScores[4]);
            metrics.put("csatScore5", csatScores[5]);
            metrics.put("byQueue", new ArrayList<>(byQueue.values()));
            metrics.put("byAssignee", new ArrayList<>(byAssignee.values()));
            metrics.put("byCaseType", new ArrayList<>(byCaseType.values()));
            metrics.put("byProduct", new ArrayList<>(byProduct.values()));
            metrics.put("csvRows", csvRows);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("metrics", metrics);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void addCsvSummary(List<Map<String, Object>> rows, String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section", "SUMMARY");
        row.put("key", key);
        row.put("value", value);
        rows.add(row);
    }

    private static void addCsvGroup(List<Map<String, Object>> rows, String section, Map<String, Object> group) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section", section);
        row.put("key", group.get("name"));
        row.put("openedCount", group.get("openedCount"));
        row.put("unresolvedCount", group.get("unresolvedCount"));
        row.put("overdueCount", group.get("overdueCount"));
        rows.add(row);
    }

    private static void enrichGroupNames(Delegator delegator, Map<String, Map<String, Object>> groups,
            String kind, String idField) throws GenericEntityException {
        for (Map.Entry<String, Map<String, Object>> entry : groups.entrySet()) {
            String key = entry.getKey();
            String name = "_NA_".equals(key) ? "-" : key;
            if (!"_NA_".equals(key)) {
                if ("queue".equals(kind)) {
                    GenericValue queue = EntityQuery.use(delegator).from("SupportQueue")
                            .where("queueId", key).queryOne();
                    if (queue != null && UtilValidate.isNotEmpty(queue.getString("queueName"))) {
                        name = queue.getString("queueName");
                    }
                } else if ("assignee".equals(kind)) {
                    GenericValue person = EntityQuery.use(delegator).from("PartyNameView")
                            .where("partyId", key).queryOne();
                    if (person != null) {
                        String firstName = person.getString("firstName");
                        String lastName = person.getString("lastName");
                        String groupName = person.getString("groupName");
                        if (UtilValidate.isNotEmpty(firstName) || UtilValidate.isNotEmpty(lastName)) {
                            name = (nullToEmpty(firstName) + " " + nullToEmpty(lastName)).trim();
                        } else if (UtilValidate.isNotEmpty(groupName)) {
                            name = groupName;
                        }
                    }
                } else if ("caseType".equals(kind)) {
                    GenericValue enumeration = EntityQuery.use(delegator).from("Enumeration")
                            .where("enumId", key).queryOne();
                    if (enumeration != null && UtilValidate.isNotEmpty(enumeration.getString("description"))) {
                        name = enumeration.getString("description");
                    }
                } else if ("product".equals(kind)) {
                    GenericValue product = EntityQuery.use(delegator).from("Product")
                            .where("productId", key).queryOne();
                    if (product != null && UtilValidate.isNotEmpty(product.getString("productName"))) {
                        name = product.getString("productName");
                    }
                }
            }
            entry.getValue().put(idField, key);
            entry.getValue().put("name", name);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean inPeriod(Timestamp value, Timestamp fromDate, Timestamp thruDate) {
        return value != null && !value.before(fromDate) && !value.after(thruDate);
    }

    private static boolean isUnresolvedAt(GenericValue supportCase, Timestamp asOf) {
        Timestamp openedAt = supportCase.getTimestamp("openedAt");
        if (openedAt == null || openedAt.after(asOf)) {
            return false;
        }
        Timestamp resolvedAt = supportCase.getTimestamp("resolvedAt");
        if (resolvedAt != null && !resolvedAt.after(asOf)) {
            return false;
        }
        Timestamp closedAt = supportCase.getTimestamp("closedAt");
        if (closedAt != null && !closedAt.after(asOf)) {
            return false;
        }
        String statusId = supportCase.getString("statusId");
        if (STATUS_RESOLVED.equals(statusId) && resolvedAt == null) {
            return false;
        }
        if (STATUS_CLOSED.equals(statusId) && closedAt == null && resolvedAt == null) {
            return false;
        }
        return !(STATUS_CANCELLED.equals(statusId) && resolvedAt == null && closedAt == null);
    }

    private static boolean isOverdueAt(GenericValue supportCase, Timestamp asOf) {
        Timestamp firstResponseAt = supportCase.getTimestamp("firstResponseAt");
        Timestamp firstResponseDueAt = supportCase.getTimestamp("firstResponseDueAt");
        Timestamp resolvedAt = supportCase.getTimestamp("resolvedAt");
        Timestamp resolutionDueAt = supportCase.getTimestamp("resolutionDueAt");
        boolean firstResponseOverdue = (firstResponseAt == null || firstResponseAt.after(asOf))
                && firstResponseDueAt != null && firstResponseDueAt.before(asOf);
        boolean resolutionOverdue = (resolvedAt == null || resolvedAt.after(asOf))
                && resolutionDueAt != null && resolutionDueAt.before(asOf);
        return firstResponseOverdue || resolutionOverdue;
    }

    private static void incrementGroup(Map<String, Map<String, Object>> groups, String key, String idField,
            boolean opened, boolean unresolved, boolean overdue) {
        String groupKey = UtilValidate.isEmpty(key) ? "_NA_" : key;
        Map<String, Object> group = groups.computeIfAbsent(groupKey, unused -> {
            Map<String, Object> row = new HashMap<>();
            row.put(idField, groupKey);
            row.put("openedCount", 0);
            row.put("unresolvedCount", 0);
            row.put("overdueCount", 0);
            return row;
        });
        if (opened) {
            group.put("openedCount", ((Integer) group.get("openedCount")) + 1);
        }
        if (unresolved) {
            group.put("unresolvedCount", ((Integer) group.get("unresolvedCount")) + 1);
        }
        if (overdue) {
            group.put("overdueCount", ((Integer) group.get("overdueCount")) + 1);
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void addEquals(List<EntityCondition> conditions, String field, String value) {
        if (UtilValidate.isNotEmpty(value)) {
            conditions.add(EntityCondition.makeCondition(field, value));
        }
    }
}
