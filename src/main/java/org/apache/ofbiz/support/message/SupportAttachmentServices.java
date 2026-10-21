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
package org.apache.ofbiz.support.message;

import java.util.Locale;
import java.util.Map;

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
import org.apache.ofbiz.support.audit.SupportAuditServices;

/**
 * STEP S6: permission-checked attachment metadata access.
 *
 * <p>Binary streaming is intentionally left to the efinance/content layer.
 * This service returns only metadata and text data after checking that the
 * content is attached to a visible message of the case. It never returns a
 * DataResource URL.</p>
 */
public final class SupportAttachmentServices {

    private static final String RESOURCE = "SupportUiLabels";

    private SupportAttachmentServices() {
    }

    public static Map<String, Object> getSupportAttachment(DispatchContext dctx, Map<String, Object> context) {
        return getAttachment(dctx, context, false);
    }

    public static Map<String, Object> downloadSupportAttachment(DispatchContext dctx, Map<String, Object> context) {
        return getAttachment(dctx, context, true);
    }

    private static Map<String, Object> getAttachment(DispatchContext dctx, Map<String, Object> context,
            boolean customerApi) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String supportCaseId = getString(context, "supportCaseId");
        String contentId = getString(context, "contentId");
        try {
            GenericValue supportCase = findCase(delegator, supportCaseId);
            if (supportCase == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            if (customerApi && !UtilValidate.isNotEmpty(getString(context, "customerPartyId"))) {
                return ServiceUtil.returnError(message(locale, "SupportApiCustomerRequired"));
            }
            if (customerApi && !getString(context, "customerPartyId").equals(supportCase.getString("customerPartyId"))) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            GenericValue assoc = EntityQuery.use(delegator).from("CommEventContentAssoc")
                    .where("contentId", contentId).queryFirst();
            if (assoc == null) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            GenericValue link = EntityQuery.use(delegator).from("SupportCaseCommEvent")
                    .where("supportCaseId", supportCaseId,
                            "communicationEventId", assoc.getString("communicationEventId")).queryOne();
            if (link == null || (customerApi && "Y".equals(link.getString("isInternal")))) {
                return ServiceUtil.returnError(message(locale, "SupportCaseNotFound"));
            }
            GenericValue view = EntityQuery.use(delegator).from("ContentDataResourceView")
                    .where("contentId", contentId).queryOne();
            if (view == null) {
                return ServiceUtil.returnError(message(locale, "SupportAttachmentNotFound"));
            }
            String mimeTypeId = view.getString("mimeTypeId");
            if (UtilValidate.isEmpty(mimeTypeId)) {
                mimeTypeId = view.getString("drMimeTypeId");
            }
            String fileName = view.getString("drDataResourceName");
            if (UtilValidate.isEmpty(fileName)) {
                fileName = view.getString("dataResourceName");
            }
            String textData = null;
            String dataResourceId = view.getString("drDataResourceId");
            if (UtilValidate.isNotEmpty(dataResourceId)) {
                GenericValue text = EntityQuery.use(delegator).from("ElectronicText")
                        .where("dataResourceId", dataResourceId).queryOne();
                textData = text == null ? null : text.getString("textData");
            }
            SupportAuditServices.record(delegator, context, supportCaseId, SupportAuditServices.ACTION_DOWNLOAD,
                    customerApi ? SupportAuditServices.CHANNEL_API : SupportAuditServices.CHANNEL_STAFF,
                    contentId, "Attachment accessed", null);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("contentId", contentId);
            result.put("mimeTypeId", mimeTypeId);
            result.put("fileName", fileName);
            result.put("dataResourceTypeId", view.getString("drDataResourceTypeId"));
            result.put("textData", textData);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static GenericValue findCase(Delegator delegator, String supportCaseId) throws GenericEntityException {
        if (UtilValidate.isEmpty(supportCaseId)) {
            return null;
        }
        EntityCondition notDeleted = EntityCondition.makeCondition(EntityOperator.OR,
                EntityCondition.makeCondition("isDeleted", EntityOperator.EQUALS, null),
                EntityCondition.makeCondition("isDeleted", "N"));
        return EntityQuery.use(delegator).from("SupportCase")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("supportCaseId", supportCaseId), notDeleted))
                .queryOne();
    }

    private static String getString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }
}
