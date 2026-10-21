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
import java.sql.Timestamp

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase
import org.apache.ofbiz.support.audit.SupportDataMasker

/**
 * STEP S6: audit, logical deletion, retention, masking and attachment access.
 */
class SupportAuditTests extends OFBizTestCase {

    SupportAuditTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private GenericValue agentLogin() {
        return from('UserLogin').where('userLoginId', 'support_test_agent').queryOne()
    }

    private GenericValue apiLogin() {
        return from('UserLogin').where('userLoginId', 'support_test_api').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase(String subject) {
        Map result = dispatcher.runSync('createSupportCase', [
                userLogin: admin(),
                customerPartyId: 'TEST_SUPPORT_CUST',
                caseTypeEnumId: 'SUPCT_INQUIRY',
                sourceEnumId: 'SUPCSRC_STAFF',
                financeContractId: 'TEST_SUPPORT_CTR',
                subject: subject,
                description: 'S6 audit test'
        ])
        ok(result)
        return result.supportCaseId
    }

    void testAuditLifecycleAndPermission() {
        String supportCaseId = createCase('Audit lifecycle')

        Map get = dispatcher.runSync('getSupportCase', [userLogin: admin(), supportCaseId: supportCaseId])
        ok(get)

        Map update = dispatcher.runSync('updateSupportCase', [
                userLogin: admin(), supportCaseId: supportCaseId, subject: 'Audit lifecycle updated'
        ])
        ok(update)

        Map assign = dispatcher.runSync('assignSupportCase', [
                userLogin: admin(), supportCaseId: supportCaseId,
                queueId: 'SUPPORT_DEFAULT', assigneePartyId: 'TEST_SUPPORT_AGENT'
        ])
        ok(assign)

        Map status = dispatcher.runSync('changeSupportCaseStatus', [
                userLogin: admin(), supportCaseId: supportCaseId, statusId: 'SUPCS_OPEN', note: 'Open'
        ])
        ok(status)

        Map logs = dispatcher.runSync('findSupportAuditLogs', [
                userLogin: admin(), supportCaseId: supportCaseId
        ])
        ok(logs)
        Set<String> actions = logs.auditLogs.collect { it.actionEnumId } as Set
        assert actions.contains('SUPAUD_CREATE')
        assert actions.contains('SUPAUD_VIEW')
        assert actions.contains('SUPAUD_UPDATE')
        assert actions.contains('SUPAUD_ASSIGN')
        assert actions.contains('SUPAUD_STATUS')
        assert logs.auditLogs.every { it.summary != null }

        Map permission = dispatcher.runSync('supportAuditPermission', [userLogin: agentLogin()])
        ok(permission)
        assert !permission.hasPermission
    }

    void testLogicalDeleteHidesCase() {
        String supportCaseId = createCase('Logical delete target')
        Map result = dispatcher.runSync('deleteSupportCase', [userLogin: admin(), supportCaseId: supportCaseId])
        ok(result)

        GenericValue raw = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert raw.isDeleted == 'Y'
        assert raw.deletedAt != null
        assert raw.deletedByUserLogin == 'system'

        Map get = dispatcher.runSync('getSupportCase', [userLogin: admin(), supportCaseId: supportCaseId])
        assert ServiceUtil.isError(get)

        Map found = dispatcher.runSync('findSupportCases', [userLogin: admin(), supportCaseId: supportCaseId])
        ok(found)
        assert found.caseCount == 0

        Map logs = dispatcher.runSync('findSupportAuditLogs', [
                userLogin: admin(), supportCaseId: supportCaseId
        ])
        ok(logs)
        assert logs.auditLogs.any { it.actionEnumId == 'SUPAUD_DELETE' }
    }

    void testRetentionAnonymizesPersonalData() {
        String supportCaseId = createCase('Retention target')
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        supportCase.set('statusId', 'SUPCS_CLOSED')
        supportCase.set('closedAt', new Timestamp(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000))
        supportCase.set('subject', 'Retention customer a@example.com')
        supportCase.set('description', 'Call 090-1234-5678 about account 123456789012')
        supportCase.store()

        Map result = dispatcher.runSync('applySupportRetention', [
                userLogin: admin(), retentionDays: 1, mode: 'ANONYMIZE', limit: 20
        ])
        ok(result)
        supportCase.refresh()
        assert supportCase.subject == '[anonymized]'
        assert supportCase.description == '[anonymized]'
        assert supportCase.anonymizedAt != null

        Map logs = dispatcher.runSync('findSupportAuditLogs', [
                userLogin: admin(), supportCaseId: supportCaseId
        ])
        ok(logs)
        assert logs.auditLogs.any { it.actionEnumId == 'SUPAUD_ANONYMIZE' }
    }

    void testCustomerApiHidesContactFieldsAndDownloadsAudit() {
        Map created = dispatcher.runSync('createSupportCaseForCustomer', [
                userLogin: apiLogin(),
                customerPartyId: 'TEST_SUPPORT_CUST',
                subject: 'S6 API contact test',
                description: 'Contact fields must not be returned'
        ])
        ok(created)
        String supportCaseId = created.supportCaseId

        Map reply = dispatcher.runSync('replySupportCase', [
                userLogin: agentLogin(), supportCaseId: supportCaseId,
                subject: 'Staff reply', content: 'Reply body', sendNow: false
        ])
        ok(reply)

        Map get = dispatcher.runSync('getSupportCaseForCustomer', [
                userLogin: apiLogin(), customerPartyId: 'TEST_SUPPORT_CUST', supportCaseId: supportCaseId
        ])
        ok(get)
        assert get.messages
        assert get.messages.every { !it.containsKey('fromString') && !it.containsKey('toString') }
        assert get.messages.every { !it.containsKey('partyIdFrom') && !it.containsKey('partyIdTo') }

        Map upload = dispatcher.runSync('uploadSupportAttachment', [
                userLogin: apiLogin(),
                customerPartyId: 'TEST_SUPPORT_CUST',
                supportCaseId: supportCaseId,
                communicationEventId: reply.communicationEventId,
                fileName: 's6.txt',
                contentType: 'text/plain',
                uploadedFile: ByteBuffer.wrap('S6 attachment'.getBytes('UTF-8'))
        ])
        ok(upload)

        Map download = dispatcher.runSync('downloadSupportAttachment', [
                userLogin: apiLogin(),
                customerPartyId: 'TEST_SUPPORT_CUST',
                supportCaseId: supportCaseId,
                contentId: upload.contentId
        ])
        ok(download)
        assert download.contentId == upload.contentId
        assert download.fileName == 's6.txt'

        Map staffDownload = dispatcher.runSync('getSupportAttachment', [
                userLogin: admin(), supportCaseId: supportCaseId, contentId: upload.contentId
        ])
        ok(staffDownload)

        Map logs = dispatcher.runSync('findSupportAuditLogs', [
                userLogin: admin(), supportCaseId: supportCaseId
        ])
        ok(logs)
        assert logs.auditLogs.any { it.actionEnumId == 'SUPAUD_DOWNLOAD' }
    }

    void testMasker() {
        assert SupportDataMasker.maskEmail('customer@example.com') == 'c***@example.com'
        assert SupportDataMasker.maskPhone('090-1234-5678').endsWith('5678')
        assert SupportDataMasker.maskAccount('123456789012').startsWith('****')
    }
}
