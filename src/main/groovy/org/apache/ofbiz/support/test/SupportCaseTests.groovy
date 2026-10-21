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

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase
import org.apache.ofbiz.widget.model.ScreenFactory

/**
 * STEP S1: support case lifecycle, assignment and status history.
 */
class SupportCaseTests extends OFBizTestCase {

    SupportCaseTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private GenericValue agentLogin() {
        return from('UserLogin').where('userLoginId', 'support_test_agent').queryOne()
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
                subject: subject,
                description: 'Test support case'
        ])
        ok(result)
        return result.supportCaseId
    }

    private String createCaseWithContract(String subject) {
        Map result = dispatcher.runSync('createSupportCase', [
                userLogin: admin(),
                customerPartyId: 'TEST_SUPPORT_CUST',
                caseTypeEnumId: 'SUPCT_INQUIRY',
                sourceEnumId: 'SUPCSRC_STAFF',
                financeContractId: 'TEST_SUPPORT_CTR',
                subject: subject,
                description: 'Test support case with contract'
        ])
        ok(result)
        return result.supportCaseId
    }

    void testScreensParse() {
        assert ScreenFactory.getScreensFromLocation('component://support/widget/SupportScreens.xml').size() == 5
    }

    void testCreateAndGet() {        String supportCaseId = createCase('Create and get')
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert supportCase != null
        assert supportCase.statusId == 'SUPCS_NEW'
        assert supportCase.customerPartyId == 'TEST_SUPPORT_CUST'
        assert supportCase.openedAt != null
        assert supportCase.firstResponseDueAt != null
        assert supportCase.resolutionDueAt != null
        assert from('SupportCaseStatus').where('supportCaseId', supportCaseId).queryCount() == 1

        Map result = dispatcher.runSync('getSupportCase', [userLogin: admin(), supportCaseId: supportCaseId])
        ok(result)
        assert result.supportCase.supportCaseId == supportCaseId
        assert result.supportCaseStatuses.size() == 1
    }

    void testAssignmentAndLifecycle() {
        String supportCaseId = createCase('Lifecycle')

        Map result = dispatcher.runSync('assignSupportCase', [
                userLogin: admin(),
                supportCaseId: supportCaseId,
                queueId: 'SUPPORT_DEFAULT',
                assigneePartyId: 'TEST_SUPPORT_AGENT'
        ])
        ok(result)
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert supportCase.assigneePartyId == 'TEST_SUPPORT_AGENT'
        assert supportCase.queueId == 'SUPPORT_DEFAULT'

        result = dispatcher.runSync('changeSupportCaseStatus', [
                userLogin: admin(), supportCaseId: supportCaseId, statusId: 'SUPCS_OPEN', note: 'Opened'
        ])
        ok(result)
        supportCase.refresh()
        assert supportCase.statusId == 'SUPCS_OPEN'

        result = dispatcher.runSync('resolveSupportCase', [
                userLogin: admin(), supportCaseId: supportCaseId,
                resolutionEnumId: 'SUPRES_ANSWERED', note: 'Answered'
        ])
        ok(result)
        supportCase.refresh()
        assert supportCase.statusId == 'SUPCS_RESOLVED'
        assert supportCase.resolvedAt != null
        assert supportCase.resolutionEnumId == 'SUPRES_ANSWERED'

        result = dispatcher.runSync('closeSupportCase', [
                userLogin: admin(), supportCaseId: supportCaseId, note: 'Closed'
        ])
        ok(result)
        supportCase.refresh()
        assert supportCase.statusId == 'SUPCS_CLOSED'
        assert supportCase.closedAt != null

        result = dispatcher.runSync('reopenSupportCase', [
                userLogin: admin(), supportCaseId: supportCaseId, reason: 'Customer replied'
        ])
        ok(result)
        supportCase.refresh()
        assert supportCase.statusId == 'SUPCS_OPEN'
        assert supportCase.resolvedAt == null
        assert supportCase.closedAt == null
        assert supportCase.getLong('reopenedCount') == 1L
        assert from('SupportCaseStatus').where('supportCaseId', supportCaseId).queryCount() == 5
    }

    void testInvalidStatusTransition() {
        String supportCaseId = createCase('Invalid transition')
        Map result = dispatcher.runSync('changeSupportCaseStatus', [
                userLogin: admin(), supportCaseId: supportCaseId, statusId: 'SUPCS_CLOSED', note: 'Invalid'
        ])
        assert ServiceUtil.isError(result)
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert supportCase.statusId == 'SUPCS_NEW'
    }

    void testThreadAndInternalNote() {
        String supportCaseId = createCase('Thread and note')
        Map noteResult = dispatcher.runSync('addSupportCaseInternalNote', [
                userLogin: agentLogin(),
                supportCaseId: supportCaseId,
                subject: 'Internal note',
                content: 'Only staff can see this'
        ])
        ok(noteResult)
        assert noteResult.communicationEventId

        Map threadResult = dispatcher.runSync('getSupportCaseThread', [
                userLogin: agentLogin(), supportCaseId: supportCaseId
        ])
        ok(threadResult)
        assert threadResult.supportCaseThread.size() == 1
        assert threadResult.supportCaseThread[0].directionEnumId == 'SUPMSG_INTERNAL'
        assert threadResult.supportCaseThread[0].isInternal == 'Y'
    }

    void testReplySupportCase() {
        String supportCaseId = createCase('Reply')
        Map replyResult = dispatcher.runSync('replySupportCase', [
                userLogin: agentLogin(),
                supportCaseId: supportCaseId,
                subject: 'Answer',
                content: 'Here is the answer',
                sendNow: false
        ])
        ok(replyResult)
        assert replyResult.communicationEventId

        GenericValue event = from('CommunicationEvent')
                .where('communicationEventId', replyResult.communicationEventId).queryOne()
        assert event.communicationEventTypeId == 'EMAIL_COMMUNICATION'
        assert event.statusId == 'COM_IN_PROGRESS'
        assert event.contactMechIdFrom == 'TST_SUP_AGENT_EMAIL'
        assert event.contactMechIdTo == 'TST_SUP_CUST_EMAIL'

        GenericValue link = from('SupportCaseCommEvent')
                .where('supportCaseId', supportCaseId, 'communicationEventId', replyResult.communicationEventId).queryOne()
        assert link.directionEnumId == 'SUPMSG_OUT'
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert supportCase.statusId == 'SUPCS_OPEN'
        assert supportCase.firstResponseAt != null

        Map threadResult = dispatcher.runSync('getSupportCaseThread', [
                userLogin: agentLogin(), supportCaseId: supportCaseId
        ])
        ok(threadResult)
        assert threadResult.supportCaseThread.size() == 1
    }

    void testRouteInboundEmail() {
        Map inbound = dispatcher.runSync('createCommunicationEvent', [
                userLogin: admin(),
                communicationEventTypeId: 'AUTO_EMAIL_COMM',
                statusId: 'COM_ENTERED',
                partyIdFrom: 'TEST_SUPPORT_CUST',
                partyIdTo: 'TEST_SUPPORT_AGENT',
                subject: 'Inbound question',
                content: 'Please help with my contract',
                contentMimeTypeId: 'text/plain',
                entryDate: UtilDateTime.nowTimestamp()
        ])
        ok(inbound)
        String inboundId = inbound.communicationEventId
        GenericValue link = from('SupportCaseCommEvent').where('communicationEventId', inboundId).queryOne()
        assert link != null
        String supportCaseId = link.supportCaseId
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert supportCase.customerPartyId == 'TEST_SUPPORT_CUST'
        assert supportCase.sourceEnumId == 'SUPCSRC_EMAIL'
        assert supportCase.rootCommunicationEventId == inboundId

        Map reply = dispatcher.runSync('createCommunicationEvent', [
                userLogin: admin(),
                communicationEventTypeId: 'AUTO_EMAIL_COMM',
                statusId: 'COM_ENTERED',
                partyIdFrom: 'TEST_SUPPORT_CUST',
                partyIdTo: 'TEST_SUPPORT_AGENT',
                parentCommEventId: inboundId,
                origCommEventId: inboundId,
                subject: "Re: [SUP-${supportCaseId}] Inbound question",
                content: 'Additional information',
                contentMimeTypeId: 'text/plain',
                entryDate: UtilDateTime.nowTimestamp()
        ])
        ok(reply)
        GenericValue replyLink = from('SupportCaseCommEvent')
                .where('communicationEventId', reply.communicationEventId).queryOne()
        assert replyLink != null
        assert replyLink.supportCaseId == supportCaseId
        assert from('SupportCaseStatus').where('supportCaseId', supportCaseId).queryCount() >= 2
    }

    void testAutoAssignmentAndSla() {
        String supportCaseId = createCaseWithContract('Auto assignment and SLA')
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        assert supportCase.queueId == 'SUPPORT_DEFAULT'
        assert supportCase.assigneePartyId == 'TEST_SUPPORT_AGENT'
        assert supportCase.slaId == 'TEST_SUP_SLA'
        long firstResponseMinutes = (supportCase.firstResponseDueAt.time - supportCase.openedAt.time) / 60000L
        long resolutionMinutes = (supportCase.resolutionDueAt.time - supportCase.openedAt.time) / 60000L
        assert firstResponseMinutes == 60L
        assert resolutionMinutes == 120L
    }

    void testEscalation() {
        String supportCaseId = createCaseWithContract('Escalation')
        GenericValue supportCase = from('SupportCase').where('supportCaseId', supportCaseId).queryOne()
        Timestamp past = new Timestamp(System.currentTimeMillis() - 3_600_000L)
        supportCase.set('firstResponseDueAt', past)
        supportCase.set('resolutionDueAt', past)
        supportCase.store()

        Map result = dispatcher.runSync('escalateOverdueSupportCases', [userLogin: admin()])
        ok(result)
        assert result.escalatedCount >= 1
        supportCase.refresh()
        assert supportCase.getLong('escalationLevel') == 1L
        assert supportCase.lastEscalatedAt != null

        GenericValue link = from('SupportCaseWorkEffort').where('supportCaseId', supportCaseId).queryFirst()
        assert link != null
        GenericValue workEffort = from('WorkEffort').where('workEffortId', link.workEffortId).queryOne()
        assert workEffort != null
        GenericValue financeTask = from('FinanceContractWorkEffort')
                .where('financeContractId', 'TEST_SUPPORT_CTR', 'workEffortId', link.workEffortId).queryOne()
        assert financeTask != null
    }

    void testFindSupportCases() {
        createCase('Find one')
        createCase('Find two')
        Map result = dispatcher.runSync('findSupportCases', [
                userLogin: admin(), customerPartyId: 'TEST_SUPPORT_CUST'
        ])
        ok(result)
        assert result.supportCases.size() >= 2
        assert result.caseCount >= 2
    }
}

