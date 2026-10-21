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

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP S5: support metrics and dashboard data.
 */
class SupportReportTests extends OFBizTestCase {

    SupportReportTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
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
                description: 'Metrics test'
        ])
        ok(result)
        return result.supportCaseId
    }

    void testMetrics() {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        Timestamp past = new Timestamp(now.time - 3_600_000L)
        Timestamp future = new Timestamp(now.time + 3_600_000L)

        String resolvedId = createCase('Resolved metric case')
        Thread.sleep(5)
        Timestamp resolvedAt = new Timestamp(System.currentTimeMillis())
        GenericValue resolved = from('SupportCase').where('supportCaseId', resolvedId).queryOne()
        resolved.set('statusId', 'SUPCS_CLOSED')
        resolved.set('firstResponseAt', resolvedAt)
        resolved.set('firstResponseDueAt', future)
        resolved.set('resolvedAt', resolvedAt)
        resolved.set('resolutionDueAt', future)
        resolved.set('closedAt', resolvedAt)
        resolved.set('satisfactionScore', 5)
        resolved.store()

        String overdueId = createCase('Overdue metric case')
        GenericValue overdue = from('SupportCase').where('supportCaseId', overdueId).queryOne()
        overdue.set('firstResponseDueAt', past)
        overdue.set('resolutionDueAt', past)
        overdue.store()

        Map result = dispatcher.runSync('getSupportMetrics', [
                userLogin: admin(),
                financeContractId: 'TEST_SUPPORT_CTR',
                fromDate: new Timestamp(now.time - 7_200_000L),
                thruDate: new Timestamp(now.time + 7_200_000L)
        ])
        ok(result)
        Map metrics = result.metrics
        assert metrics.openedCount >= 2
        assert metrics.resolvedCount >= 1
        assert metrics.closedCount >= 1
        assert metrics.unresolvedCount >= 1
        assert metrics.overdueCount >= 1
        assert metrics.csatCount >= 1
        assert metrics.csatAverage == 5.0
        assert metrics.csatScore5 >= 1
        assert metrics.slaComplianceRate >= 0.0
        assert metrics.byQueue
        assert metrics.byAssignee
        assert metrics.byCaseType
        assert metrics.byQueue.every { it.name != null }
        assert metrics.csvRows

        Map boundary = dispatcher.runSync('getSupportMetrics', [
                userLogin: admin(),
                financeContractId: 'TEST_SUPPORT_CTR',
                fromDate: resolvedAt,
                thruDate: resolvedAt
        ])
        ok(boundary)
        assert boundary.metrics.resolvedCount >= 1
        assert boundary.metrics.closedCount >= 1
    }

    void testReportPermission() {
        GenericValue agent = from('UserLogin').where('userLoginId', 'support_test_agent').queryOne()
        assert agent != null
        Map permission = dispatcher.runSync('supportReportPermission', [userLogin: agent])
        ok(permission)
        assert !permission.hasPermission
    }
}
