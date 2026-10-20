<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<#if supportCaseThread?has_content>
    <#list supportCaseThread as message>
        <#assign threadDepth = (message.depth)!0>
        <div style="margin-left: ${threadDepth * 20}px; padding: 8px 0; border-bottom: 1px solid #ddd;<#if ((message.isInternal)!'N') == 'Y'> background-color: #fff4e5;</#if>">
            <div>
                <strong>
                    <#if (message.entryDate)?has_content>${message.entryDate?string("yyyy-MM-dd HH:mm")}</#if>
                </strong>
                <#if ((message.directionEnumId)!'') == 'SUPMSG_IN'>
                    [IN]
                <#elseif ((message.directionEnumId)!'') == 'SUPMSG_OUT'>
                    [OUT]
                <#else>
                    [INTERNAL]
                </#if>
                <#if ((message.isInternal)!'N') == 'Y'>[${uiLabelMap.SupportInternalMessage}]</#if>
                <#if ((message.isAuto)!'N') == 'Y'>[${uiLabelMap.SupportAutoMessage}]</#if>
                <#if ((message.hasAttachments)!'N') == 'Y'>[${uiLabelMap.SupportAttachment}]</#if>
            </div>
            <div><strong>${((message.subject)!'')?html}</strong></div>
            <div>${((message.content)!'')?html?replace("\n", "<br/>")}</div>
            <div style="color: #666; font-size: 90%;">
                ${((message.partyIdFrom)!'')?html} &rarr; ${((message.partyIdTo)!'')?html}
                &nbsp; ${((message.communicationEventId)!'')?html}
            </div>
        </div>
    </#list>
<#else>
    <p>${uiLabelMap.SupportNoMessages}</p>
</#if>
