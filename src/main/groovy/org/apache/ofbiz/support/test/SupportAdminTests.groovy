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

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP S6: queue and SLA administration.
 */
class SupportAdminTests extends OFBizTestCase {

    SupportAdminTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    void testQueueAndSlaCrud() {
        String suffix = String.valueOf(System.currentTimeMillis() % 10000000000L)
        String queueId = 'TQ' + suffix
        String slaId = 'TS' + suffix

        Map queue = dispatcher.runSync('createSupportQueue', [
                userLogin: admin(), queueId: queueId, queueName: 'S6 test queue',
                assignStrategyEnumId: 'SUPASG_LEAST_LOAD', active: 'Y'
        ])
        ok(queue)
        Map updateQueue = dispatcher.runSync('updateSupportQueue', [
                userLogin: admin(), queueId: queueId, queueName: 'S6 test queue updated', active: 'Y'
        ])
        ok(updateQueue)
        GenericValue storedQueue = from('SupportQueue').where('queueId', queueId).queryOne()
        assert storedQueue.queueName == 'S6 test queue updated'

        Map member = dispatcher.runSync('createSupportQueueMember', [
                userLogin: admin(), queueId: queueId, partyId: 'TEST_SUPPORT_AGENT',
                capacity: 5, active: 'Y'
        ])
        ok(member)
        Map removeMember = dispatcher.runSync('removeSupportQueueMember', [
                userLogin: admin(), queueId: queueId, partyId: 'TEST_SUPPORT_AGENT'
        ])
        ok(removeMember)
        assert from('SupportQueueMember').where('queueId', queueId).queryCount() == 0

        String mappingId = 'TP' + suffix
        Map mapping = dispatcher.runSync('createSupportQueueProduct', [
                userLogin: admin(), supportQueueProductId: mappingId, queueId: queueId,
                contractTypeId: 'LOAN', sequenceNum: 1, active: 'Y'
        ])
        ok(mapping)
        Map removeMapping = dispatcher.runSync('removeSupportQueueProduct', [
                userLogin: admin(), supportQueueProductId: mappingId, queueId: queueId
        ])
        ok(removeMapping)
        assert from('SupportQueueProduct').where('supportQueueProductId', mappingId).queryCount() == 0

        Map sla = dispatcher.runSync('createSupportSla', [
                userLogin: admin(), slaId: slaId, description: 'S6 test SLA',
                firstResponseMinutes: 60, resolutionMinutes: 480, active: 'Y', sequenceNum: 99
        ])
        ok(sla)
        Map updateSla = dispatcher.runSync('updateSupportSla', [
                userLogin: admin(), slaId: slaId, description: 'S6 test SLA updated',
                firstResponseMinutes: 120, resolutionMinutes: 960, active: 'Y', sequenceNum: 99
        ])
        ok(updateSla)
        GenericValue storedSla = from('SupportSla').where('slaId', slaId).queryOne()
        assert storedSla.description == 'S6 test SLA updated'
        assert storedSla.firstResponseMinutes == 120
    }
}
