/*
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
 */
package org.apache.ofbiz.support.test

import java.nio.ByteBuffer

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP S4: customer portal API (ownership, internal filtering, idempotency,
 * close/rating and form options).
 */
class SupportApiTests extends OFBizTestCase {

    SupportApiTests(String name) {
        super(name)
    }

    private GenericValue apiLogin() {
        return from('UserLogin').where('userLoginId', 'support_test_api').queryOne()
    }

    private GenericValue agentLogin() {
        return from('UserLogin').where('userLoginId', 'support_test_agent').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase(String externalId) {
        Map result = dispatcher.runSync('createSupportCaseForCustomer', [
                userLogin: apiLogin(),
                customerPartyId: 'TEST_SUPPORT_CUST',
                caseTypeEnumId: 'SUPCT_INQUIRY',
                financeContractId: 'TEST_SUPPORT_CTR',
                subject: 'API support case',
                description: 'Created through the customer API',
                externalId: externalId
        ])
        ok(result)
        return result.supportCaseId
    }

    void testCreateIdempotentAndList() {
        String supportCaseId = createCase('API-IDEMPOTENT-1')
        Map duplicate = dispatcher.runSync('createSupportCaseForCustomer', [
                userLogin: apiLogin(),
                customerPartyId: 'TEST_SUPPORT_CUST',
                subject: 'Different subject',
                externalId: 'API-IDEMPOTENT-1'
        ])
        ok(duplicate)
        assert duplicate.supportCaseId == supportCaseId

        Map list = dispatcher.runSync('listSupportCasesForCustomer', [
                userLogin: apiLogin(), customerPartyId: 'TEST_SUPPORT_CUST'
        ])
        ok(list)
        assert list.supportCases.any { it.supportCaseId == supportCaseId }
    }

    void testGetHidesInternalNotes() {
        String supportCaseId = createCase('API-INTERNAL-1')
        Map note = dispatcher.runSync('addSupportCaseInternalNote', [
                userLogin: agentLogin(),
                supportCaseId: supportCaseId,
                subject: 'Internal note',
                content: 'This must not be visible to the customer'
        ])
        ok(note)

        Map detail = dispatcher.runSync('getSupportCaseForCustomer', [
                userLogin: apiLogin(), supportCaseId: supportCaseId, customerPartyId: 'TEST_SUPPORT_CUST'
        ])
        ok(detail)
        assert detail.supportCase.supportCaseId == supportCaseId
        assert detail.canReply
        assert detail.messages.every { it.isInternal != 'Y' }
        assert detail.messages.every { it.content != 'This must not be visible to the customer' }
    }

    void testReplyCloseAndRate() {
        String supportCaseId = createCase('API-LIFECYCLE-1')
        Map reply = dispatcher.runSync('replySupportCaseFromCustomer', [
                userLogin: apiLogin(),
                supportCaseId: supportCaseId,
                customerPartyId: 'TEST_SUPPORT_CUST',
                content: 'Customer reply from the API'
        ])
        ok(reply)
        assert reply.communicationEventId
        assert reply.statusId == 'SUPCS_OPEN'

        Map resolve = dispatcher.runSync('resolveSupportCase', [
                userLogin: agentLogin(), supportCaseId: supportCaseId,
                resolutionEnumId: 'SUPRES_ANSWERED', note: 'Answered by support'
        ])
        ok(resolve)

        Map close = dispatcher.runSync('closeSupportCaseByCustomer', [
                userLogin: apiLogin(), supportCaseId: supportCaseId,
                customerPartyId: 'TEST_SUPPORT_CUST', reason: 'Resolved'
        ])
        ok(close)
        assert close.statusId == 'SUPCS_CLOSED'

        Map rating = dispatcher.runSync('rateSupportCase', [
                userLogin: apiLogin(), supportCaseId: supportCaseId,
                customerPartyId: 'TEST_SUPPORT_CUST',
                satisfactionScore: '5', satisfactionComment: 'Good'
        ])
        ok(rating)
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert supportCase.getLong('satisfactionScore') == 5L
    }

    void testOwnershipAndFormOptions() {
        String supportCaseId = createCase('API-OWNERSHIP-1')
        Map denied = dispatcher.runSync('getSupportCaseForCustomer', [
                userLogin: apiLogin(), supportCaseId: supportCaseId, customerPartyId: 'admin'
        ])
        assert ServiceUtil.isError(denied)

        Map options = dispatcher.runSync('getSupportCaseFormOptions', [
                userLogin: apiLogin(), customerPartyId: 'TEST_SUPPORT_CUST'
        ])
        ok(options)
        assert options.contracts.any { it.financeContractId == 'TEST_SUPPORT_CTR' }
        assert options.caseTypes.any { it.caseTypeEnumId == 'SUPCT_INQUIRY' }
    }

    void testUploadAttachment() {
        String supportCaseId = createCase('API-ATTACHMENT-1')
        Map reply = dispatcher.runSync('replySupportCaseFromCustomer', [
                userLogin: apiLogin(), supportCaseId: supportCaseId,
                customerPartyId: 'TEST_SUPPORT_CUST', content: 'Please see the attachment'
        ])
        ok(reply)
        ByteBuffer file = ByteBuffer.wrap('attachment body'.getBytes('UTF-8'))
        Map upload = dispatcher.runSync('uploadSupportAttachment', [
                userLogin: apiLogin(), supportCaseId: supportCaseId,
                customerPartyId: 'TEST_SUPPORT_CUST',
                communicationEventId: reply.communicationEventId,
                fileName: 'test.txt', contentType: 'text/plain', uploadedFile: file
        ])
        ok(upload)
        assert upload.contentId
    }
}
