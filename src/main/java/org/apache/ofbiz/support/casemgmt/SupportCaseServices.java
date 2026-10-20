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
    private static void applySla(Delegator delegator, GenericValue supportCase) throws GenericEntityException {
        GenericValue sla = EntityQuery.use(delegator).from("SupportSla")
                .where("active", "Y")
                .orderBy("sequenceNum")
                .queryFirst();
        long firstResponseMinutes = 240L;
        long resolutionMinutes = 2880L;
        if (sla != null) {
            if (sla.get("firstResponseMinutes") != null) {
                firstResponseMinutes = sla.getLong("firstResponseMinutes");
            }
            if (sla.get("resolutionMinutes") != null) {
                resolutionMinutes = sla.getLong("resolutionMinutes");
            }
            supportCase.set("slaId", sla.getString("slaId"));
        }
        Timestamp openedAt = supportCase.getTimestamp("openedAt");
        if (openedAt == null) {
            openedAt = UtilDateTime.nowTimestamp();
            supportCase.set("openedAt", openedAt);
        }
        supportCase.set("firstResponseDueAt", new Timestamp(openedAt.getTime() + firstResponseMinutes * 60_000L));
        supportCase.set("resolutionDueAt", new Timestamp(openedAt.getTime() + resolutionMinutes * 60_000L));
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

    private static void addEquals(List<EntityCondition> conditions, String field, String value) {
        if (UtilValidate.isNotEmpty(value)) {
            conditions.add(EntityCondition.makeCondition(field, value));
        }
    }
}
